package com.dacia.hybridcluster.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.SharedPreferences
import com.dacia.hybridcluster.engine.PowertrainCalculator
import com.dacia.hybridcluster.engine.TripEnergyTracker
import com.dacia.hybridcluster.model.ConfigManager
import com.dacia.hybridcluster.model.TelemetryData
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.util.concurrent.Executors

enum class ObdConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

/**
 * Orkiestracja połączenia z adapterem OBD.
 *
 * Cała komunikacja (łączenie, inicjalizacja, odpytywanie) odbywa się w JEDNEJ korutynie na
 * dedykowanym wątku "obd-io" – nie ma już wyścigu, w którym connectDevice() z innego wątku
 * zamykał gniazdo w trakcie sendCommand(). UI tylko zgłasza żądanie (zmienia [requestedDevice]
 * i podbija [requestGeneration]); pętla reaguje na nie w najbliższym cyklu.
 */
object ObdManager {

    private val _telemetryState = MutableStateFlow(TelemetryData())
    val telemetryState: StateFlow<TelemetryData> = _telemetryState.asStateFlow()

    private val _connectionStatus = MutableStateFlow(ObdConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<ObdConnectionStatus> = _connectionStatus.asStateFlow()

    private val _connectedDeviceName = MutableStateFlow<String?>(null)
    val connectedDeviceName: StateFlow<String?> = _connectedDeviceName.asStateFlow()

    private val obdDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "obd-io").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(obdDispatcher + SupervisorJob())
    private var job: Job? = null

    private val tripTracker = TripEnergyTracker()
    private val calculator = PowertrainCalculator(tripTracker)
    private val demoTrip = TripEnergyTracker() // osobny trip – demo nie zaśmieca prawdziwego
    private val demoSource = DemoSource(demoTrip)

    @Volatile private var requestedDevice: BluetoothDevice? = null
    @Volatile private var requestGeneration = 0
    @Volatile private var manualDisconnect = false
    @Volatile private var activeLink: ElmLink? = null
    @Volatile private var tripResetRequested = false

    private var appContext: Context? = null
    private var tripPrefs: SharedPreferences? = null
    private var lastTripSaveMs = 0L

    private val obdLog = object : ObdLog {
        override val enabled: Boolean get() = ObdLogger.isEnabled()
        override fun log(message: String) = ObdLogger.log(message)
    }

    val isConnected: Boolean
        get() = _connectionStatus.value == ObdConnectionStatus.CONNECTED

    /** Wołane z [com.dacia.hybridcluster.DaciaApp] – odtwarza trip zapisany przed restartem procesu. */
    fun init(context: Context) {
        appContext = context.applicationContext
        tripPrefs = context.getSharedPreferences("trip_state", Context.MODE_PRIVATE)
        tripPrefs?.let { p ->
            tripTracker.restore(
                TripEnergyTracker.Snapshot(
                    elecJoules = p.getDouble("elecJ"),
                    regenJoules = p.getDouble("regenJ"),
                    fuelLiters = p.getDouble("fuelL"),
                    distanceMeters = p.getDouble("distM"),
                    evDistanceMeters = p.getDouble("evDistM"),
                    lastActivityMs = p.getLong("lastMs", 0L)
                )
            )
        }
    }

    /** Idempotentne – wołane z telefonu i z obu sesji Android Auto. */
    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { mainLoop() }
    }

    fun stop() {
        job?.cancel()
        activeLink?.close()
    }

    /** Reset tripu wykonywany na wątku OBD (bez wyścigu z całkowaniem). */
    fun resetTrip() {
        tripResetRequested = true
        if (!isConnected || ConfigManager.configState.value.demoMode) {
            scope.launch { applyTripReset() }
        }
    }

    fun connectDevice(device: BluetoothDevice) {
        manualDisconnect = false
        requestedDevice = device
        requestGeneration++
        activeLink?.close() // przerwij ewentualne blokujące czytanie starego połączenia
    }

    fun disconnectBluetooth() {
        manualDisconnect = true
        requestedDevice = null
        requestGeneration++
        activeLink?.close()
    }

    // ---------------------------------------------------------------------

    private suspend fun mainLoop() {
        var lastAutoConnectAttempt = 0L
        while (currentCoroutineContext().isActive) {
            if (ConfigManager.configState.value.demoMode) {
                runDemoLoop()
                continue
            }
            val device = requestedDevice ?: savedDeviceForAutoConnect()?.takeIf {
                val now = System.currentTimeMillis()
                (now - lastAutoConnectAttempt > AUTO_CONNECT_INTERVAL_MS).also { ok -> if (ok) lastAutoConnectAttempt = now }
            }
            if (device == null) {
                if (_connectionStatus.value == ObdConnectionStatus.CONNECTED) setDisconnected()
                delay(500)
                continue
            }
            val generation = requestGeneration
            val link = openLink(device) ?: run {
                if (requestedDevice === device) requestedDevice = null // ręczne połączenie: nie ponawiaj w kółko
                delay(1500)
                null
            } ?: continue
            if (generation == requestGeneration) requestedDevice = null
            runSession(link, generation)
        }
    }

    private fun savedDeviceForAutoConnect(): BluetoothDevice? {
        if (manualDisconnect) return null
        val address = ConfigManager.configState.value.selectedBtDeviceAddress ?: return null
        return try {
            bluetoothAdapter()?.getRemoteDevice(address)
        } catch (_: Exception) {
            null
        }
    }

    @SuppressLint("MissingPermission")
    private fun openLink(device: BluetoothDevice): ElmLink? {
        val name = try { device.name } catch (_: SecurityException) { null } ?: device.address
        _connectionStatus.value = ObdConnectionStatus.CONNECTING
        _connectedDeviceName.value = name
        return try {
            try { bluetoothAdapter()?.cancelDiscovery() } catch (_: SecurityException) {}
            val link = BluetoothElmLink.connect(device)
            ObdLogger.log("Połączono przez Bluetooth z: $name")
            ConfigManager.updateConfig(
                ConfigManager.configState.value.copy(
                    selectedBtDeviceAddress = device.address,
                    selectedBtDeviceName = name,
                    demoMode = false
                )
            )
            link
        } catch (e: Exception) {
            ObdLogger.log("Błąd połączenia z urządzeniem: ${e.message}")
            _connectionStatus.value = ObdConnectionStatus.ERROR
            null
        }
    }

    /** Jedna sesja połączenia: inicjalizacja adaptera + pętla odpytywania do błędu lub zmiany żądania. */
    private suspend fun runSession(link: ElmLink, generation: Int) {
        activeLink = link
        _connectionStatus.value = ObdConnectionStatus.CONNECTED
        val protocol = ObdProtocol(link, obdLog, { ConfigManager.configState.value.useVlinkerFastProtocol })
        val poller = PidPoller(protocol)
        try {
            protocol.initialize()
            // Trip NIE jest już zerowany przy każdym połączeniu – patrz TripEnergyTracker.onConnectionStarted()
            tripTracker.onConnectionStarted()
            calculator.reset()
            poller.resetReadings()
            poller.detectCapabilities(obdLog)

            while (currentCoroutineContext().isActive &&
                generation == requestGeneration &&
                !ConfigManager.configState.value.demoMode
            ) {
                if (tripResetRequested) applyTripReset()
                val cfg = ConfigManager.configState.value
                poller.pollCycle(PidPoller.Options(cfg.showTemperatures, cfg.show12vBattery))
                _telemetryState.value = calculator.compute(poller.raw)
                maybePersistTrip()
                delay(if (protocol.stpxSupported && cfg.useVlinkerFastProtocol) 2L else 15L)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            if (generation == requestGeneration) {
                ObdLogger.log("Zerwane połączenie OBD: ${e.message}")
                _connectionStatus.value = ObdConnectionStatus.ERROR
            }
        } catch (e: Exception) {
            ObdLogger.log("Nieoczekiwany błąd pętli OBD: ${e.javaClass.simpleName}: ${e.message}")
            _connectionStatus.value = ObdConnectionStatus.ERROR
        } finally {
            persistTrip()
            link.close()
            if (activeLink === link) activeLink = null
            if (manualDisconnect) setDisconnected()
        }
    }

    private suspend fun runDemoLoop() {
        activeLink?.close()
        _connectionStatus.value = ObdConnectionStatus.CONNECTED
        _connectedDeviceName.value = "Symulator Demo"
        while (ConfigManager.configState.value.demoMode && currentCoroutineContext().isActive) {
            _telemetryState.value = demoSource.next()
            delay(150)
        }
        setDisconnected()
    }

    private fun setDisconnected() {
        _connectionStatus.value = ObdConnectionStatus.DISCONNECTED
        _connectedDeviceName.value = null
    }

    private fun applyTripReset() {
        tripResetRequested = false
        tripTracker.reset()
        demoTrip.reset()
        persistTrip()
        val t = _telemetryState.value
        _telemetryState.value = t.copy(
            tripDistanceKm = 0.0, tripEvDistancePercent = 0.0, tripEvEnergySharePercent = 0.0,
            tripRegenKWh = 0.0, tripUsedElecKWh = 0.0, tripFuelSavedLiters = 0.0
        )
    }

    private fun maybePersistTrip() {
        val now = System.currentTimeMillis()
        if (now - lastTripSaveMs > TRIP_SAVE_INTERVAL_MS) persistTrip()
    }

    private fun persistTrip() {
        lastTripSaveMs = System.currentTimeMillis()
        val s = tripTracker.snapshot()
        tripPrefs?.edit()
            ?.putDouble("elecJ", s.elecJoules)
            ?.putDouble("regenJ", s.regenJoules)
            ?.putDouble("fuelL", s.fuelLiters)
            ?.putDouble("distM", s.distanceMeters)
            ?.putDouble("evDistM", s.evDistanceMeters)
            ?.putLong("lastMs", s.lastActivityMs)
            ?.apply()
    }

    private fun SharedPreferences.getDouble(key: String): Double =
        java.lang.Double.longBitsToDouble(getLong(key, 0L))

    private fun SharedPreferences.Editor.putDouble(key: String, v: Double): SharedPreferences.Editor =
        putLong(key, java.lang.Double.doubleToRawLongBits(v))

    private fun bluetoothAdapter(): BluetoothAdapter? =
        (appContext?.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private const val AUTO_CONNECT_INTERVAL_MS = 10_000L
    private const val TRIP_SAVE_INTERVAL_MS = 10_000L
}
