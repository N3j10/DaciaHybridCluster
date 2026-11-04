package com.dacia.hybridcluster.car

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.model.DateTimeWithZone
import androidx.car.app.model.Distance
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
import androidx.car.app.navigation.model.Destination
import androidx.car.app.navigation.model.Step
import androidx.car.app.navigation.model.TravelEstimate
import androidx.car.app.navigation.model.Trip
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.dacia.hybridcluster.model.ConfigManager
import com.dacia.hybridcluster.obd.ObdManager
import com.dacia.hybridcluster.weather.WeatherManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.TimeZone

/**
 * Sesja ekranu środkowego Infotainment:
 * Obsługuje renderowanie telemetrii oraz menu ustawień na ekranie głównym Android Auto,
 * a także utrzymuje trasę-widmo dla sesji klastra (ClusterCarSession).
 */
class HybridCarSession : Session() {

    private val sessionScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var navManager: NavigationManager? = null
    val infotainmentRenderer = InfotainmentSurfaceRenderer()

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                try { navManager?.navigationEnded() } catch (_: Exception) {}
                infotainmentRenderer.stop()
                sessionScope.cancel()
            }
        })
    }

    override fun onCreateScreen(intent: Intent): Screen {
        com.dacia.hybridcluster.model.DaciaFonts.init(carContext)
        // 1. Uruchomienie odpytywania danych OBD i pogody
        ObdManager.start()
        WeatherManager.startPolling(sessionScope)

        // 2. KLUCZOWE: zegary dostają obraz tylko przy AKTYWNEJ trasie (jak w Waze).
        val nav = carContext.getCarService(NavigationManager::class.java)
        navManager = nav
        nav.setNavigationManagerCallback(object : NavigationManagerCallback {
            override fun onStopNavigation() {
                startFakeNavigationTrip(nav)
            }
        })
        startFakeNavigationTrip(nav)

        // 3. Przywrócenie ostatnio zapamiętanego stanu (Zegary lub Menu)
        val config = ConfigManager.configState.value
        return if (config.lastInfotainmentScreen == "TELEMETRY") {
            InfotainmentTelemetryScreen(carContext, this)
        } else {
            InfotainmentMenuScreen(carContext, this)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        android.util.Log.i("HybridCarSession", "onNewIntent received from host: $intent")
    }

    /**
     * Wysyła do hosta sygnał trwającej nawigacji z celem i manewrem,
     * co powoduje utworzenie sesji klastra i przekazanie jej Surface zegarów.
     * Wykonywane asynchronicznie z ponawianiem (aż host zakończy wiązanie IPC).
     */
    private fun startFakeNavigationTrip(navManager: NavigationManager) {
        sessionScope.launch {
            for (attempt in 1..25) {
                try {
                    navManager.navigationStarted()

                    val destination = Destination.Builder()
                        .setName("Tryb Telemetrii e-AWD")
                        .setAddress("Kokpit Hybrydowy")
                        .build()

                    val travelEstimate = TravelEstimate.Builder(
                        Distance.create(999.0, Distance.UNIT_KILOMETERS),
                        DateTimeWithZone.create(
                            System.currentTimeMillis() + 86400000L,
                            TimeZone.getDefault()
                        )
                    ).build()

                    val step = Step.Builder()
                        .setCue("Aktywny monitor hybrydy")
                        .setRoad("Dacia Hybrid-G 150 e-AWD")
                        .build()

                    val trip = Trip.Builder()
                        .addDestination(destination, travelEstimate)
                        .addStep(step, travelEstimate)
                        .setCurrentRoad("Dacia Hybrid-G 150 e-AWD")
                        .build()

                    navManager.updateTrip(trip)
                    break
                } catch (_: Exception) {
                    kotlinx.coroutines.delay(250)
                }
            }
        }
    }
}
