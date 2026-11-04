package com.dacia.hybridcluster.car

import android.content.ComponentName
import android.content.Intent
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.*
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.lifecycle.lifecycleScope
import com.dacia.hybridcluster.model.AppLanguage
import com.dacia.hybridcluster.model.ConfigManager
import com.dacia.hybridcluster.model.StringsManager
import com.dacia.hybridcluster.obd.ObdConnectionStatus
import com.dacia.hybridcluster.obd.ObdManager
import kotlinx.coroutines.launch

/**
 * Ekran wyświetlania parametrów (Zegary i kafelki) na ekranie środkowym Infotainment.
 */
class InfotainmentTelemetryScreen(
    carContext: CarContext,
    private val session: HybridCarSession
) : Screen(carContext) {

    init {
        // Podpięcie renderera dla wyświetlacza środkowego
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(session.infotainmentRenderer)

        session.infotainmentRenderer.onBackClicked = {
            goToMenu()
        }

        session.infotainmentRenderer.onSurfaceTapped = {
            requestFullscreen()
        }
    }

    private fun requestFullscreen() {
        android.util.Log.i("TelemetryScreen", "requestFullscreen triggered from surface tap")
        val targets = listOf(
            Intent().apply { component = ComponentName(carContext, HybridCarAppService::class.java) },
            Intent(Intent.ACTION_MAIN).apply { component = ComponentName(carContext, HybridCarAppService::class.java) },
            Intent("androidx.car.app.CarAppService").apply {
                component = ComponentName(carContext, HybridCarAppService::class.java)
                `package` = carContext.packageName
            }
        )
        for (intent in targets) {
            try {
                carContext.startCarApp(intent)
                android.util.Log.i("TelemetryScreen", "Successfully dispatched startCarApp: $intent")
                break
            } catch (e: Exception) {
                android.util.Log.w("TelemetryScreen", "startCarApp attempt failed: $intent -> ${e.message}")
            }
        }
    }

    private fun goToMenu() {
        ConfigManager.updateConfig(ConfigManager.configState.value.copy(lastInfotainmentScreen = "MENU"))
        if (screenManager.stackSize > 1) {
            screenManager.pop()
        } else {
            screenManager.push(InfotainmentMenuScreen(carContext, session))
        }
    }

    override fun onGetTemplate(): Template {
        val strings = StringsManager.get(ConfigManager.configState.value.appLanguage)
        return NavigationTemplate.Builder()
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(
                        Action.Builder()
                            .setTitle(strings.aaMenuBack)
                            .setOnClickListener {
                                goToMenu()
                            }
                            .build()
                    )
                    .build()
            )
            .build()
    }
}

/**
 * Główne menu konfiguracji na ekranie Infotainment:
 * Posiada opcję "Wyświetl ekran parametrów" powyżej "STATUS POŁĄCZENIA",
 * synchronizuje ustawienia na żywo z telefonem i zegarami.
 */
class InfotainmentMenuScreen(
    carContext: CarContext,
    private val session: HybridCarSession
) : Screen(carContext) {

    init {
        lifecycleScope.launch {
            ConfigManager.configState.collect {
                invalidate()
            }
        }
        lifecycleScope.launch {
            ObdManager.connectionStatus.collect {
                invalidate()
            }
        }
    }

    override fun onGetTemplate(): Template {
        val config = ConfigManager.configState.value
        val strings = StringsManager.get(config.appLanguage)
        val connectionStatus = ObdManager.connectionStatus.value
        val deviceName = ObdManager.connectedDeviceName.value ?: config.selectedBtDeviceName ?: "vLinker"

        val itemList = ItemList.Builder()

        // 1. WYŚWIETL EKRAN PARAMETRÓW (Powyżej STATUS POŁĄCZENIA zgodnie z wymogiem!)
        itemList.addItem(
            Row.Builder()
                .setTitle(strings.aaShowTelemetry)
                .addText(strings.aaShowTelemetryDesc)
                .setOnClickListener {
                    ConfigManager.updateConfig(config.copy(lastInfotainmentScreen = "TELEMETRY"))
                    screenManager.push(InfotainmentTelemetryScreen(carContext, session))
                }
                .build()
        )

        // 2. STATUS POŁĄCZENIA
        val statusTitle = when {
            config.demoMode -> "${strings.aaStatusPrefix}: ${strings.statusDemo}"
            connectionStatus == ObdConnectionStatus.CONNECTED -> "${strings.aaStatusPrefix}: ${strings.statusConnected(deviceName)}"
            connectionStatus == ObdConnectionStatus.CONNECTING -> "${strings.aaStatusPrefix}: ${strings.statusConnecting(deviceName)}"
            connectionStatus == ObdConnectionStatus.ERROR -> "${strings.aaStatusPrefix}: ${strings.statusError}"
            else -> "${strings.aaStatusPrefix}: ${strings.statusDisconnected}"
        }
        itemList.addItem(
            Row.Builder()
                .setTitle(statusTitle)
                .addText(strings.aaStatusCanDesc)
                .build()
        )

        // 3. TRYB SYMULACJI (DEMO)
        itemList.addItem(
            Row.Builder()
                .setTitle(strings.demoModeTitle)
                .addText(strings.demoModeSubtitle)
                .setToggle(
                    Toggle.Builder { checked ->
                        ConfigManager.updateConfig(config.copy(demoMode = checked))
                    }
                    .setChecked(config.demoMode)
                    .build()
                )
                .build()
        )

        // 4. PROFIL ADAPTERA VLINKER MS
        val protoTitle = if (config.useVlinkerFastProtocol) strings.adapterProfileVlinker else strings.adapterProfileElm
        val protoDesc = if (config.useVlinkerFastProtocol) strings.adapterProfileVlinkerDesc else strings.adapterProfileElmDesc
        itemList.addItem(
            Row.Builder()
                .setTitle(protoTitle)
                .addText(protoDesc)
                .setToggle(
                    Toggle.Builder { checked ->
                        ConfigManager.updateConfig(config.copy(useVlinkerFastProtocol = checked))
                    }
                    .setChecked(config.useVlinkerFastProtocol)
                    .build()
                )
                .build()
        )

        // 5. ELEMENTY NA ZEGARACH (Podmenu)
        itemList.addItem(
            Row.Builder()
                .setTitle(strings.cardElementsTitle)
                .addText(strings.cardElementsDesc)
                .setOnClickListener {
                    screenManager.push(InfotainmentElementsScreen(carContext))
                }
                .build()
        )

        // 6. PODGLĄD TELEMETRII NA ŻYWO (Podmenu tekstowe)
        itemList.addItem(
            Row.Builder()
                .setTitle(strings.cardTelemetryTitle)
                .addText(strings.cardTelemetryDesc)
                .setOnClickListener {
                    screenManager.push(InfotainmentLiveTelemetryScreen(carContext))
                }
                .build()
        )

        // 7. JĘZYK APLIKACJI (Przełącznik w Android Auto)
        val currentLang = AppLanguage.fromCode(config.appLanguage)
        val nextLang = when (currentLang) {
            AppLanguage.SYSTEM -> AppLanguage.PL
            AppLanguage.PL -> AppLanguage.EN
            AppLanguage.EN -> AppLanguage.SYSTEM
        }
        itemList.addItem(
            Row.Builder()
                .setTitle("${strings.sectionLanguage}: ${currentLang.flag} ${currentLang.displayName}")
                .addText(strings.langSwitchDesc(nextLang.displayName))
                .setOnClickListener {
                    ConfigManager.updateConfig(config.copy(appLanguage = nextLang.code))
                }
                .build()
        )

        return ListTemplate.Builder()
            .setTitle(strings.appTitle)
            .setHeaderAction(Action.APP_ICON)
            .setSingleList(itemList.build())
            .build()
    }
}

/**
 * Podmenu: Przełączniki elementów na ekranie Infotainment
 */
class InfotainmentElementsScreen(carContext: CarContext) : Screen(carContext) {

    init {
        lifecycleScope.launch {
            ConfigManager.configState.collect {
                invalidate()
            }
        }
    }

    override fun onGetTemplate(): Template {
        val config = ConfigManager.configState.value
        val strings = StringsManager.get(config.appLanguage)
        val itemList = ItemList.Builder()

        // 1. Wielosegmentowy pasek mocy
        itemList.addItem(
            Row.Builder()
                .setTitle(strings.togglePowerBarTitle)
                .addText(strings.togglePowerBarSubtitle)
                .setToggle(
                    Toggle.Builder { checked ->
                        ConfigManager.updateConfig(config.copy(showMultiSegmentPower = checked))
                    }.setChecked(config.showMultiSegmentPower).build()
                )
                .build()
        )

        // 2. Obrotomierz w trybie szerokim
        itemList.addItem(
            Row.Builder()
                .setTitle(strings.toggleTachoInWideTitle)
                .addText(strings.toggleTachoInWideSubtitle)
                .setToggle(
                    Toggle.Builder { checked ->
                        ConfigManager.updateConfig(config.copy(showTachometerInWide = checked))
                    }.setChecked(config.showTachometerInWide).build()
                )
                .build()
        )

        // 3. Akumulator 12V
        itemList.addItem(
            Row.Builder()
                .setTitle(strings.toggle12vTitle)
                .addText(if (config.show12vWattsMode) strings.toggle12vWattsTitle else strings.toggle12vSubtitle)
                .setToggle(
                    Toggle.Builder { checked ->
                        ConfigManager.updateConfig(config.copy(show12vBattery = checked))
                    }.setChecked(config.show12vBattery).build()
                )
                .build()
        )

        // 4. Podział napędu 4x4
        itemList.addItem(
            Row.Builder()
                .setTitle(strings.toggleTorqueSplitTitle)
                .addText(strings.toggleTorqueSplitSubtitle)
                .setToggle(
                    Toggle.Builder { checked ->
                        ConfigManager.updateConfig(config.copy(showTorqueSplit = checked))
                    }.setChecked(config.showTorqueSplit).build()
                )
                .build()
        )

        // 5. Temperatury podzespołów
        itemList.addItem(
            Row.Builder()
                .setTitle(strings.toggleTempsTitle)
                .addText(strings.toggleTempsSubtitle)
                .setToggle(
                    Toggle.Builder { checked ->
                        ConfigManager.updateConfig(config.copy(showTemperatures = checked))
                    }.setChecked(config.showTemperatures).build()
                )
                .build()
        )

        // 6. Statystyki podróży (Trip)
        itemList.addItem(
            Row.Builder()
                .setTitle(strings.toggleTripTitle)
                .addText(strings.toggleTripSubtitle)
                .setToggle(
                    Toggle.Builder { checked ->
                        ConfigManager.updateConfig(config.copy(showTripStats = checked))
                    }.setChecked(config.showTripStats).build()
                )
                .build()
        )

        return ListTemplate.Builder()
            .setTitle(strings.elementsScreenTitle)
            .setHeaderAction(Action.BACK)
            .setSingleList(itemList.build())
            .build()
    }
}

/**
 * Podmenu: Podgląd telemetrii na żywo na ekranie Infotainment
 */
class InfotainmentLiveTelemetryScreen(carContext: CarContext) : Screen(carContext) {

    init {
        lifecycleScope.launch {
            // Szablony Android Auto są odświeżane przez host z ograniczoną częstotliwością –
            // invalidate() przy każdej ramce OBD (~15/s) tylko marnowało IPC. 1 Hz wystarcza.
            var lastInvalidate = 0L
            ObdManager.telemetryState.collect {
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastInvalidate >= 1000L) {
                    lastInvalidate = now
                    invalidate()
                }
            }
        }
    }

    override fun onGetTemplate(): Template {
        val t = ObdManager.telemetryState.value
        val strings = StringsManager.get(ConfigManager.configState.value.appLanguage)

        val pane = Pane.Builder()

        val modeStr = strings.modeName(t.hybridMode)
        val r1 = Row.Builder()
            .setTitle("${strings.gearLabel}: ${t.currentGear} (EV: ${t.eGear})  |  ${strings.modeLabel}: $modeStr")
            .addText("${strings.speedLabel}: ${t.speedKmH.toInt()} km/h  |  ${strings.iceRpmLabel}: ${t.iceRpm} RPM  |  ${strings.eMotorLabel}: ${Math.abs(t.rearMotorRpm)} RPM")
            .build()

        val r2 = Row.Builder()
            .setTitle("${strings.powerOutputLabel}: +${t.totalOutputPowerKw.toInt()} kW  |  ${strings.regenLabel}: -${t.totalRegenPowerKw.toInt()} kW")
            .addText("ICE: ${t.icePowerKw.toInt()} kW  |  ${strings.rearLabel}: ${t.rearMotorPowerKw.toInt()} kW  |  HSG: ${t.hsgPowerKw.toInt()} kW")
            .build()

        val r3 = Row.Builder()
            .setTitle("${strings.torqueSplitLabel}: ${t.frontTorquePercent}% ${strings.frontLabel} / ${t.rearTorquePercent}% ${strings.rearLabel}")
            .addText("${strings.coolantLabel}: ${fmtTemp(t.coolantTempC)}  |  ${strings.engineOilLabel}: ${fmtTemp(t.oilTempC)}  |  ATF: ${fmtTemp(t.gearboxTempC)}")
            .build()

        val soc48Str = if (t.hvBatterySocPercent in 5.0..100.0) "${t.hvBatterySocPercent.toInt()}%" else "--%"
        val r4 = Row.Builder()
            .setTitle("48V: ${String.format(java.util.Locale.US, "%.1f", t.hvBatteryVoltage)}V ($soc48Str)  |  12V: ${String.format(java.util.Locale.US, "%.1f", t.bat12vVoltage)}V")
            .addText("Trip: ${t.tripEvDistancePercent.toInt()}% EV  |  ${strings.tripRegenLabel}: ${String.format(java.util.Locale.US, "%.2f", t.tripRegenKWh)} kWh  |  ${strings.tripSavedLabel}: +${String.format(java.util.Locale.US, "%.2f", t.tripFuelSavedLiters)} L")
            .build()

        pane.addRow(r1)
        pane.addRow(r2)
        pane.addRow(r3)
        pane.addRow(r4)

        pane.addAction(
            Action.Builder()
                .setTitle(strings.btnResetTrip)
                .setOnClickListener {
                    ObdManager.resetTrip()
                }
                .build()
        )

        return PaneTemplate.Builder(pane.build())
            .setTitle(strings.telemetryTitle)
            .setHeaderAction(Action.BACK)
            .build()
    }
}

/** Temperatura do wyświetlenia; brak odczytu (NO_TEMP) -> "--" zamiast "-999°C". */
private fun fmtTemp(t: Double): String = if (t <= -100.0) "--" else "${t.toInt()}°C"
