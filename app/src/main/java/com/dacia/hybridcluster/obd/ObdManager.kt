package com.dacia.hybridcluster.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import com.dacia.hybridcluster.engine.HybridModeDetector
import com.dacia.hybridcluster.engine.TripEnergyTracker
import com.dacia.hybridcluster.model.ConfigManager
import com.dacia.hybridcluster.model.TelemetryData
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlin.math.abs
import kotlin.math.sin

enum class ObdConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

object ObdManager {
    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private val _telemetryState = MutableStateFlow(TelemetryData())
    val telemetryState: StateFlow<TelemetryData> = _telemetryState

    private val _connectionStatus = MutableStateFlow(ObdConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<ObdConnectionStatus> = _connectionStatus

    private val _connectedDeviceName = MutableStateFlow<String?>(null)
    val connectedDeviceName: StateFlow<String?> = _connectedDeviceName

    private val tripTracker = TripEnergyTracker()
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var socket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    @Volatile private var connecting = false
    @Volatile private var lastAutoConnectAttempt = 0L

    val isConnected: Boolean
        get() = _connectionStatus.value == ObdConnectionStatus.CONNECTED && socket != null

    /** Idempotentne – wołane z telefonu i z obu sesji Android Auto. */
    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                if (ConfigManager.configState.value.demoMode) {
                    _connectedDeviceName.value = "Symulator Demo"
                    runDemoLoop()
                    if (socket == null) {
                        _connectionStatus.value = ObdConnectionStatus.DISCONNECTED
                        _connectedDeviceName.value = null
                    }
                } else {
                    val savedAddress = ConfigManager.configState.value.selectedBtDeviceAddress
                    val now = System.currentTimeMillis()
                    if (savedAddress != null && socket == null && !connecting &&
                        now - lastAutoConnectAttempt > 10_000
                    ) {
                        lastAutoConnectAttempt = now
                        tryAutoConnect(savedAddress)
                    }
                    runObdPollingLoop()
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        disconnectBluetooth()
    }

    fun resetTrip() {
        tripTracker.reset()
    }

    @SuppressLint("MissingPermission")
    private fun tryAutoConnect(address: String) {
        try {
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
            connectDevice(adapter.getRemoteDevice(address))
        } catch (_: Exception) {}
    }

    @SuppressLint("MissingPermission")
    fun connectDevice(device: BluetoothDevice) {
        if (connecting) return
        connecting = true
        scope.launch {
            try {
                closeSocket()
                _connectionStatus.value = ObdConnectionStatus.CONNECTING
                _connectedDeviceName.value = device.name ?: device.address

                BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery()

                val s = device.createRfcommSocketToServiceRecord(SPP_UUID)
                s.connect()
                socket = s
                inputStream = s.inputStream
                outputStream = s.outputStream

                _connectionStatus.value = ObdConnectionStatus.CONNECTED
                ObdLogger.log("Połączono przez Bluetooth z: ${device.name ?: device.address}")
                ConfigManager.updateConfig(
                    ConfigManager.configState.value.copy(
                        selectedBtDeviceAddress = device.address,
                        selectedBtDeviceName = device.name ?: device.address,
                        demoMode = false
                    )
                )
            } catch (e: Exception) {
                closeSocket()
                ObdLogger.log("Błąd połączenia z urządzeniem: ${e.message}")
                _connectionStatus.value = ObdConnectionStatus.ERROR
            } finally {
                connecting = false
            }
        }
    }

    fun disconnectBluetooth() {
        closeSocket()
        _connectionStatus.value = ObdConnectionStatus.DISCONNECTED
        _connectedDeviceName.value = null
    }

    private fun closeSocket() {
        try { inputStream?.close() } catch (_: Exception) {}
        try { outputStream?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        inputStream = null
        outputStream = null
    }

    // ---------------------------------------------------------------------
    // TRYB DEMO (Symulacja na emulatorze lub bez OBD)
    // ---------------------------------------------------------------------

    private suspend fun runDemoLoop() {
        _connectionStatus.value = ObdConnectionStatus.CONNECTED
        var tick = 0.0
        while (ConfigManager.configState.value.demoMode && currentCoroutineContext().isActive) {
            tick += 0.1

            val cycle = (sin(tick * 0.5) + 1.0) / 2.0 // 0.0 do 1.0
            val isBraking = sin(tick * 0.5) < -0.2

            val speed = (cycle * 85.0).coerceAtLeast(0.0)
            val gear = when {
                speed < 1.0 -> "P"
                speed < 18.0 -> "D1"
                speed < 38.0 -> "D2"
                speed < 62.0 -> "D3"
                else -> "D4"
            }

            val iceRpm = if (isBraking || speed < 15.0) 0 else (1200 + cycle * 2200).toInt()
            val icePower = if (isBraking || speed < 15.0) 0.0 else (cycle * 55.0)

            val rearPower = if (isBraking) 0.0 else (cycle * 24.0)
            val hsgPower = if (isBraking) 0.0 else (if (cycle > 0.6) 8.0 else 2.0)

            val totalOutput = if (isBraking) 0.0 else (icePower + rearPower + hsgPower)
            val rearRegen = if (isBraking) (18.0 * cycle).coerceAtLeast(4.0) else 0.0
            val frontRegen = if (isBraking) (6.0 * cycle).coerceAtLeast(2.0) else 0.0
            val totalRegen = rearRegen + frontRegen

            val hvVoltage = 48.0 + sin(tick) * 1.5
            val currentAmps = if (isBraking) (totalRegen * 1000.0) / hvVoltage else -((rearPower + hsgPower) * 1000.0) / hvVoltage

            val v12Sim = 13.8 + sin(tick * 0.3) * 0.6
            val a12Sim = (14.0 + sin(tick * 0.6) * 10.0 + if (isBraking) 12.0 else 0.0).coerceAtLeast(3.0)

            val frontPower = icePower + hsgPower
            val totalShare = frontPower + rearPower
            val (frontPct, rearPct) = when {
                totalShare > 0.5 -> {
                    val fp = ((frontPower / totalShare) * 100.0).toInt().coerceIn(0, 100)
                    Pair(fp, 100 - fp)
                }
                speed < 1.0 -> Pair(50, 50)
                iceRpm == 0 && speed >= 1.0 -> Pair(0, 100) // Ruszanie/manewry EV na tylnym silniku
                else -> Pair(100, 0)
            }

            tripTracker.update(
                voltage = hvVoltage,
                current = currentAmps,
                speedKmH = speed,
                iceRpm = iceRpm,
                fuelRateLh = if (icePower > 0) 4.8 else 0.0,
                isRegenActive = isBraking,
                regenPowerKw = totalRegen,
                rearMotorPowerKw = rearPower
            )

            val w12Sim = (v12Sim * a12Sim).coerceAtLeast(0.0)
            val w12LoadSim = (w12Sim * 0.82).coerceAtLeast(0.0)
            val w12NetSim = w12Sim - w12LoadSim

            val isCoastingDemo = (!isBraking && cycle < 0.08 && speed > 5.0)
            val isEvDemo = (!isBraking && rearPower > 0.5 && icePower <= 0.1)

            val mode = when {
                isBraking -> "REKUPERACJA"
                isCoastingDemo -> "ŻEGLOWANIE"
                isEvDemo -> "PURE EV"
                icePower > 8.0 && rearPower > 3.0 -> "E-AWD BOOST"
                icePower > 0 -> "HYBRYDA AUTO"
                else -> "EV / READY"
            }

            val eGearDemo = when {
                gear == "R" -> if (speed > 20.0) "R2" else "R1"
                speed > 45.0 -> "E2"
                speed > 1.0 -> "E1"
                else -> if (gear == "P" || gear == "N") "N" else "E1"
            }
            val fuelBoostDemo = (icePower > 35.0 || (cycle > 0.7 && icePower > 15.0))
            val sssInhibitDemo = when {
                iceRpm > 0 && cycle in 0.25..0.40 -> "BLOKADA EV: KLIMA / NAWIEW\nBLOKADA EV: ZIMNY SILNIK"
                iceRpm > 0 && cycle in 0.40..0.58 -> "BLOKADA EV: KLIMA / NAWIEW"
                else -> ""
            }

            _telemetryState.value = TelemetryData(
                speedKmH = speed,
                currentGear = gear,
                eGear = eGearDemo,
                iceRpm = iceRpm,
                icePowerKw = icePower,
                rearMotorPowerKw = if (isCoastingDemo || isBraking) 0.0 else rearPower,
                hsgPowerKw = hsgPower,
                totalOutputPowerKw = totalOutput,
                totalRegenPowerKw = totalRegen,
                iceTorqueNm = if (icePower > 0) 140.0 else 0.0,
                rearMotorTorqueNm = if (isBraking) -55.0 else (if (isCoastingDemo) 0.0 else rearPower * 4.5),
                rearMotorRpm = (speed * 88.7).toInt(),
                hsgVoltage48v = 53.0,
                hsgLoadPercent = 22.0,
                hsgPowerWatts = hsgPower * 1000.0,
                hvBatterySocPercent = 68.0 - (tick * 0.01) % 15.0,
                hvBatteryVoltage = hvVoltage,
                hvBatteryCurrent = currentAmps,
                hvBatteryTempC = 24.5 + sin(tick * 0.1) * 2.0,
                bat12vVoltage = v12Sim,
                bat12vCurrent = a12Sim,
                bat12vSocPercent = (82 + sin(tick * 0.1) * 6.0).toInt().coerceIn(70, 95),
                bat12vPowerWatts = w12Sim,
                bat12vLoadWatts = w12LoadSim,
                bat12vNetChargeWatts = w12NetSim,
                coolantTempC = 88.0,
                oilTempC = 85.0,
                gearboxTempC = 81.0,
                rearMotorTempC = 44.0,
                rearInverterTempC = 41.0,
                fuelInjectionBoostActive = fuelBoostDemo,
                startStopInhibitReason = sssInhibitDemo,
                frontTorquePercent = frontPct,
                rearTorquePercent = rearPct,
                gForce = if (isBraking) -0.32 else (cycle * 0.42),
                pitchDegrees = sin(tick * 0.2) * 6.0,
                hybridMode = mode,
                tripDistanceKm = tripTracker.getDistanceKm(),
                tripEvDistancePercent = tripTracker.getEvDistancePercent(),
                tripEvEnergySharePercent = tripTracker.getEvEnergySharePercent(),
                tripRegenKWh = tripTracker.getRegenKWh(),
                tripUsedElecKWh = tripTracker.getUsedElecKWh(),
                tripFuelSavedLiters = tripTracker.getFuelSavedLiters()
            )

            delay(150)
        }
    }

    // ---------------------------------------------------------------------
    // TRYB RZECZYWISTY (vLinker / ELM327)
    // Dacia CMF-B: 29-bit CAN (ATSP7, ATCP18)
    // ---------------------------------------------------------------------

    private suspend fun runObdPollingLoop() {
        if (!isConnected) {
            delay(1500)
            return
        }
        try {
            val isFastMode = ConfigManager.configState.value.useVlinkerFastProtocol
            ObdLogger.log("Inicjalizacja OBD (Profil: ${if (isFastMode) "vLinker MS / STN" else "Standardowy ELM327"})")

            // 1. Podstawowa konfiguracja adaptera
            sendCommand("ATZ", timeoutMs = 2000)
            sendCommand("ATE0")   // echo off
            sendCommand("ATL0")   // no linefeed
            sendCommand("ATS0")   // no spaces
            sendCommand("ATH0")   // headers off w treści odpowiedzi

            tripTracker.reset()
            pidFailures.clear()
            stpxSupported = false
            lastElmConfiguredEcu = ""
            currentTargetEcu = ""
            currentRespEcu = ""

            // Identyfikacja układu STN / vLinker
            val stnInfo = sendCommand("STI", timeoutMs = 400)
            val isStnChip = !stnInfo.contains("?") && (stnInfo.contains("STN", ignoreCase = true) || stnInfo.contains("vLinker", ignoreCase = true) || stnInfo.contains("OBDLink", ignoreCase = true))

            if (isFastMode) {
                if (isStnChip) {
                    ObdLogger.log("Wykryto procesor STN: '$stnInfo'")
                } else {
                    ObdLogger.log("Wybrano profil vLinker MS (STI: '$stnInfo')")
                }
                // vLinker MS / STN2120 optymalizacje:
                // ATAT2: Aggressive Adaptive Timing (błyskawiczne dopasowanie czasów odpowiedzi ECU)
                // ATST14: Krótki timeout (20 * 4ms = ~80ms) eliminujący zawieszenia (ECU X1311 odpowiadają w 2-18ms)
                sendCommand("ATAT2")
                sendCommand("ATST14")
            } else {
                ObdLogger.log("Wybrano profil standardowy ELM327 (ATAT1, standardowe timeouty)")
                sendCommand("ATAT1")
                sendCommand("ATST64")
            }

            // 2. Dacia CMF-B (X1311) używa ISO 15765-4 CAN 29-bit 500k (ATSP7)
            // WAŻNE: ATFCSH musi poprzedzać ATFCSM1, inaczej STN2120 zwraca '?' przy braku nagłówka Flow Control!
            sendCommand("ATSP7")
            sendCommand("ATCP18")
            sendCommand("ATFCSH18DA15F1")
            sendCommand("ATFCSD300000")
            sendCommand("ATFCSM1")

            // Sprawdzenie bezpośredniego wsparcia rozkazu STPX (STN2120 / vLinker MS)
            if (isFastMode) {
                val stpxTest = sendCommand("STPX h:18DA15F1, d:0100, t:80, r:1", timeoutMs = 250)
                if (!stpxTest.contains("?")) {
                    stpxSupported = true
                    ObdLogger.log("Protokół STPX aktywny: bezpośrednie adresowanie pakietów CAN (1 komenda zamiast 4)!")
                } else {
                    stpxSupported = false
                    ObdLogger.log("Adapter nie zaakceptował rozkazu STPX, automatyczny powrót do trybu ELM327")
                }
            }

            var t = TelemetryData()

            // Wykrycie obsługiwanego PID-u rzeczywistego momentu silnika w EMS3253 (18DA15F1):
            // 1. 221039 = Estimated engine torque (step 0.03125, offset -400.0 Nm)
            // 2. 22117B = Estimated motor torque (signed 16-bit Nm)
            // 3. 0104   = Calculated Engine Load % (OBD-II)
            setTargetEcu("18DA15F1", "18DAF115")
            val ecmTorquePid = when {
                query("221039", "621039") != null -> {
                    ObdLogger.log("ECM 18DA15F1: Wykryto moment silnika z PID 221039 (Estimated engine torque)")
                    "221039"
                }
                query("22117B", "62117B") != null -> {
                    ObdLogger.log("ECM 18DA15F1: Wykryto moment silnika z PID 22117B (Estimated motor torque)")
                    "22117B"
                }
                else -> {
                    ObdLogger.log("ECM 18DA15F1: Użycie PID 0104 (Calculated Engine Load) do momentu silnika")
                    "0104"
                }
            }

            var loopCounter = 0
            var ibsSoc: Int? = null
            var eradGearRaw = 2 // 0=N, 1=E1, 2=E2 (domyślnie bieg 2 szosowy)
            var smoothedFrontPct = 50.0
            var regenConfirmCount = 0
            var cachedHsgPowerKw = 0.0
            var cachedHsgRegenKw = 0.0
            var cachedIceTorque = 0.0
            var cachedIceTorqueKnown = false
            var ssDisplayCode = 0
            var ssHvacForbid = false
            var ssHvForbid = false
            var ssPowertrainForbid = false
            var ssVeemForbid = false
            var ssPtcActive = false
            var ssCoolingForbid = false
            var ssSailingClutchForbid = false
            var ssHfpForbid = false
            var ssAbsForbid = false
            var ssStatusRaw = 1

            while (!ConfigManager.configState.value.demoMode && isConnected &&
                currentCoroutineContext().isActive
            ) {
                loopCounter++
                val cfg = ConfigManager.configState.value

                var speed = t.speedKmH
                var rpm = t.iceRpm
                var coolant = t.coolantTempC
                var oilTemp = t.oilTempC
                var gearboxTemp = t.gearboxTempC
                var v12 = t.bat12vVoltage
                var bat12vWatts = t.bat12vPowerWatts
                var bat12vAmps = t.bat12vCurrent
                var hsgVolt48 = t.hsgVoltage48v
                var hsgLoad = t.hsgLoadPercent
                var hvVolt = t.hvBatteryVoltage
                var hvAmp = t.hvBatteryCurrent
                var hvSoc = t.hvBatterySocPercent
                var hvTemp = t.hvBatteryTempC
                var inverterTemp = t.rearInverterTempC
                var rearTorque = t.rearMotorTorqueNm
                var rearRpm = t.rearMotorRpm
                var rearTemp = t.rearMotorTempC
                var gearStr = t.currentGear
                var eGearStr = t.eGear
                var fuelBoost = t.fuelInjectionBoostActive
                var startStopReason = t.startStopInhibitReason
                var iceTorque = cachedIceTorque
                var iceTorqueKnown = cachedIceTorqueKnown

                // =================================================================
                // 1. PĘTLA SZYBKA (W KAŻDYM CYKLU - ~12-15 FPS z STPX):
                // - ECM 18DA15F1: RPM ICE (010C), Prędkość (010D), Moment ICE (1 komenda)
                // - eRAD 18DAE5F1: Obroty elektryczne wirnika (22FD10), Moment silnika tył (22FD11)
                // Obroty silnika elektrycznego odpytywane w KAŻDYM cyklu z tym samym odświeżaniem co ICE!
                // =================================================================
                setTargetEcu("18DA15F1", "18DAF115")

                // RPM silnika spalinowego (010C)
                query("010C", "410C")?.let {
                    if (it.size >= 2) rpm = ((it[0] * 256) + it[1]) / 4
                }

                // Prędkość pojazdu (010D)
                query("010D", "410D")?.let {
                    if (it.isNotEmpty()) speed = it[0].toDouble()
                }

                // Rzeczywisty moment obrotowy silnika spalinowego wg bazy EMS3253 (tylko 1 zapytanie na cykl!)
                when (ecmTorquePid) {
                    "221039" -> query("221039", "621039")?.let {
                        if (it.size >= 2) {
                            val raw = (it[0] * 256) + it[1]
                            val tq = (raw * 0.03125) - 400.0
                            iceTorque = if (rpm > 250 && tq > 0.0) tq.coerceIn(0.0, 240.0) else 0.0
                            iceTorqueKnown = true
                        }
                    }
                    "22117B" -> query("22117B", "62117B")?.let {
                        if (it.size >= 2) {
                            val raw = (it[0] * 256) + it[1]
                            val signedTq = if (raw > 32767) raw - 65536 else raw
                            iceTorque = if (rpm > 250 && signedTq > 0) signedTq.toDouble().coerceIn(0.0, 240.0) else 0.0
                            iceTorqueKnown = true
                        }
                    }
                    else -> query("0104", "4104")?.let {
                        if (it.isNotEmpty()) {
                            val loadRatio = (it[0] / 255.0).coerceIn(0.0, 1.0)
                            val maxTq = maxEngineTorqueAtRpm(rpm)
                            iceTorque = if (rpm > 250 && loadRatio > 0.16) {
                                (((loadRatio - 0.16) / 0.84) * maxTq).coerceIn(0.0, 235.0)
                            } else 0.0
                            iceTorqueKnown = true
                        }
                    }
                }
                if (rpm < 250) {
                    iceTorque = 0.0
                    cachedHsgPowerKw = 0.0
                    cachedHsgRegenKw = 0.0
                    hsgLoad = 0.0
                }
                cachedIceTorque = iceTorque
                cachedIceTorqueKnown = iceTorqueKnown

                setTargetEcu("18DAE5F1", "18DAF1E5")

                // Obroty wirnika silnika tylnego (22FD10 z INV_RE_48V_ERAD_RSA06: offset -25000 rpm)
                // Odpytywane w KAŻDYM cyklu równolegle z obrotami spalinowymi (010C)!
                query("22FD10", "62FD10")?.let {
                    if (it.size >= 2) {
                        val raw = (it[0] * 256) + it[1]
                        val calcRpm = if (raw > 0) raw - 25000 else 0
                        rearRpm = if (speed < 1.0 && Math.abs(calcRpm) <= 15) 0 else calcRpm
                    }
                }

                // Rzeczywisty moment obrotowy silnika tylnego (22FD11 z INV_RE_48V_ERAD_RSA06: step 0.1, offset -100.0 Nm)
                query("22FD11", "62FD11")?.let {
                    if (it.size >= 2) {
                        val raw = (it[0] * 256) + it[1]
                        rearTorque = (raw * 0.1) - 100.0
                    }
                }

                // =================================================================
                // 2. PĘTLA ŚREDNIA (1 zapytanie na cykl, rotacja co 3 cykle = ~4-5 Hz):
                // - reszta 0: Skrzynia biegów DW23 (18DA18F1:222135 - wałek nieparzysty tg1 i parzysty tg2)
                // - reszta 1: Prąd baterii 48V BMS Sunwoda (18DA92F1:229021)
                // - reszta 2: Aktualny bieg osi tylnej eRAD (18DAE5F1:22FD05)
                // =================================================================
                when (loopCounter % 3) {
                    0 -> {
                        setTargetEcu("18DA18F1", "18DAF118")
                        query("222135", "622135")?.let {
                            if (it.size >= 2) {
                                decodeGearbox(it[0], it[1], speed, rpm, gearStr)?.let { g -> gearStr = g }
                            }
                        }
                    }
                    1 -> {
                        setTargetEcu("18DA92F1", "18DAF192")
                        query("229021", "629021")?.let {
                            if (it.size >= 4) {
                                val raw = (it[0].toLong() shl 24) or (it[1].toLong() shl 16) or (it[2].toLong() shl 8) or it[3].toLong()
                                val signedMa = if (raw > 2147483647L) raw - 4294967296L else raw
                                val amps = signedMa / 1000.0
                                if (Math.abs(amps) <= 300.0) hvAmp = amps
                            }
                        }
                    }
                    2 -> {
                        setTargetEcu("18DAE5F1", "18DAF1E5")
                        query("22FD05", "62FD05")?.let {
                            if (it.isNotEmpty()) {
                                eradGearRaw = it[0]
                                eGearStr = decodeEradGear(it[0], gearStr == "R", eGearStr)
                            }
                        }
                    }
                }

                // =================================================================
                // 3. PĘTLA WOLNA (1 slot co 6 cykli - wyłącznie PID-y potrzebne do aktywnych kart UI!):
                // Nieużywane w UI PID-y (22FD7E inwerter, 229036 temp. baterii 48V, 2202DB) zostały usunięte z pętli głównej.
                // =================================================================
                if (loopCounter % 6 == 0) {
                    when ((loopCounter / 6) % 11) {
                        0 -> {
                            // Przedni rozruszniko-generator BSG 48V w EMS3253 (18DA15F1):
                            // W układzie MHEV 48V moc BSG jest liczona dynamicznie z bilansu szyny 48V w każdym szybkim cyklu.
                            // Odpytujemy 220157 (obciążenie alternatora/generatora) jako diagnostyczny wskaźnik obciążenia:
                            setTargetEcu("18DA15F1", "18DAF115")
                            query("220157", "620157")?.let {
                                if (it.isNotEmpty()) {
                                    hsgLoad = (it[0] * 0.3922).coerceIn(0.0, 100.0)
                                }
                            }
                        }
                        1 -> {
                            // SoC baterii 48V (BMS Sunwoda 18DA92F1:229103 - step 0.01 %)
                            setTargetEcu("18DA92F1", "18DAF192")
                            query("229103", "629103")?.let {
                                if (it.size >= 2) {
                                    val soc = ((it[0] * 256) + it[1]) * 0.01
                                    if (soc in 5.0..100.0) hvSoc = soc
                                }
                            }
                        }
                        2 -> {
                            // Napięcie pakietu 48V (BMS Sunwoda 18DA92F1:229017 - mV)
                            setTargetEcu("18DA92F1", "18DAF192")
                            query("229017", "629017")?.let {
                                if (it.size >= 2) {
                                    val v = ((it[0] * 256) + it[1]) / 1000.0
                                    if (v in 30.0..60.0) hvVolt = v
                                }
                            }
                        }
                        3 -> {
                            // Temperatura oleju skrzyni biegów w EMS3253 (18DA15F1:2230C1, offset -40.0)
                            // oraz stojana silnika tylnego eRAD (18DAE5F1:22FD60, offset -30.0)
                            // Odpytywane tylko gdy karta temperatur jest włączona
                            if (cfg.showTemperatures) {
                                setTargetEcu("18DA15F1", "18DAF115")
                                query("2230C1", "6230C1")?.let {
                                    if (it.isNotEmpty()) {
                                        val temp = (it[0] - 40).toDouble()
                                        if (temp in -30.0..150.0) gearboxTemp = temp
                                    }
                                }
                                setTargetEcu("18DAE5F1", "18DAF1E5")
                                query("22FD60", "62FD60")?.let {
                                    if (it.isNotEmpty()) rearTemp = it[0] - 30.0
                                }
                            }
                        }
                        4 -> {
                            // Temperatura płynu chłodzącego (0105) i oleju silnika DID (22100C: 16-bit, step 0.01, offset -50.0)
                            setTargetEcu("18DA15F1", "18DAF115")
                            query("0105", "4105")?.let { if (it.isNotEmpty()) coolant = it[0] - 40.0 }
                            if (cfg.showTemperatures) {
                                query("22100C", "62100C")?.let {
                                    if (it.size >= 2) {
                                        val raw = (it[0] * 256) + it[1]
                                        val temp = (raw * 0.01) - 50.0
                                        if (temp in -30.0..160.0) oilTemp = temp
                                    }
                                }
                            }
                        }
                        5 -> {
                            // Przetwornica DCDC 48V/12V (DCDC_48V_HR12 18DADDF1):
                            // 220106 = LV voltage measurement (step 0.5 V)
                            // 220104 = LV current measurement (step 1.0 A, offset -511.0 A)
                            setTargetEcu("18DADDF1", "18DAF1DD")
                            if (cfg.show12vBattery) {
                                query("220106", "620106")?.let {
                                    if (it.isNotEmpty()) {
                                        val v = it[0] * 0.5
                                        if (v in 10.0..16.5) v12 = v
                                    }
                                }
                            }
                            query("220104", "620104")?.let {
                                if (it.size >= 2) {
                                    val raw = (it[0] * 256) + it[1]
                                    val amps = (raw - 511.0).coerceAtLeast(0.0)
                                    if (amps in 0.0..220.0) {
                                        bat12vAmps = amps
                                        val effV12 = if (v12 > 10.0) v12 else 14.4
                                        bat12vWatts = effV12 * amps
                                    }
                                }
                            }
                        }
                        6 -> {
                            // Powód blokady EV ze sterownika EMS3253 (18DA15F1) - grupa 1:
                            // - 220311: StopStartInformationDisplay (komunikat przyczyny blokady na zegarach)
                            // - 22034F: Auto stop forbidden by HVAC request flag (1=blokada przez klimatyzację/nawiew)
                            // - 2202D0: PTC activation flag (1=aktywna grzałka elektryczna kabiny PTC)
                            setTargetEcu("18DA15F1", "18DAF115")
                            query("220311", "620311")?.let {
                                if (it.isNotEmpty()) ssDisplayCode = it[0]
                            }
                            query("22034F", "62034F")?.let {
                                if (it.isNotEmpty()) ssHvacForbid = (it[0] == 1)
                            }
                            query("2202D0", "6202D0")?.let {
                                if (it.isNotEmpty()) ssPtcActive = (it[0] == 1)
                            }
                        }
                        7 -> {
                            // Flagi bezpośredniej blokady EV w EMS3253 (18DA15F1) - grupa 2:
                            // - 2201BD: StopAuto forbidden by HV Network (1=blokada przez sieć/baterię 48V)
                            // - 220322: Auto stop forbidden by powertrain conditions (1=blokada przez silnik/GPF/temperaturę)
                            // - 220142: Electrical Energy Management open cluster forbidden (1=blokada przez zarządzanie energią 12V VEEM)
                            setTargetEcu("18DA15F1", "18DAF115")
                            query("2201BD", "6201BD")?.let {
                                if (it.isNotEmpty()) ssHvForbid = (it[0] == 1)
                            }
                            query("220322", "620322")?.let {
                                if (it.isNotEmpty()) ssPowertrainForbid = (it[0] == 1)
                            }
                            query("220142", "620142")?.let {
                                if (it.isNotEmpty()) ssVeemForbid = (it[0] == 1)
                            }
                        }
                        8 -> {
                            // Tryb paliwa / dotrysk (222172) oraz SoC akumulatora 12V z EMS3253 (220141: step 0.5, offset +69%)
                            setTargetEcu("18DA15F1", "18DAF115")
                            query("222172", "622172")?.let {
                                if (it.isNotEmpty()) {
                                    val mode = it[0]
                                    fuelBoost = (mode == 2 || mode == 5 || mode == 6 || mode == 7 || mode == 8)
                                }
                            }
                            if (cfg.show12vBattery) {
                                query("220141", "620141")?.let {
                                    if (it.isNotEmpty()) {
                                        val soc = (it[0] * 0.5 + 69.0).toInt().coerceIn(5, 100)
                                        ibsSoc = soc
                                    }
                                }
                            }
                        }
                        9 -> {
                            // Flagi bezpośredniej blokady EV / Sailing w EMS3253 (18DA15F1) - grupa 3:
                            // - 220254: Sailing Inhibition from Cooling system (1=blokada żeglowania/EV przez układ chłodzenia)
                            // - 220103: Sailing Permanent open clutch forbidden request (1=stała blokada rozłączenia sprzęgła do EV)
                            // - 220313: Stop auto forbidden by HFP (1=blokada przez układ hamulcowy / HFP)
                            setTargetEcu("18DA15F1", "18DAF115")
                            query("220254", "620254")?.let {
                                if (it.isNotEmpty()) ssCoolingForbid = (it[0] == 1)
                            }
                            query("220103", "620103")?.let {
                                if (it.isNotEmpty()) ssSailingClutchForbid = (it[0] == 1)
                            }
                            query("220313", "620313")?.let {
                                if (it.isNotEmpty()) ssHfpForbid = (it[0] == 1)
                            }
                        }
                        10 -> {
                            // Flagi i 4-bajtowe rejestry przyczyn blokady Stop & Start w EMS3253 (18DA15F1) - grupa 4:
                            // - 22030C: Forbid request of auto stop by ABS Regulation flag
                            // - 22011F: Stop and Start Gen2 system inhibition causes (4 bajty)
                            // - 22016E: Stop and Start AutoStop request inhibition causes (4 bajty)
                            setTargetEcu("18DA15F1", "18DAF115")
                            query("22030C", "62030C")?.let {
                                if (it.isNotEmpty()) ssAbsForbid = (it[0] == 1)
                            }
                            query("22011F", "62011F")
                            query("22016E", "62016E")
                        }
                    }
                }

                startStopReason = decodeStartStopInhibition(
                    rpm = rpm,
                    speedKmH = speed,
                    displayCode = ssDisplayCode,
                    hvacForbid = ssHvacForbid,
                    ptcActive = ssPtcActive,
                    veemForbid = ssVeemForbid,
                    hvForbid = ssHvForbid,
                    powertrainForbid = ssPowertrainForbid,
                    coolingForbid = ssCoolingForbid,
                    sailingClutchForbid = ssSailingClutchForbid,
                    hfpForbid = ssHfpForbid,
                    absForbid = ssAbsForbid
                )

                var hsgPowerKw = 0.0
                var hsgRegenKw = 0.0

                // =================================================================
                // BILANS ENERGII I WEKTOR 4x4 (PRZÓD ICE+HSG vs TYŁ e-AWD)
                // =================================================================
                val isStandstill = speed < 1.0
                val isParkOrNeutral = (gearStr == "P" || gearStr == "N")
                val isReverse = gearStr == "R"

                // Pomiary elektryczne z BMS 48V (Sunwoda):
                val safeHvVolt = if (hvVolt in 30.0..60.0) hvVolt else 48.0
                val isBatteryCharging = hvAmp > 0.5
                val isBatteryDischarging = hvAmp < -0.5
                val batChargeKw = if (isBatteryCharging) ((hvAmp * safeHvVolt) / 1000.0).coerceIn(0.0, 30.0) else 0.0
                val batDischargeKw = if (isBatteryDischarging) ((-hvAmp * safeHvVolt) / 1000.0).coerceIn(0.0, 30.0) else 0.0

                // Moc pobierana z szyny 48V przez przetwornicę DCDC 12V (220104 * 220106, sprawność ~92%):
                // Odjęcie jej od batDischargeKw zapobiega wliczaniu grzałki PTC / nawiewu 12V (1.0-1.4 kW) do mocy napędu kół!
                val dcdc48vKw = if (bat12vWatts > 5.0) ((bat12vWatts / 1000.0) / 0.92).coerceIn(0.0, 3.5) else 0.0
                val netTractionBatDischargeKw = (batDischargeKw - dcdc48vKw).coerceAtLeast(0.0)

                // Oś tylna e-AWD (eRAD):
                // Z logów ADB:
                // - Bieg 1 (E1, raw=1): przełożenie 21.97:1 -> 168.0 obr/min na każdy km/h prędkości
                // - Bieg 2 (E2, raw=2): przełożenie 11.60:1 -> 88.7 obr/min na każdy km/h prędkości
                // - Neutral (N, raw=0) lub zmiana biegu (raw=3): sprzęgło kłowe rozłączone (wirnik swobodny)
                val isEradMechanicallyEngaged = (eradGearRaw == 1 || eradGearRaw == 2)
                val kinematicFallbackRatio = when (eradGearRaw) {
                    1 -> 168.0
                    2 -> 88.7
                    else -> 0.0
                }
                val activeRearRpm = if (rearRpm != 0) {
                    Math.abs(rearRpm)
                } else if (speed > 1.0 && isEradMechanicallyEngaged) {
                    (speed * kinematicFallbackRatio).toInt()
                } else {
                    0
                }

                // Moc napędowa osi tylnej eRAD (bezpośredni pomiar z wału wirnika 22FD10 * 22FD11 w każdym cyklu):
                // Gdy skrzynia eRAD jest w Neutralu (0) lub w trakcie synchronizacji kłów (3),
                // moment na wirniku służy wyłącznie do synchronizacji obrotów wałka, a NIE napędza ani nie hamuje kół!
                val calcRearDriveKw = when {
                    !isEradMechanicallyEngaged -> 0.0
                    isReverse -> if (Math.abs(rearTorque) > 0.8 && activeRearRpm > 0) {
                        ((Math.abs(rearTorque) * activeRearRpm) / 9549.0).coerceIn(0.0, 30.0)
                    } else 0.0
                    rearTorque > 0.8 && activeRearRpm > 0 -> ((rearTorque * activeRearRpm) / 9549.0).coerceIn(0.0, 30.0)
                    else -> 0.0
                }

                // Rekuperacja mechaniczna z osi tylnej eRAD:
                // Wymaga zapiętego biegu (E1 lub E2) oraz momentu hamującego poniżej -4.0 Nm
                // (eliminuje szum swobodnego toczenia -0.6..-1.8 Nm oraz odciążanie kłów -3.4..-3.7 Nm przed zrzuceniem na N)
                val calcRearRegenKw = if (isEradMechanicallyEngaged && !isReverse && rearTorque < -4.0 && activeRearRpm > 0) {
                    ((-rearTorque * activeRearRpm) / 9549.0).coerceIn(0.0, 25.0)
                } else 0.0

                // Obliczenie mocy wspomagania (HSG Power) oraz rekuperacji (HSG Regen) przedniego BSG 48V:
                // 1. Wspomaganie momentu obrotowego (Torque Assist z baterii 48V):
                //    Moc elektryczna pobierana przez tylny falownik eRAD ~ calcRearDriveKw / 0.90.
                //    Wszelka pozostała moc trakcyjna pobierana z baterii 48V przy pracującym silniku (rpm > 250)
                //    trafia bezpośrednio paskiem na wał korbowy jako boost przedniego rozrusznika-alternatora 48V (BSG).
                val rearElecDriveKw = if (calcRearDriveKw > 0.1) (calcRearDriveKw / 0.90) else 0.0
                val hsgElecAssistKw = (netTractionBatDischargeKw - rearElecDriveKw).coerceAtLeast(0.0)
                hsgPowerKw = if (rpm > 250 && hsgElecAssistKw > 0.15) {
                    (hsgElecAssistKw * 0.90).coerceIn(0.0, 12.0)
                } else {
                    0.0
                }

                // 2. Rekuperacja przedniego BSG 48V podczas zwalniania:
                //    Całkowita moc elektryczna generowana na szynę 48V = ładowanie baterii + zasilanie przetwornicy 12V.
                //    Po odjęciu rekuperacji tylnej osi eRAD (calcRearRegenKw * 0.90), reszta to odzysk z przedniego BSG.
                val totalGenElecKw = batChargeKw + dcdc48vKw
                val rearRegenElecKw = calcRearRegenKw * 0.90
                val hsgGenElecKw = (totalGenElecKw - rearRegenElecKw).coerceAtLeast(0.0)
                hsgRegenKw = if (rpm > 250 && hsgGenElecKw > 0.2) {
                    (hsgGenElecKw / 0.90).coerceIn(0.0, 15.0)
                } else {
                    0.0
                }

                // Blokada rekuperacji podczas przyspieszania/jazdy pod obciążeniem:
                val isAcceleratingOrCruising = (
                    (rpm > 600 && iceTorque > 6.0) ||
                    (isEradMechanicallyEngaged && rearTorque > 1.2 && netTractionBatDischargeKw > 0.2)
                )

                val rawKineticRegen = !isStandstill && !isParkOrNeutral && !isReverse && speed > 3.0 && !isAcceleratingOrCruising && (
                    calcRearRegenKw > 0.8 ||
                    (rpm == 0 && batChargeKw > 1.0) ||
                    (rpm > 500 && iceTorque <= 3.0 && batChargeKw > 1.0) ||
                    (batChargeKw > 1.5 && (calcRearRegenKw > 0.4 || hsgRegenKw > 0.8))
                )
                regenConfirmCount = if (rawKineticRegen) (regenConfirmCount + 1).coerceAtMost(10) else 0
                val isKineticRegen = regenConfirmCount >= 2

                val elecFromBatPureEv = (netTractionBatDischargeKw * 0.90).coerceIn(0.0, 25.0)
                val isPureEv = (rpm == 0 && speed > 1.0 && !isReverse && !isParkOrNeutral && !isKineticRegen &&
                    (calcRearDriveKw > 0.15 || (elecFromBatPureEv > 0.15 && rearTorque > 0.3)))
                val isCoasting = (rpm == 0 && speed > 2.0 && !isReverse && !isParkOrNeutral && !isKineticRegen && !isPureEv)

                // Obliczenie mocy poszczególnych silników (wzajemnie wykluczające się ze stanem rekuperacji i postoju):
                var rawIceShaftKw = 0.0
                var icePowerKw = 0.0
                var rearPowerKw = 0.0
                var rearFromBatKw = 0.0
                var totalOutput = 0.0
                var totalRegenKw = 0.0
                var rearRegenKw = 0.0

                when {
                    // 1. Postój w miejscu (speed < 1.0) na D, P, N lub R: moc napędowa na koła wynosi ZAWSZE 0.0 kW!
                    isStandstill || isParkOrNeutral -> {
                        icePowerKw = 0.0
                        rearPowerKw = 0.0
                        rearFromBatKw = 0.0
                        totalOutput = 0.0
                        totalRegenKw = 0.0
                        rearRegenKw = 0.0
                        hsgRegenKw = 0.0
                        hsgPowerKw = 0.0
                    }

                    // 2. Rekuperacja kinetyczna podczas zwalniania/hamowania (pasek w lewo)
                    isKineticRegen -> {
                        val totalR = Math.max(calcRearRegenKw + hsgRegenKw, batChargeKw).coerceIn(0.0, 25.0)
                        val rearR = calcRearRegenKw.coerceAtMost(totalR)
                        val frontR = (totalR - rearR).coerceAtLeast(0.0)
                        icePowerKw = 0.0
                        rearPowerKw = 0.0
                        rearFromBatKw = 0.0
                        totalOutput = 0.0
                        totalRegenKw = totalR
                        rearRegenKw = rearR
                        hsgRegenKw = frontR
                        hsgPowerKw = 0.0
                    }

                    // 3. Swobodne żeglowanie z wyłączonym silnikiem spalinowym (0.0 kW)
                    isCoasting -> {
                        icePowerKw = 0.0
                        rearPowerKw = 0.0
                        rearFromBatKw = 0.0
                        totalOutput = 0.0
                        totalRegenKw = 0.0
                        rearRegenKw = 0.0
                        hsgRegenKw = 0.0
                        hsgPowerKw = 0.0
                    }

                    // 4. Jazda czysto elektryczna pod obciążeniem (Pure EV - silnik spalinowy wyłączony, rpm == 0)
                    isPureEv -> {
                        val eDrive = Math.max(elecFromBatPureEv, calcRearDriveKw)
                        icePowerKw = 0.0
                        rearPowerKw = eDrive
                        rearFromBatKw = eDrive
                        totalOutput = eDrive
                        totalRegenKw = 0.0
                        rearRegenKw = 0.0
                        hsgRegenKw = 0.0
                        hsgPowerKw = 0.0
                    }

                    // 5. Bieg wsteczny (R)
                    isReverse -> {
                        val elecFromBat = (netTractionBatDischargeKw * 0.90).coerceIn(0.0, 20.0)
                        val rPower = if (elecFromBat > 0.15) Math.max(elecFromBat, calcRearDriveKw) else calcRearDriveKw
                        icePowerKw = 0.0
                        rearPowerKw = rPower
                        rearFromBatKw = if (elecFromBat > 0.15) rPower else 0.0
                        totalOutput = rPower
                        totalRegenKw = 0.0
                        rearRegenKw = 0.0
                        hsgRegenKw = 0.0
                        hsgPowerKw = 0.0
                    }

                    // 6. Normalna jazda hybrydowa w przód (silnik spalinowy włączony)
                    else -> {
                        val shaftIceKw = if (rpm >= 400 && iceTorqueKnown && iceTorque > 0.0) {
                            ((iceTorque * rpm) / 9549.0).coerceIn(0.0, 100.0)
                        } else 0.0
                        rawIceShaftKw = shaftIceKw

                        // Moc tylnej osi eRAD (22FD11 * 22FD10):
                        // - Wymaga fizycznie zapiętego biegu tylnej przekładni (E1 lub E2) oraz dodatniego momentu rearTorque > 0.8 Nm.
                        // - Rzeczywisty moment z wału eRAD (calcRearDriveKw) stanowi fizyczną moc tylnych kół.
                        val rearKw = when {
                            !isEradMechanicallyEngaged -> 0.0
                            rearTorque > 0.8 -> calcRearDriveKw
                            else -> 0.0
                        }

                        rearFromBatKw = if (rearKw > 0.0 && netTractionBatDischargeKw > 0.15) {
                            rearKw.coerceAtMost(netTractionBatDischargeKw * 0.90)
                        } else 0.0

                        // Część mocy tylnego silnika pochodząca bezpośrednio z przedniego generatora BSG (odebrana paskiem z wału 1.2 TCe):
                        // Odejmujemy ją od mocy spalinowej na przednich kołach, aby nie liczyć podwójnie tych samych kilowatów w Total Output
                        // i pokazać prawdziwy rozdział mocy PRZÓD / TYŁ!
                        val genToRearKw = (rearKw - rearFromBatKw).coerceAtLeast(0.0)

                        icePowerKw = (shaftIceKw - genToRearKw).coerceAtLeast(0.0)
                        rearPowerKw = rearKw
                        totalOutput = icePowerKw + rearPowerKw + hsgPowerKw
                        totalRegenKw = 0.0
                        rearRegenKw = 0.0
                        hsgRegenKw = 0.0
                    }
                }

                val frontPower = (icePowerKw + hsgPowerKw).coerceAtLeast(0.0)
                val rearPower = rearPowerKw.coerceAtLeast(0.0)

                val dynamicRearTorque = when {
                    speed < 1.0 && activeRearRpm <= 15 -> 0.0
                    !isEradMechanicallyEngaged -> 0.0
                    Math.abs(rearTorque) > 0.5 -> rearTorque
                    rearPowerKw > 0 && activeRearRpm > 0 -> (rearPowerKw * 9549.0 / activeRearRpm).coerceIn(0.0, 180.0)
                    else -> 0.0
                }

                val batteryPowerKw = if (totalRegenKw > 0.05) -totalRegenKw else (rearPower + hsgPowerKw)

                // Wektor podziału 4x4 (Przód vs Tył) obliczany na podstawie bilansu mocy napędowych:
                // Gdy tylna oś nie napędza (rearPower <= 0.15 kW) a jedziemy na spalinowym -> 100% PRZÓD / 0% TYŁ.
                // Gdy pracują obie osie -> dokładny udział procentowy przód/tył (np. 50/50, 82/18) z szybkim wygładzaniem EMA.
                val totalShare = frontPower + rearPower
                val rawFrontPct = when {
                    isStandstill || isParkOrNeutral -> 50.0
                    isPureEv || isReverse -> 0.0 // 100% tył (0% przód)
                    isKineticRegen -> {
                        val totReg = hsgRegenKw + rearRegenKw
                        if (totReg > 0.2) ((hsgRegenKw / totReg) * 100.0).coerceIn(0.0, 100.0) else 0.0
                    }
                    totalShare > 0.3 -> {
                        if (rearPower <= 0.15) 100.0
                        else if (frontPower <= 0.15) 0.0
                        else ((frontPower / totalShare) * 100.0).coerceIn(0.0, 100.0)
                    }
                    else -> 50.0
                }
                smoothedFrontPct = (smoothedFrontPct * 0.55) + (rawFrontPct * 0.45)
                val frontPct = Math.round(smoothedFrontPct).toInt().coerceIn(0, 100)
                val rearPct = 100 - frontPct

                // Rzeczywiste parametry instalacji 12V z przetwornicy DC-DC (220106 / 220104) oraz SoC z EMS3253 (220141):
                val bat12vLoadWatts = bat12vWatts
                val bat12vNetChargeWatts = 0.0
                val final12vSoc = if (ibsSoc != null && ibsSoc!! in 5..100) ibsSoc!! else t.bat12vSocPercent

                // Wyznaczenie prądu baterii do całkowania (wliczamy wyłącznie prąd faktycznie pobrany z baterii 48V,
                // pomijając prąd wytwarzany na bieżąco przez przedni generator BSG w trybie 4x4 E-AWD):
                val activeHvAmp = if (Math.abs(hvAmp) >= 0.25) {
                    hvAmp
                } else {
                    val safeV = if (hvVolt > 35.0) hvVolt else 48.0
                    if (totalRegenKw > 0.05) {
                        (totalRegenKw * 1000.0) / safeV
                    } else if (rearFromBatKw > 0.02 || hsgPowerKw > 0.02) {
                        -((rearFromBatKw + hsgPowerKw) * 1000.0) / safeV
                    } else {
                        0.0
                    }
                }

                val fuelShaftKw = if (rawIceShaftKw > 0.0) rawIceShaftKw else icePowerKw
                val fuelRateLh = if (fuelShaftKw > 0) (fuelShaftKw * 0.28).coerceAtLeast(0.8) else 0.0
                tripTracker.update(
                    voltage = if (hvVolt > 35.0) hvVolt else 48.0,
                    current = activeHvAmp,
                    speedKmH = speed,
                    iceRpm = rpm,
                    fuelRateLh = fuelRateLh,
                    isRegenActive = isKineticRegen,
                    regenPowerKw = totalRegenKw,
                    rearMotorPowerKw = rearFromBatKw
                )

                val detectedMode = when {
                    isStandstill -> {
                        if (rpm > 200 && batChargeKw > 0.5) "ŁADOWANIE 48V"
                        else if (rpm > 200 || isParkOrNeutral) "POSTÓJ"
                        else "EV / READY"
                    }
                    isParkOrNeutral -> {
                        if (rpm > 200 && batChargeKw > 0.5) "ŁADOWANIE 48V"
                        else "STANDBY"
                    }
                    isKineticRegen -> "REKUPERACJA"
                    isCoasting -> "ŻEGLOWANIE"
                    isPureEv -> "PURE EV"
                    isReverse -> "COFANIE (e-AWD)"
                    rearPowerKw > 0.3 && rearFromBatKw <= 0.2 -> "4x4 E-AWD"
                    icePowerKw > 8.0 && rearPowerKw > 2.5 -> "E-AWD BOOST"
                    rearPowerKw > 0.5 -> "HYBRYDA e-AWD"
                    icePowerKw > 0.5 || rpm > 300 -> "HYBRYDA AUTO"
                    else -> "EV / READY"
                }

                t = t.copy(
                    speedKmH = speed,
                    iceRpm = rpm,
                    currentGear = gearStr,
                    eGear = if (eGearStr.isNotBlank() && eGearStr != "-") eGearStr else decodeEradGear(eradGearRaw, isReverse, eGearStr),
                    icePowerKw = icePowerKw,
                    rearMotorPowerKw = rearPowerKw,
                    hsgPowerKw = hsgPowerKw,
                    totalOutputPowerKw = totalOutput,
                    totalRegenPowerKw = totalRegenKw,
                    rearRegenPowerKw = rearRegenKw,
                    iceTorqueNm = iceTorque,
                    rearMotorTorqueNm = dynamicRearTorque,
                    rearMotorRpm = activeRearRpm,
                    hsgVoltage48v = hsgVolt48,
                    hsgLoadPercent = hsgLoad,
                    hsgPowerWatts = hsgPowerKw * 1000.0,
                    hvBatterySocPercent = hvSoc,
                    hvBatteryVoltage = hvVolt,
                    hvBatteryCurrent = hvAmp,
                    hvBatteryTempC = hvTemp,
                    bat12vVoltage = v12,
                    bat12vCurrent = bat12vAmps,
                    bat12vSocPercent = final12vSoc,
                    bat12vPowerWatts = bat12vWatts,
                    bat12vLoadWatts = bat12vLoadWatts,
                    bat12vNetChargeWatts = bat12vNetChargeWatts,
                    coolantTempC = coolant,
                    oilTempC = oilTemp,
                    gearboxTempC = gearboxTemp,
                    rearMotorTempC = rearTemp,
                    rearInverterTempC = inverterTemp,
                    fuelInjectionBoostActive = fuelBoost,
                    startStopInhibitReason = startStopReason,
                    frontTorquePercent = frontPct,
                    rearTorquePercent = rearPct,
                    hybridMode = detectedMode,
                    tripDistanceKm = tripTracker.getDistanceKm(),
                    tripEvDistancePercent = tripTracker.getEvDistancePercent(),
                    tripEvEnergySharePercent = tripTracker.getEvEnergySharePercent(),
                    tripRegenKWh = tripTracker.getRegenKWh(),
                    tripUsedElecKWh = tripTracker.getUsedElecKWh(),
                    tripFuelSavedLiters = tripTracker.getFuelSavedLiters()
                )
                _telemetryState.value = t
                val loopDelay = if (stpxSupported && cfg.useVlinkerFastProtocol) 2L else 15L
                delay(loopDelay)
            }
        } catch (e: Exception) {
            closeSocket()
            _connectionStatus.value = ObdConnectionStatus.ERROR
        }
    }

    /**
     * Dekoduje wszystkie aktywne przyczyny blokady EV / Stop & Start wyłącznie z oficjalnych PID-ów EMS3253 (18DA15F1):
     * - 220311 (StopStartInformationDisplay)
     * - 22034F (Auto stop forbidden by HVAC request flag)
     * - 2202D0 (PTC activation flag - grzałka elektryczna kabiny)
     * - 220142 (Electrical Energy Management open cluster forbidden - zarządzanie energią 12V VEEM)
     * - 2201BD (StopAuto forbidden by HV Network - sieć/bateria 48V)
     * - 220322 (Auto stop forbidden by powertrain conditions - silnik/napęd)
     * - 220254 (Sailing Inhibition from Cooling system - układ chłodzenia/termika)
     * - 220103 (Sailing Permanent open clutch forbidden request - blokada rozłączenia sprzęgła do EV)
     * - 220313 / 22030C (Stop auto forbidden by HFP / ABS Regulation flag)
     * Zwraca pusty tekst "", gdy brak blokady lub gdy silnik spalinowy już zgasł (rpm == 0).
     * Gdy występuje więcej niż jeden powód jednocześnie, zwraca je rozdzielone znakiem nowej linii "\n".
     */
    private fun decodeStartStopInhibition(
        rpm: Int,
        speedKmH: Double,
        displayCode: Int,
        hvacForbid: Boolean,
        ptcActive: Boolean,
        veemForbid: Boolean,
        hvForbid: Boolean,
        powertrainForbid: Boolean,
        coolingForbid: Boolean,
        sailingClutchForbid: Boolean,
        hfpForbid: Boolean,
        absForbid: Boolean
    ): String {
        // Jeśli silnik spalinowy jest wyłączony (auto już jest w Stop & Start lub Pure EV), brak blokady:
        if (rpm < 300) return ""

        val reasons = LinkedHashSet<String>()

        // 1. Sprawdzenie wyłączenia przycisku lub błędu systemu (220311)
        if (displayCode == 36) reasons.add("BLOKADA EV: WYŁĄCZONY")
        if (displayCode == 1 || displayCode == 17 || displayCode == 32) reasons.add("BLOKADA EV: USTERKA")

        // 2. Dekodowanie oficjalnego kodu przyczyny z DID 220311 (StopStartInformationDisplay w EMS3253):
        when (displayCode) {
            2, 18 -> reasons.add("BLOKADA EV: POCHYLENIE")
            3, 19 -> if (speedKmH < 2.0) reasons.add("BLOKADA EV: RUCH AUTA")
            4, 20 -> reasons.add("BLOKADA EV: ENERGIA 12V/48V")
            5, 21 -> reasons.add("BLOKADA EV: SILNIK / GPF")
            6, 22, 35 -> reasons.add("BLOKADA EV: HAMULEC")
            7, 23, 42, 47 -> reasons.add("BLOKADA EV: MASKA")
            8, 24, 45, 46 -> reasons.add("BLOKADA EV: DRZWI")
            9, 25, 43 -> reasons.add("BLOKADA EV: PASY")
            10, 26 -> reasons.add("BLOKADA EV: WSTECZNY")
            11, 27 -> reasons.add("BLOKADA EV: KLIMA / NAWIEW")
            12, 28 -> reasons.add("BLOKADA EV: KIEROWNICA")
            13, 29 -> reasons.add("BLOKADA EV: KARTA")
            14, 30 -> reasons.add("BLOKADA EV: SYSTEM")
            34 -> reasons.add("BLOKADA EV: SPRZĘGŁO")
        }

        // 3. Bezpośrednie oficjalne flagi blokady EV / Sailing / S&S ze sterownika EMS3253 (18DA15F1):
        if (hvacForbid) reasons.add("BLOKADA EV: KLIMA / NAWIEW")
        if (ptcActive) reasons.add("BLOKADA EV: GRZAŁKA PTC")
        if (veemForbid) reasons.add("BLOKADA EV: ENERGIA 12V (VEEM)")
        if (hvForbid) reasons.add("BLOKADA EV: BATERIA 48V")
        if (powertrainForbid) reasons.add("BLOKADA EV: SILNIK / NAPĘD")
        if (coolingForbid) reasons.add("BLOKADA EV: CHŁODZENIE / TERMIKA")
        if (sailingClutchForbid) reasons.add("BLOKADA EV: SPRZĘGŁO / SAILING")
        if (hfpForbid || absForbid) reasons.add("BLOKADA EV: HAMULEC / ABS")

        return reasons.joinToString("\n")
    }

    /**
     * Krzywa maksymalnego momentu obrotowego silnika 1.2 TCe 48V (HR12 / EMS3253) w funkcji obrotów
     */
    private fun maxEngineTorqueAtRpm(rpm: Int): Double = when {
        rpm < 900 -> 60.0
        rpm < 1500 -> 60.0 + ((rpm - 900) / 600.0) * 110.0 // 60 -> 170 Nm (narastanie turbo)
        rpm < 1750 -> 170.0 + ((rpm - 1500) / 250.0) * 60.0 // 170 -> 230 Nm (pełny boost)
        rpm <= 4000 -> 230.0
        else -> (230.0 - ((rpm - 4000) / 2000.0) * 50.0).coerceAtLeast(150.0)
    }

    /**
     * Dekoduje biegi 6-biegowej skrzyni dwusprzęgłowej DW23 (DW23_C1AHSevo) z parametru 222135:
     * - Bajt A = tg1 (wałek 1 nieparzysty):
     *   0x01=Gear 1, 0x03=Gear 3, 0x05=Gear 5, 0x07=Gear 7, 0x09=Gear R,
     *   0x0C=Park, 0x0D=Gear N13, 0x0E=Gear N35, 0x0F=Gear N57
     * - Bajt B = tg2 (wałek 2 parzysty):
     *   0x02=Gear 2, 0x04=Gear 4, 0x06=Gear 6,
     *   0x10=Gear NR2, 0x11=Gear N24, 0x12=Gear N46, 0x14=Park
     *
     * Gdy zapięty jest tylko jeden wałek (drugi jest w neutralu N13/N35/N57 lub NR2/N24/N46),
     * zwracamy ten wałek bezpośrednio.
     * Gdy zapięte są OBA wałki jednocześnie (preselekcja kolejnego biegu, np. 05 04 podczas jazdy na D4 lub D5),
     * porównujemy rzeczywiste przełożenie (rpm / speedKmH) wyłącznie pomiędzy dwoma zapiętymi biegami A i B!
     */
    private fun decodeGearbox(a: Int, b: Int, speedKmH: Double, rpm: Int, prevGear: String): String? {
        if (a == 0x0C || b == 0x14) return "P"
        if (a == 0x09) return "R"
        if (a == 0x0B && b == 0x0B) return "N"

        val oddEngaged = a in listOf(1, 3, 5, 7)
        val evenEngaged = b in listOf(2, 4, 6)

        // Neutral na obu wałkach (np. 0D 10 = N13 + NR2)
        if (!oddEngaged && !evenEngaged) {
            return if (speedKmH < 1.0 && prevGear == "P") "P" else "N"
        }

        // Tylko wałek nieparzysty ma zapięty bieg (wałek parzysty w neutralu 0x10..0x12)
        if (oddEngaged && !evenEngaged) return "D$a"

        // Tylko wałek parzysty ma zapięty bieg (wałek nieparzysty w neutralu 0x0D..0x0F)
        if (evenEngaged && !oddEngaged) return "D$b"

        // OBA wałki mają zapięty bieg jednocześnie (preselekcja DCT, np. a=3 b=2 lub a=5 b=4):
        // Rozstrzygamy pomiędzy kandydatem 'a' oraz kandydatem 'b' na podstawie zmierzonego przełożenia skrzyni DW23:
        // D1=152.0, D2=100.0, D3=65.8, D4=44.3, D5=29.8, D6=20.3 obr/min na km/h
        if (speedKmH > 3.0 && rpm > 450) {
            val ratio = rpm / speedKmH
            val ratioA = dw23GearRatio(a)
            val ratioB = dw23GearRatio(b)
            return if (Math.abs(ratio - ratioA) <= Math.abs(ratio - ratioB)) "D$a" else "D$b"
        }

        // Gdy stoimy w miejscu lub jedziemy w Pure EV (rpm == 0) z preselekcją (np. 01 02):
        if (speedKmH <= 3.0 && (a == 1 || b == 2)) return "D1"
        if (prevGear == "D$a" || prevGear == "D$b") return prevGear
        return if (speedKmH < 15.0) "D${Math.min(a, b)}" else "D$b"
    }

    private fun dw23GearRatio(gear: Int): Double = when (gear) {
        1 -> 152.0
        2 -> 100.0
        3 -> 65.8
        4 -> 44.3
        5 -> 29.8
        6 -> 20.3
        else -> 30.0
    }

    /**
     * Dekoduje bieg redukcji tylnej osi eRAD z parametru 22FD05 (INV_RE_48V_ERAD_RSA06):
     * 0 = Neutral -> "N"
     * 1 = Gear 1 engaged (krótki bieg terenowy 21.97:1) -> "E1" / "R1"
     * 2 = Gear 2 engaged (długi bieg szosowy 11.60:1) -> "E2" / "R2"
     * 3 = Shifting in progress -> zachowaj ostatni bieg, aby nie migać napisem pośrednim
     */
    private fun decodeEradGear(raw: Int, isReverse: Boolean, prevEGear: String): String = when (raw) {
        0 -> "N"
        1 -> if (isReverse) "R1" else "E1"
        2 -> if (isReverse) "R2" else "E2"
        3 -> if (prevEGear in listOf("R2", "R1", "N", "E1", "E2")) prevEGear else (if (isReverse) "R2" else "E2")
        else -> "N"
    }

    private var stpxSupported = false
    private var currentTargetEcu = ""
    private var currentRespEcu = ""
    private var lastElmConfiguredEcu = ""

    private fun setTargetEcu(target29: String, resp29: String) {
        val fastMode = stpxSupported && ConfigManager.configState.value.useVlinkerFastProtocol
        currentTargetEcu = target29
        currentRespEcu = resp29
        if (fastMode) {
            return
        }
        if (lastElmConfiguredEcu == target29) return
        lastElmConfiguredEcu = target29
        val shortTarget = if (target29.startsWith("18DA")) target29.substring(2) else target29
        sendCommand("ATFCSH$target29", timeoutMs = 80)
        sendCommand("ATSH$shortTarget", timeoutMs = 80)
        sendCommand("ATCRA$resp29", timeoutMs = 80)
    }

    /**
     * Wysyła PID i zwraca bajty danych po nagłówku odpowiedzi (np. "410C" -> [A, B]).
     * Zwraca null przy NO DATA, błędzie negatywnym (7F) lub braku odpowiedzi.
     */
    private val pidFailures = HashMap<String, Int>()
    private var pidSkipTick = 0

    private fun query(cmd: String, expectedPrefix: String): IntArray? {
        val key = "$currentTargetEcu:$cmd"
        val failed = pidFailures[key] ?: 0
        if (failed >= 5) {
            pidSkipTick++
            if (pidSkipTick % 30 != 0) return null
        }

        val useFast = stpxSupported && ConfigManager.configState.value.useVlinkerFastProtocol && currentTargetEcu.isNotEmpty()
        val rawCmd = if (useFast) {
            "STPX h:$currentTargetEcu, d:$cmd, t:85, r:1"
        } else {
            cmd
        }

        // Normalna odpowiedź kończy się natychmiast po odebraniu znaku '>' (21-26 ms).
        // Wyższy timeoutMs (260 ms) chroni przed przedwczesnym przerwaniem odczytu podczas chwilowego lagu Bluetooth w telefonie
        // i zapobiega przesunięciu odpowiedzi do następnego zapytania (kaskada STOPPED).
        val resp = sendCommand(rawCmd, timeoutMs = if (useFast) 260 else 300).replace(" ", "").uppercase()

        // Automatyczny fallback, jeśli adapter nie rozpoznaje STPX
        if (useFast && resp.contains("?")) {
            ObdLogger.log("Komenda STPX nieobsługiwana ($rawCmd) -> '$resp'. Przełączanie w tryb ELM327.")
            stpxSupported = false
            lastElmConfiguredEcu = ""
            setTargetEcu(currentTargetEcu, currentRespEcu)
            return query(cmd, expectedPrefix)
        }

        val idx = resp.indexOf(expectedPrefix)
        if (idx < 0) {
            pidFailures[key] = failed + 1
            if (ObdLogger.isEnabled() && failed < 5) {
                ObdLogger.logCommand(cmd, resp, "BRAK '$expectedPrefix' na $currentTargetEcu")
            }
            return null
        }
        pidFailures[key] = 0
        val hex = resp.substring(idx + expectedPrefix.length).takeWhile { it.isLetterOrDigit() }
        if (hex.length < 2) {
            if (ObdLogger.isEnabled()) {
                ObdLogger.logCommand(cmd, resp, "ZA KRÓTKA ODPOWIEDŹ")
            }
            return null
        }
        return try {
            val bytes = IntArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16) }
            if (ObdLogger.isEnabled()) {
                ObdLogger.logCommand(cmd, resp, "OK [${bytes.joinToString(",") { it.toString(16) }}]")
            }
            bytes
        } catch (_: NumberFormatException) {
            null
        }
    }

    /** Wysyła komendę i czyta odpowiedź blokowo z bufora aż do znaku zachęty '>' (lub timeoutu). */
    private val readBuffer = ByteArray(128)

    private fun sendCommand(cmd: String, timeoutMs: Long = 200): String {
        val out = outputStream ?: throw IllegalStateException("Brak połączenia")
        val input = inputStream ?: throw IllegalStateException("Brak połączenia")

        // Opróżnij bufor wejściowy przed wysłaniem nowego polecenia
        while (input.available() > 0) {
            val avail = input.available().coerceAtMost(readBuffer.size)
            if (input.read(readBuffer, 0, avail) <= 0) break
        }

        out.write("$cmd\r".toByteArray())
        out.flush()

        val sb = StringBuilder(32)
        val deadline = System.currentTimeMillis() + timeoutMs
        var done = false
        while (!done && System.currentTimeMillis() < deadline) {
            val avail = input.available()
            if (avail > 0) {
                val count = input.read(readBuffer, 0, avail.coerceAtMost(readBuffer.size))
                if (count < 0) break
                for (i in 0 until count) {
                    val ch = readBuffer[i].toInt().toChar()
                    if (ch == '>') {
                        done = true
                        break
                    }
                    sb.append(ch)
                }
            } else {
                Thread.sleep(1)
            }
        }
        // Jeśli z powodu przycięcia Bluetooth znak '>' jeszcze nie dotarł, doczytaj strumień przez maks. 80 ms,
        // aby spóźniona odpowiedź nie przesunęła kolejki następnych komend:
        if (!done) {
            val drainDeadline = System.currentTimeMillis() + 80
            while (!done && System.currentTimeMillis() < drainDeadline) {
                val avail = input.available()
                if (avail > 0) {
                    val count = input.read(readBuffer, 0, avail.coerceAtMost(readBuffer.size))
                    if (count < 0) break
                    for (i in 0 until count) {
                        val ch = readBuffer[i].toInt().toChar()
                        if (ch == '>') {
                            done = true
                            break
                        }
                        sb.append(ch)
                    }
                } else {
                    Thread.sleep(1)
                }
            }
        }
        val clean = sb.toString().replace("\r", " ").replace("\n", " ").trim()
        if (ObdLogger.isEnabled() && (cmd.startsWith("AT") || cmd.startsWith("STI") || cmd.startsWith("10"))) {
            ObdLogger.log("CMD $cmd -> '$clean'")
        }
        return clean
    }
}
