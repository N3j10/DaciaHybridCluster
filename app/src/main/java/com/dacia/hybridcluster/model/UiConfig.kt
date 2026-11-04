package com.dacia.hybridcluster.model

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class UiConfig(
    val useVlinkerFastProtocol: Boolean = true, // true = vLinker MS (profil STN: komendy STPX, adaptacyjny timing, wysoki FPS), false = klasyczny ELM327
    val show12vBattery: Boolean = true,
    val show12vWattsMode: Boolean = false, // false = napięcie i stan ładowania (np. 14.1V • 85% ↑), true = pobór mocy w watach (POBÓR MOCY 12V)
    val showTripStats: Boolean = true, // Statystyki energii, zużycia i oszczędności (domyślnie włączone)
    val showGForce: Boolean = false,
    val showTorqueSplit: Boolean = true,
    val showTemperatures: Boolean = true,
    val showMultiSegmentPower: Boolean = true,
    val showTachometerInWide: Boolean = true,
    val logObdData: Boolean = false,
    val logExperimentalPids: Boolean = false, // Zbieraj dodatkowe eksperymentalne PID-y z różnych modułów w celu analizy i ulepszeń
    val demoMode: Boolean = false, // Domyślnie false, bo użytkownik jest w aucie i czyta prawdziwe OBD!
    val uiScale: Float = 1.15f, // Skala wielkości tekstu i elementów (0.8f - 1.6f)

    // === TRYB PEŁNY (Obrotomierz + Kafelki) ===
    val fullLeftOffsetPercent: Float = 24f,       // Pozycja startu lewego okna (za fabrycznym prędkościomierzem i znakiem 20 km/h)
    val fullTachoWidthPercent: Float = 18f,       // Szerokość lewego okna (obrotomierz)
    val fullRightMarginPercent: Float = 18f,      // Margines z prawej (ochrona przed ramką/menu)
    val fullBottomMarginPercent: Float = 22f,     // Margines dolny (ochrona przed paskiem LPG/zasięgu)
    val fullTopMarginPercent: Float = 1f,         // Margines górny (drobna korekta pionowa)

    // === TRYB TYLKO KAFELKI (Do bocznego menu auta) ===
    val cardsOnlyLeftOffsetPercent: Float = 25f,  // Szerokość lewego okna (wolne miejsce na prędkościomierz i widżety auta)
    val cardsOnlyRightMarginPercent: Float = 18f, // Margines z prawej (ochrona przed ramką/menu)
    val cardsOnlyBottomMarginPercent: Float = 22f,// Margines dolny (ochrona przed paskiem LPG/zasięgu)
    val cardsOnlyTopMarginPercent: Float = 1f,    // Margines górny (drobna korekta pionowa)

    // === INFOTAINMENT (EKRAN ŚRODKOWY): OSTATNIO WYBRANY EKRAN I TRYB WIDOKU ===
    val lastInfotainmentScreen: String = "TELEMETRY", // "TELEMETRY" lub "MENU"
    val infoViewMode: String = "AUTO", // "AUTO" (wg podziału Coolwalk), "TACHO" (zawsze z obrotomierzem), "CARDS" (zawsze tylko kafelki)

    // === INFOTAINMENT: KALIBRACJA - PEŁNY EKRAN (Z OBROTOMIERZEM) ===
    val infoFullLeftOffsetPercent: Float = 6f,       // Start obrotomierza / lewy margines
    val infoFullTachoWidthPercent: Float = 22f,      // Szerokość obrotomierza
    val infoFullRightMarginPercent: Float = 6f,      // Margines z prawej
    val infoFullBottomMarginPercent: Float = 6f,     // Margines dolny
    val infoFullTopMarginPercent: Float = 4f,        // Margines górny (miejsce na belkę / przycisk Menu)

    // === INFOTAINMENT: KALIBRACJA - PODZIELONY EKRAN (TYLKO KAFELKI) ===
    val infoSplitLeftOffsetPercent: Float = 5f,      // Lewy margines kafelków
    val infoSplitRightMarginPercent: Float = 5f,     // Prawy margines
    val infoSplitBottomMarginPercent: Float = 6f,    // Dolny margines
    val infoSplitTopMarginPercent: Float = 4f,       // Margines górny

    // Skala UI dla Infotainment
    val infoUiScale: Float = 1.10f,

    // Kolory
    val clusterBgColorHex: String = "#000000",   // Główne tło zegarów: czysta czerń (idealne dopasowanie do ramki zegarów OLED/LCD)
    val clusterCardColorHex: String = "#171717", // Tło kafelków telemetrii (grafit)
    val selectedBtDeviceAddress: String? = null,
    val selectedBtDeviceName: String? = null,

    // Język aplikacji ("SYSTEM", "PL", "EN")
    val appLanguage: String = "SYSTEM",

    // Pola kompatybilności wstecznej
    val clusterTachoOffsetPercent: Float = 24f,
    val clusterBottomMarginPercent: Float = 22f,
    val clusterRightMarginPercent: Float = 18f
)

object ConfigManager {
    private const val PREFS_NAME = "dacia_hybrid_cluster_prefs"
    private lateinit var prefs: SharedPreferences

    private val _configState = MutableStateFlow(UiConfig())
    val configState: StateFlow<UiConfig> = _configState

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val legacyTachoOffset = prefs.getFloat("clusterTachoOffsetPercent", 24f)
        val legacyBottomMargin = prefs.getFloat("clusterBottomMarginPercent", 22f)
        val legacyRightMargin = prefs.getFloat("clusterRightMarginPercent", 18f)

        val loaded = UiConfig(
            useVlinkerFastProtocol = prefs.getBoolean("useVlinkerFastProtocol", true),
            show12vBattery = prefs.getBoolean("show12vBattery", true),
            show12vWattsMode = prefs.getBoolean("show12vWattsMode", false),
            showTripStats = prefs.getBoolean("showTripStats", true),
            showGForce = prefs.getBoolean("showGForce", false),
            showTorqueSplit = prefs.getBoolean("showTorqueSplit", true),
            showTemperatures = prefs.getBoolean("showTemperatures", true),
            showMultiSegmentPower = prefs.getBoolean("showMultiSegmentPower", true),
            showTachometerInWide = prefs.getBoolean("showTachometerInWide", true),
            logObdData = prefs.getBoolean("logObdData", false),
            logExperimentalPids = prefs.getBoolean("logExperimentalPids", false),
            demoMode = prefs.getBoolean("demoMode", false),
            uiScale = prefs.getFloat("uiScale", 1.15f),

            // Tryb Pełny
            fullLeftOffsetPercent = prefs.getFloat("fullLeftOffsetPercent", legacyTachoOffset),
            fullTachoWidthPercent = prefs.getFloat("fullTachoWidthPercent", 18f),
            fullRightMarginPercent = prefs.getFloat("fullRightMarginPercent", legacyRightMargin),
            fullBottomMarginPercent = prefs.getFloat("fullBottomMarginPercent", legacyBottomMargin),
            fullTopMarginPercent = prefs.getFloat("fullTopMarginPercent", 1f),

            // Tryb Tylko kafelki
            cardsOnlyLeftOffsetPercent = prefs.getFloat("cardsOnlyLeftOffsetPercent", 25f),
            cardsOnlyRightMarginPercent = prefs.getFloat("cardsOnlyRightMarginPercent", legacyRightMargin),
            cardsOnlyBottomMarginPercent = prefs.getFloat("cardsOnlyBottomMarginPercent", legacyBottomMargin),
            cardsOnlyTopMarginPercent = prefs.getFloat("cardsOnlyTopMarginPercent", 1f),

            // Infotainment
            lastInfotainmentScreen = prefs.getString("lastInfotainmentScreen", "TELEMETRY") ?: "TELEMETRY",
            infoViewMode = prefs.getString("infoViewMode", "AUTO") ?: "AUTO",
            infoFullLeftOffsetPercent = prefs.getFloat("infoFullLeftOffsetPercent", 6f),
            infoFullTachoWidthPercent = prefs.getFloat("infoFullTachoWidthPercent", 22f),
            infoFullRightMarginPercent = prefs.getFloat("infoFullRightMarginPercent", 6f),
            infoFullBottomMarginPercent = prefs.getFloat("infoFullBottomMarginPercent", 6f),
            infoFullTopMarginPercent = prefs.getFloat("infoFullTopMarginPercent", 4f),
            infoSplitLeftOffsetPercent = prefs.getFloat("infoSplitLeftOffsetPercent", 5f),
            infoSplitRightMarginPercent = prefs.getFloat("infoSplitRightMarginPercent", 5f),
            infoSplitBottomMarginPercent = prefs.getFloat("infoSplitBottomMarginPercent", 6f),
            infoSplitTopMarginPercent = prefs.getFloat("infoSplitTopMarginPercent", 4f),
            infoUiScale = prefs.getFloat("infoUiScale", 1.10f),

            // Kolory
            clusterBgColorHex = prefs.getString("clusterBgColorHex", "#000000") ?: "#000000",
            clusterCardColorHex = prefs.getString("clusterCardColorHex", "#171717") ?: "#171717",
            selectedBtDeviceAddress = prefs.getString("selectedBtDeviceAddress", null),
            selectedBtDeviceName = prefs.getString("selectedBtDeviceName", null),
            appLanguage = prefs.getString("appLanguage", "SYSTEM") ?: "SYSTEM",

            // Kompatybilność
            clusterTachoOffsetPercent = prefs.getFloat("fullLeftOffsetPercent", legacyTachoOffset),
            clusterBottomMarginPercent = prefs.getFloat("fullBottomMarginPercent", legacyBottomMargin),
            clusterRightMarginPercent = prefs.getFloat("fullRightMarginPercent", legacyRightMargin)
        )
        _configState.value = loaded
        DaciaFonts.init(context)
        com.dacia.hybridcluster.obd.ObdLogger.init(context)
        com.dacia.hybridcluster.obd.ObdLogger.setEnabled(loaded.logObdData)
    }

    fun updateConfig(newConfig: UiConfig) {
        _configState.value = newConfig
        com.dacia.hybridcluster.obd.ObdLogger.setEnabled(newConfig.logObdData)
        if (::prefs.isInitialized) {
            prefs.edit()
                .putBoolean("useVlinkerFastProtocol", newConfig.useVlinkerFastProtocol)
                .putBoolean("show12vBattery", newConfig.show12vBattery)
                .putBoolean("show12vWattsMode", newConfig.show12vWattsMode)
                .putBoolean("showTripStats", newConfig.showTripStats)
                .putBoolean("showGForce", newConfig.showGForce)
                .putBoolean("showTorqueSplit", newConfig.showTorqueSplit)
                .putBoolean("showTemperatures", newConfig.showTemperatures)
                .putBoolean("showMultiSegmentPower", newConfig.showMultiSegmentPower)
                .putBoolean("showTachometerInWide", newConfig.showTachometerInWide)
                .putBoolean("logObdData", newConfig.logObdData)
                .putBoolean("logExperimentalPids", newConfig.logExperimentalPids)
                .putBoolean("demoMode", newConfig.demoMode)
                .putFloat("uiScale", newConfig.uiScale)

                // Tryb Pełny
                .putFloat("fullLeftOffsetPercent", newConfig.fullLeftOffsetPercent)
                .putFloat("fullTachoWidthPercent", newConfig.fullTachoWidthPercent)
                .putFloat("fullRightMarginPercent", newConfig.fullRightMarginPercent)
                .putFloat("fullBottomMarginPercent", newConfig.fullBottomMarginPercent)
                .putFloat("fullTopMarginPercent", newConfig.fullTopMarginPercent)

                // Tryb Tylko kafelki
                .putFloat("cardsOnlyLeftOffsetPercent", newConfig.cardsOnlyLeftOffsetPercent)
                .putFloat("cardsOnlyRightMarginPercent", newConfig.cardsOnlyRightMarginPercent)
                .putFloat("cardsOnlyBottomMarginPercent", newConfig.cardsOnlyBottomMarginPercent)
                .putFloat("cardsOnlyTopMarginPercent", newConfig.cardsOnlyTopMarginPercent)

                // Infotainment
                .putString("lastInfotainmentScreen", newConfig.lastInfotainmentScreen)
                .putString("infoViewMode", newConfig.infoViewMode)
                .putFloat("infoFullLeftOffsetPercent", newConfig.infoFullLeftOffsetPercent)
                .putFloat("infoFullTachoWidthPercent", newConfig.infoFullTachoWidthPercent)
                .putFloat("infoFullRightMarginPercent", newConfig.infoFullRightMarginPercent)
                .putFloat("infoFullBottomMarginPercent", newConfig.infoFullBottomMarginPercent)
                .putFloat("infoFullTopMarginPercent", newConfig.infoFullTopMarginPercent)
                .putFloat("infoSplitLeftOffsetPercent", newConfig.infoSplitLeftOffsetPercent)
                .putFloat("infoSplitRightMarginPercent", newConfig.infoSplitRightMarginPercent)
                .putFloat("infoSplitBottomMarginPercent", newConfig.infoSplitBottomMarginPercent)
                .putFloat("infoSplitTopMarginPercent", newConfig.infoSplitTopMarginPercent)
                .putFloat("infoUiScale", newConfig.infoUiScale)

                // Kolory i adapter
                .putString("clusterBgColorHex", newConfig.clusterBgColorHex)
                .putString("clusterCardColorHex", newConfig.clusterCardColorHex)
                .putString("selectedBtDeviceAddress", newConfig.selectedBtDeviceAddress)
                .putString("selectedBtDeviceName", newConfig.selectedBtDeviceName)
                .putString("appLanguage", newConfig.appLanguage)

                // Kompatybilność
                .putFloat("clusterTachoOffsetPercent", newConfig.fullLeftOffsetPercent)
                .putFloat("clusterBottomMarginPercent", newConfig.fullBottomMarginPercent)
                .putFloat("clusterRightMarginPercent", newConfig.fullRightMarginPercent)
                .apply()
        }
    }
}
