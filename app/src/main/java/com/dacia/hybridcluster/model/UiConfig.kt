package com.dacia.hybridcluster.model

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
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

    // Zegary: rysuj w obszarze bezpiecznym zgłaszanym przez host Android Auto (stable/visible area)
    // zamiast na całej powierzchni. Domyślnie wyłączone, bo dotychczasowa kalibracja % jest liczona
    // względem całej powierzchni.
    val clusterUseHostSafeArea: Boolean = false
)

/**
 * Przechowywanie konfiguracji: cały [UiConfig] zapisany jako jeden JSON (Gson) w SharedPreferences.
 *
 * Dodanie nowego pola do [UiConfig] nie wymaga już ręcznego dopisywania odczytu i zapisu
 * (wcześniej ~40 par getFloat/putFloat). Brakujące w JSON-ie pola dostają wartości domyślne
 * z konstruktora, a stare instalacje są jednorazowo migrowane z pojedynczych kluczy.
 */
object ConfigManager {
    private const val PREFS_NAME = "dacia_hybrid_cluster_prefs"
    private const val KEY_JSON = "ui_config_json_v1"

    private lateinit var prefs: SharedPreferences
    private val gson = Gson()

    private val _configState = MutableStateFlow(UiConfig())
    val configState: StateFlow<UiConfig> = _configState

    @Synchronized
    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val loaded = prefs.getString(KEY_JSON, null)?.let { fromJson(it) } ?: migrateLegacy(prefs).also { save(it) }
        _configState.value = loaded
        com.dacia.hybridcluster.obd.ObdLogger.setEnabled(loaded.logObdData)
    }

    fun updateConfig(newConfig: UiConfig) {
        _configState.value = newConfig
        com.dacia.hybridcluster.obd.ObdLogger.setEnabled(newConfig.logObdData)
        if (::prefs.isInitialized) save(newConfig)
    }

    private fun save(cfg: UiConfig) {
        prefs.edit().putString(KEY_JSON, gson.toJson(cfg)).apply()
    }

    /**
     * Gson tworzy obiekty bez konstruktora, więc pola nieobecne w zapisanym JSON-ie miałyby wartości 0/null.
     * Dlatego scalamy zapisany JSON z JSON-em domyślnej konfiguracji.
     */
    internal fun fromJson(json: String): UiConfig? = try {
        val defaults = gson.toJsonTree(UiConfig()).asJsonObject
        val stored = JsonParser.parseString(json).asJsonObject
        val merged = JsonObject()
        for ((k, v) in defaults.entrySet()) merged.add(k, if (stored.has(k) && !stored.get(k).isJsonNull) stored.get(k) else v)
        // selectedBt* mogą być null – zachowaj zapisany null
        for (k in listOf("selectedBtDeviceAddress", "selectedBtDeviceName")) if (stored.has(k)) merged.add(k, stored.get(k))
        gson.fromJson(merged, UiConfig::class.java)
    } catch (_: Exception) {
        null
    }

    /** Odczyt ustawień zapisanych przez starą wersję aplikacji (osobny klucz na każde pole). */
    private fun migrateLegacy(p: SharedPreferences): UiConfig {
        val d = UiConfig()
        val legacyTachoOffset = p.getFloat("clusterTachoOffsetPercent", d.fullLeftOffsetPercent)
        val legacyBottomMargin = p.getFloat("clusterBottomMarginPercent", d.fullBottomMarginPercent)
        val legacyRightMargin = p.getFloat("clusterRightMarginPercent", d.fullRightMarginPercent)
        return UiConfig(
            useVlinkerFastProtocol = p.getBoolean("useVlinkerFastProtocol", d.useVlinkerFastProtocol),
            show12vBattery = p.getBoolean("show12vBattery", d.show12vBattery),
            show12vWattsMode = p.getBoolean("show12vWattsMode", d.show12vWattsMode),
            showTripStats = p.getBoolean("showTripStats", d.showTripStats),
            showGForce = p.getBoolean("showGForce", d.showGForce),
            showTorqueSplit = p.getBoolean("showTorqueSplit", d.showTorqueSplit),
            showTemperatures = p.getBoolean("showTemperatures", d.showTemperatures),
            showMultiSegmentPower = p.getBoolean("showMultiSegmentPower", d.showMultiSegmentPower),
            showTachometerInWide = p.getBoolean("showTachometerInWide", d.showTachometerInWide),
            logObdData = p.getBoolean("logObdData", d.logObdData),
            demoMode = p.getBoolean("demoMode", d.demoMode),
            uiScale = p.getFloat("uiScale", d.uiScale),
            fullLeftOffsetPercent = p.getFloat("fullLeftOffsetPercent", legacyTachoOffset),
            fullTachoWidthPercent = p.getFloat("fullTachoWidthPercent", d.fullTachoWidthPercent),
            fullRightMarginPercent = p.getFloat("fullRightMarginPercent", legacyRightMargin),
            fullBottomMarginPercent = p.getFloat("fullBottomMarginPercent", legacyBottomMargin),
            fullTopMarginPercent = p.getFloat("fullTopMarginPercent", d.fullTopMarginPercent),
            cardsOnlyLeftOffsetPercent = p.getFloat("cardsOnlyLeftOffsetPercent", d.cardsOnlyLeftOffsetPercent),
            cardsOnlyRightMarginPercent = p.getFloat("cardsOnlyRightMarginPercent", legacyRightMargin),
            cardsOnlyBottomMarginPercent = p.getFloat("cardsOnlyBottomMarginPercent", legacyBottomMargin),
            cardsOnlyTopMarginPercent = p.getFloat("cardsOnlyTopMarginPercent", d.cardsOnlyTopMarginPercent),
            lastInfotainmentScreen = p.getString("lastInfotainmentScreen", d.lastInfotainmentScreen) ?: d.lastInfotainmentScreen,
            infoViewMode = p.getString("infoViewMode", d.infoViewMode) ?: d.infoViewMode,
            infoFullLeftOffsetPercent = p.getFloat("infoFullLeftOffsetPercent", d.infoFullLeftOffsetPercent),
            infoFullTachoWidthPercent = p.getFloat("infoFullTachoWidthPercent", d.infoFullTachoWidthPercent),
            infoFullRightMarginPercent = p.getFloat("infoFullRightMarginPercent", d.infoFullRightMarginPercent),
            infoFullBottomMarginPercent = p.getFloat("infoFullBottomMarginPercent", d.infoFullBottomMarginPercent),
            infoFullTopMarginPercent = p.getFloat("infoFullTopMarginPercent", d.infoFullTopMarginPercent),
            infoSplitLeftOffsetPercent = p.getFloat("infoSplitLeftOffsetPercent", d.infoSplitLeftOffsetPercent),
            infoSplitRightMarginPercent = p.getFloat("infoSplitRightMarginPercent", d.infoSplitRightMarginPercent),
            infoSplitBottomMarginPercent = p.getFloat("infoSplitBottomMarginPercent", d.infoSplitBottomMarginPercent),
            infoSplitTopMarginPercent = p.getFloat("infoSplitTopMarginPercent", d.infoSplitTopMarginPercent),
            infoUiScale = p.getFloat("infoUiScale", d.infoUiScale),
            clusterBgColorHex = p.getString("clusterBgColorHex", d.clusterBgColorHex) ?: d.clusterBgColorHex,
            clusterCardColorHex = p.getString("clusterCardColorHex", d.clusterCardColorHex) ?: d.clusterCardColorHex,
            selectedBtDeviceAddress = p.getString("selectedBtDeviceAddress", null),
            selectedBtDeviceName = p.getString("selectedBtDeviceName", null),
            appLanguage = p.getString("appLanguage", d.appLanguage) ?: d.appLanguage
        )
    }
}
