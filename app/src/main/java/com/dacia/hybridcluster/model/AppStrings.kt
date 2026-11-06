package com.dacia.hybridcluster.model

import java.util.Locale

/**
 * Obsługiwane języki aplikacji.
 * SYSTEM automatycznie dostosowuje się do języka telefonu (domyślnie).
 */
enum class AppLanguage(val code: String, val displayName: String, val flag: String) {
    SYSTEM("SYSTEM", "Domyślny systemu", "📱"),
    PL("PL", "Polski", "🇵🇱"),
    EN("EN", "English", "🇬🇧");

    companion object {
        fun fromCode(code: String?): AppLanguage {
            if (code.isNullOrBlank()) return SYSTEM
            return entries.firstOrNull {
                it.code.equals(code, ignoreCase = true) || it.name.equals(code, ignoreCase = true)
            } ?: SYSTEM
        }
    }
}

/**
 * Interfejs wszystkich ciągów tekstowych w aplikacji.
 * Gwarantuje spójność i ułatwia dodawanie kolejnych wersji językowych (np. RO, DE, FR).
 */
interface AppStrings {
    val languageCode: String

    // === EKRAN GŁÓWNY (MAIN SCREEN) ===
    val appTitle: String
    val appSubtitle: String
    val connectionStatusLabel: String
    val statusDemo: String
    fun statusConnected(deviceName: String): String
    fun statusConnecting(deviceName: String): String
    val statusError: String
    val statusDisconnected: String
    val btnDisconnect: String
    val demoModeTitle: String
    val demoModeSubtitle: String
    val adapterProfileVlinker: String
    val adapterProfileElm: String
    val adapterProfileVlinkerDesc: String
    val adapterProfileElmDesc: String
    fun btnChangeDevice(name: String): String
    val btnSelectDevice: String
    fun toastConnecting(target: String): String

    // Sekcje i kafelki menu głównego
    val sectionModules: String
    val cardCalibrationTitle: String
    val cardCalibrationDesc: String
    val cardElementsTitle: String
    val cardElementsDesc: String
    val cardTelemetryTitle: String
    val cardTelemetryDesc: String
    val cardDiagnosticsTitle: String
    val cardDiagnosticsDesc: String
    val btnOpen: String

    // Sekcja wyboru języka
    val sectionLanguage: String
    val languageTitle: String
    val languageSubtitle: String
    val langSystemOption: String
    val langPlOption: String
    val langEnOption: String

    // === PODSTRONA: ELEMENTY ZEGARÓW (ELEMENTS) ===
    val btnBackToMenu: String
    val elementsScreenTitle: String
    val elementsScreenSubtitle: String
    val elementsActiveLayoutTitle: String
    val elementsActiveLayoutSubtitle: String
    val layoutCardsOnly: String
    val layoutFull: String
    val togglePowerBarTitle: String
    val togglePowerBarSubtitle: String
    val toggleTachoInWideTitle: String
    val toggleTachoInWideSubtitle: String
    val toggle12vTitle: String
    val toggle12vSubtitle: String
    val toggle12vWattsTitle: String
    val toggle12vWattsSubtitle: String
    val toggleTorqueSplitTitle: String
    val toggleTorqueSplitSubtitle: String
    val toggleTempsTitle: String
    val toggleTempsSubtitle: String
    val toggleTripTitle: String
    val toggleTripSubtitle: String
    val toggleGForceTitle: String
    val toggleGForceSubtitle: String
    val toggleLogObdTitle: String
    val toggleLogObdSubtitle: String
    val toggleExpPidsTitle: String
    val toggleExpPidsSubtitle: String

    // === PODSTRONA: TELEMETRIA NA ŻYWO (LIVE TELEMETRY) ===
    val telemetryTitle: String
    val telemetrySubtitle: String
    val boostActive: String
    val boostInactive: String
    val gearLabel: String
    val modeLabel: String
    val speedLabel: String
    val iceRpmLabel: String
    val eMotorLabel: String
    val powerOutputLabel: String
    val regenLabel: String
    val torqueSplitLabel: String
    val coolantLabel: String
    val engineOilLabel: String
    val gearboxAtfLabel: String
    val tripEvLabel: String
    val tripRegenLabel: String
    val tripSavedLabel: String
    val btnResetTrip: String

    // === PODSTRONA: DIAGNOSTYKA OBD (DIAGNOSTICS) ===
    val diagTitle: String
    val diagSubtitle: String
    fun diagLogActive(sizeKb: Long): String
    val diagLogDisabled: String
    fun diagFileLabel(path: String): String
    val btnRefreshLog: String
    val btnClearLog: String

    // === PODSTRONA: KALIBRACJA EKRANU (CALIBRATION) ===
    val calibTitle: String
    val calibSubtitle: String
    val calibTargetDisplayHeader: String
    val displayCluster: String
    val displayInfotainment: String
    val calibLayoutHeader: String
    fun activeModeOnCluster(mode: String): String
    val previewLiveHeader: String
    fun previewSpeedo(pct: Int): String
    fun previewTacho(pct: Int): String
    val previewCards: String
    fun previewMargin(pct: Int): String
    fun previewFreeSpaceSpeedo(pct: Int): String
    val previewCardsRight: String

    // Suwaki trybów zegarów
    val fullModeSlidersHeader: String
    val cardsOnlySlidersHeader: String
    fun tachoWidthSlider(pct: Int): String
    val tachoWidthSliderDesc: String
    fun tachoLeftOffsetSlider(pct: Int): String
    val tachoLeftOffsetSliderDesc: String
    fun rightMarginSlider(pct: Int): String
    val rightMarginSliderDesc: String
    fun bottomMarginSlider(pct: Int): String
    val bottomMarginSliderDesc: String
    fun topMarginSlider(pct: Int): String
    val topMarginSliderDesc: String
    val btnRestoreDefaultsFull: String
    val btnRestoreDefaultsCardsOnly: String
    fun cardsOnlyLeftSpaceSlider(pct: Int): String
    val cardsOnlyLeftSpaceSliderDesc: String
    val cardsOnlyRightMarginSliderDesc: String

    // Kolory i skala
    val fontSizeAndColorsHeader: String
    fun uiScaleSlider(pct: Int): String
    val clusterBgColorTitle: String
    val clusterBgColorDesc: String
    val presetPureBlack: String
    val presetGraphite: String
    val presetAnthracite: String
    val presetNavy: String
    val cardColorTitle: String
    val cardColorDesc: String
    val redR: String
    val greenG: String
    val blueB: String
    val quickPresets: String
    val presetGraphiteOem: String
    val presetBlack: String
    val presetNavyBlue: String
    val presetOlive: String
    val presetLight: String

    // Infotainment kalibracja
    val infoDisplayModeHeader: String
    val infoWithTacho: String
    val infoCardsOnly: String
    val infoAutoCoolwalk: String
    val infoDescTacho: String
    val infoDescCards: String
    val infoDescAuto: String
    val infoPreviewHeader: String
    val infoSlidersFullHeader: String
    fun infoLeftMargSlider(pct: Int): String
    val infoLeftMargSliderDesc: String
    fun infoTachoWidthSlider(pct: Int): String
    val infoTachoWidthSliderDesc: String
    fun infoRightMargSlider(pct: Int): String
    val infoRightMargSliderDesc: String
    fun infoBottomMargSlider(pct: Int): String
    val infoBottomMargSliderDesc: String
    fun infoTopMargSlider(pct: Int): String
    val infoTopMargSliderDesc: String
    val btnRestoreDefaultsInfoFull: String
    val infoSlidersSplitHeader: String
    val infoSplitLeftDesc: String
    val infoSplitRightDesc: String
    val btnRestoreDefaultsInfoSplit: String
    val infoScaleHeader: String
    fun infoScaleSlider(pct: Int): String
    val btnRestoreDefaultsInfoScale: String

    // Dialog Bluetooth
    val btDialogTitle: String
    val btDialogNoDevices: String
    val btUnknownDevice: String
    val btDialogCancel: String

    // Android Auto Menu
    val aaShowTelemetry: String
    val aaShowTelemetryDesc: String
    val aaStatusPrefix: String
    val aaStatusCanDesc: String
    val aaMenuBack: String

    // Nowe elementy renderera Zegarów i Infotainment
    val gearLabelShort: String
    val modePrefix: String
    val frontLabel: String
    val rearLabel: String
    val rpmUnit: String
    val fuelBoostLabel: String
    val evBlockLabel: String
    val oilLabelShort: String
    val gearboxLabelShort: String
    val elecMotorLabelShort: String
    val coolantLabelShort: String
    val bat48vLabel: String
    val bat12vLabel: String
    val bat12vWattsLabel: String
    val usedBatteryLabel: String
    val regenRecoveryLabel: String
    val fuelSavedLabel: String
    val regenAwdSub: String
    val hybridSavingsSub: String
    val powerSuffix: String
    val regenSuffix: String
    val stoppedStatus: String
    val charging48vStatus: String
    val reversingStatus: String
    val reversingIceStatus: String
    val pureEvDriveStatus: String
    val coastingStatus: String
    val calibPreviewCardsInfoFull: String
    val calibPreviewCardsInfoSplit: String
    fun langSwitchDesc(nextName: String): String
    val toggleHostSafeAreaTitle: String
    val toggleHostSafeAreaSubtitle: String
    fun modeName(mode: HybridMode): String
    fun inhibitName(reason: EvInhibit): String

    /** Pełna linia blokady EV wyświetlana pod obrotomierzem, np. "BLOKADA EV: KLIMA / NAWIEW". */
    fun inhibitLine(reason: EvInhibit): String = "$evBlockLabel: ${inhibitName(reason)}"
}

/**
 * Wersja polska (PL)
 */
object StringsPl : AppStrings {
    override val languageCode: String = "PL"

    override val appTitle = "Dacia Hybrid-G Telemetry"
    override val appSubtitle = "Konfiguracja zegarów Android Auto & vLinkera"
    override val connectionStatusLabel = "STATUS POŁĄCZENIA"
    override val statusDemo = "Tryb Symulacji (Demo)"
    override fun statusConnected(deviceName: String) = "Połączono: $deviceName"
    override fun statusConnecting(deviceName: String) = "Łączenie z $deviceName..."
    override val statusError = "Błąd połączenia (Sprawdź zasilanie OBD)"
    override val statusDisconnected = "Rozłączono (Wybierz adapter)"
    override val btnDisconnect = "Rozłącz"
    override val demoModeTitle = "Tryb Symulacji (Demo)"
    override val demoModeSubtitle = "Generuje dynamiczne dane bez podłączania do auta"
    override val adapterProfileVlinker = "Profil: vLinker MS (STN Szybki)"
    override val adapterProfileElm = "Profil: ELM327 (Standard)"
    override val adapterProfileVlinkerDesc = "Aktywny: Rozkazy STPX, adaptacyjny timing i odświeżanie 15-25 FPS"
    override val adapterProfileElmDesc = "Aktywny: Klasyczna emulacja ELM327 (przełączanie nagłówków ATSH)"
    override fun btnChangeDevice(name: String) = "Zmień vLinker ($name)"
    override val btnSelectDevice = "Wybierz sparowany adapter vLinker"
    override fun toastConnecting(target: String) = "Łączenie z $target..."

    override val sectionModules = "MODUŁY I USTAWIENIA"
    override val cardCalibrationTitle = "📐 KALIBRACJA EKRANU I SUWAKI UI"
    override val cardCalibrationDesc = "Osobna kalibracja dla Zegarów (Cluster) oraz Ekranu Środkowego (Infotainment - pełny i podzielony)"
    override val cardElementsTitle = "🎛️ ELEMENTY NA CYFROWYCH ZEGARACH"
    override val cardElementsDesc = "Wybór układu (Tylko kafelki / Pełny), pasek mocy, 12V, podział 4x4, temperatury, trip, logowanie"
    override val cardTelemetryTitle = "⚡ PODGLĄD TELEMETRII NA ŻYWO"
    override val cardTelemetryDesc = "Moc silników w kW, obroty ICE/e-Motor, temperatury podzespołów, akumulatory 48V i 12V na żywo"
    override val cardDiagnosticsTitle = "📊 DIAGNOSTYKA I LOGI OBD"
    override val cardDiagnosticsDesc = "Podgląd surowego bufora komend magistrali CAN, rozmiar pliku logu oraz czyszczenie"
    override val btnOpen = "Otwórz ➔"

    override val sectionLanguage = "JĘZYK APLIKACJI"
    override val languageTitle = "Wybór języka interfejsu"
    override val languageSubtitle = "Zmienia język aplikacji w telefonie oraz w Android Auto"
    override val langSystemOption = "📱 Auto (System)"
    override val langPlOption = "🇵🇱 Polski"
    override val langEnOption = "🇬🇧 English"

    override val btnBackToMenu = "← Wróć do menu"
    override val elementsScreenTitle = "Elementy zegarów"
    override val elementsScreenSubtitle = "Włączaj i wyłączaj kafelki oraz funkcje"
    override val elementsActiveLayoutTitle = "Aktywny układ zegarów w aucie"
    override val elementsActiveLayoutSubtitle = "Wybierz układ wyświetlany na ekranie zegarów Dacii"
    override val layoutCardsOnly = "Tylko Kafelki (Do menu auta)"
    override val layoutFull = "Pełny (Obrotomierz + Kafelki)"
    override val togglePowerBarTitle = "Wielosegmentowy pasek mocy"
    override val togglePowerBarSubtitle = "Silnik spalinowy (pomarańcz), Tył (błękit), HSG (zieleń), Rekuperacja"
    override val toggleTachoInWideTitle = "Obrotomierz w trybie szerokim (2/3 ekranu)"
    override val toggleTachoInWideSubtitle = "Automatycznie dodaje sportowy obrotomierz po rozszerzeniu zegarów w Dacii"
    override val toggle12vTitle = "Akumulator 12V (IBS)"
    override val toggle12vSubtitle = "Napięcie, % naładowania oraz moc DC-DC"
    override val toggle12vWattsTitle = "Tryb 12V: Pobór mocy w watach (W)"
    override val toggle12vWattsSubtitle = "Przełącza kafelek z napięcia i % ze strzałką (np. 14.1V • 85% ↑) na pobór mocy (POBÓR MOCY 12V)"
    override val toggleTorqueSplitTitle = "Podział napędu 4x4"
    override val toggleTorqueSplitSubtitle = "Wektor momentu obrotowego: % Przód vs % Tył"
    override val toggleTempsTitle = "Temperatury podzespołów"
    override val toggleTempsSubtitle = "Płyn chłodzący, olej w skrzyni ATF, silnik tylny, bateria HV"
    override val toggleTripTitle = "Statystyki energii i oszczędności (Trip)"
    override val toggleTripSubtitle = "Zużycie prądu z baterii (kWh), % EV, odzysk energii z rekuperacji i zaoszczędzone paliwo"
    override val toggleGForceTitle = "Przeciążenia G-Force"
    override val toggleGForceSubtitle = "Pomiar z akcelerometru telefonu"
    override val toggleLogObdTitle = "Loguj dane OBD do pliku"
    override val toggleLogObdSubtitle = "Zapisuje surowe komendy i odpowiedzi magistrali CAN do obd_telemetry.log"
    override val toggleExpPidsTitle = "Zbieraj eksperymentalne PID-y"
    override val toggleExpPidsSubtitle = "Odpytuje w tle i rejestruje dodatkowe parametry (szynę paliwa, temperatury wydechu/oleju, kąt kół, moduły HEVC/BMS) w celu analizy"

    override val telemetryTitle = "Telemetria na żywo"
    override val telemetrySubtitle = "Bieżące parametry z magistrali CAN pojazdu"
    override val boostActive = "Dotrysk: AKTYWNY"
    override val boostInactive = "Dotrysk: brak"
    override val gearLabel = "Bieg"
    override val modeLabel = "Tryb"
    override val speedLabel = "Prędkość"
    override val iceRpmLabel = "Obroty ICE"
    override val eMotorLabel = "e-Silnik"
    override val powerOutputLabel = "Moc oddawana"
    override val regenLabel = "Rekuperacja"
    override val torqueSplitLabel = "Podział 4x4"
    override val coolantLabel = "Płyn"
    override val engineOilLabel = "Olej silnika"
    override val gearboxAtfLabel = "Skrzynia ATF"
    override val tripEvLabel = "Wsparcie EV w tripie"
    override val tripRegenLabel = "Odzysk"
    override val tripSavedLabel = "Zysk"
    override val btnResetTrip = "Zeruj licznik podróży (Trip / Odzysk / Zużycie)"

    override val diagTitle = "Diagnostyka OBD"
    override val diagSubtitle = "Podgląd magistrali i zapytań adaptera"
    override fun diagLogActive(sizeKb: Long) = "Logowanie AKTYWNE (rozmiar: $sizeKb KB)"
    override val diagLogDisabled = "Logowanie WYŁĄCZONE"
    override fun diagFileLabel(path: String) = "Plik: $path"
    override val btnRefreshLog = "Odśwież podgląd"
    override val btnClearLog = "Wyczyść log"

    override val calibTitle = "Kalibracja ekranu UI"
    override val calibSubtitle = "Dostosuj wymiary na żywo w aucie"
    override val calibTargetDisplayHeader = "WYBIERZ EKRAN DO KALIBRACJI"
    override val displayCluster = "Zegary (Cluster)"
    override val displayInfotainment = "Ekran środkowy (Infotainment)"
    override val calibLayoutHeader = "WYBIERZ UKŁAD DO KALIBRACJI"
    override fun activeModeOnCluster(mode: String) = "Aktywny tryb na zegarach w aucie: $mode (zmiany suwaków widać natychmiast!)"
    override val previewLiveHeader = "PODGLĄD PODZIAŁU EKRANU NA ŻYWO"
    override fun previewSpeedo(pct: Int) = "Prędkościomierz\nauta ($pct%)"
    override fun previewTacho(pct: Int) = "Obrotomierz\n($pct%)"
    override val previewCards = "Kafelki danych\n(prawe okno)"
    override fun previewMargin(pct: Int) = "Margines\n($pct%)"
    override fun previewFreeSpaceSpeedo(pct: Int) = "Wolne miejsce na prędkościomierz\ni menu auta ($pct%)"
    override val previewCardsRight = "Kafelki danych (prawe okno)"

    override val fullModeSlidersHeader = "SUWAKI DLA TRYBU PEŁNEGO"
    override val cardsOnlySlidersHeader = "SUWAKI DLA TRYBU TYLKO KAFELKI"
    override fun tachoWidthSlider(pct: Int) = "Szerokość lewego okna (Obrotomierz): $pct%"
    override val tachoWidthSliderDesc = "Reguluje jak szeroki jest kafelek z obrotomierzem silnika i biegiem"
    override fun tachoLeftOffsetSlider(pct: Int) = "Pozycja lewego okna (Margines od lewej): $pct%"
    override val tachoLeftOffsetSliderDesc = "Odsunięcie obrotomierza od lewej krawędzi (zostawia miejsce na prędkościomierz auta)"
    override fun rightMarginSlider(pct: Int) = "Margines prawy (ochrona prawej strony): $pct%"
    override val rightMarginSliderDesc = "Zabezpiecza prawą stronę kafelków przed ścięciem przez fizyczną ramkę zegarów lub menu"
    override fun bottomMarginSlider(pct: Int) = "Margines dolny (podniesienie kokpitu): $pct%"
    override val bottomMarginSliderDesc = "Chroni przed zasłonięciem kafelków przez fabryczny dolny pasek LPG/paliwa"
    override fun topMarginSlider(pct: Int) = "Margines górny (odsunięcie od góry): $pct%"
    override val topMarginSliderDesc = "Drobna korekta pionowa położenia całego kokpitu"
    override val btnRestoreDefaultsFull = "Przywróć domyślne dla trybu Pełnego"
    override val btnRestoreDefaultsCardsOnly = "Przywróć domyślne dla Tylko Kafelków"
    override fun cardsOnlyLeftSpaceSlider(pct: Int) = "Szerokość lewego okna (Miejsce na prędkościomierz): $pct%"
    override val cardsOnlyLeftSpaceSliderDesc = "Reguluje szerokość czarnego lewego okna zarezerwowanego dla fabrycznego prędkościomierza i znaków Dacii"
    override val cardsOnlyRightMarginSliderDesc = "Zabezpiecza kafelki przed ścięciem przez fizyczną obudowę zegarów lub boczne menu Dacii"

    override val fontSizeAndColorsHeader = "WIELKOŚĆ CZCIONEK I KOLORYSTKA"
    override fun uiScaleSlider(pct: Int) = "Skala elementów i czcionek: $pct%"
    override val clusterBgColorTitle = "Główne tło zegarów (wtopienie w ramkę)"
    override val clusterBgColorDesc = "Czysta czerń (#000000) idealnie stapia się z fizyczną ramką zegarów w aucie"
    override val presetPureBlack = "Czysta czerń"
    override val presetGraphite = "Grafit"
    override val presetAnthracite = "Antracyt"
    override val presetNavy = "Granat"
    override val cardColorTitle = "Tło kafelków telemetrii (Paleta RGB)"
    override val cardColorDesc = "Dostosuj dowolny kolor tła kafelków suwakami RGB (zmianę widać natychmiast)"
    override val redR = "Czerwony (R)"
    override val greenG = "Zielony (G)"
    override val blueB = "Niebieski (B)"
    override val quickPresets = "Szybkie presety:"
    override val presetGraphiteOem = "Grafit OEM"
    override val presetBlack = "Czerń"
    override val presetNavyBlue = "Granat"
    override val presetOlive = "Oliwka"
    override val presetLight = "Jasny"

    override val infoDisplayModeHeader = "TRYB WYŚWIETLANIA NA EKRANIE ŚRODKOWYM"
    override val infoWithTacho = "Z obrotomierzem"
    override val infoCardsOnly = "Tylko kafelki"
    override val infoAutoCoolwalk = "Auto (Coolwalk)"
    override val infoDescTacho = "Wymuszony widok szeroki z obrotomierzem (działa również przy podzielonym ekranie z YT Music!)"
    override val infoDescCards = "Wymuszony widok samych kafelków (bez obrotomierza)"
    override val infoDescAuto = "Tryb automatyczny: Pełny ekran = obrotomierz, Podzielony = kafelki"
    override val infoPreviewHeader = "PODGLĄD UKŁADU INFOTAINMENT NA ŻYWO"
    override val infoSlidersFullHeader = "SUWAKI DLA PEŁNEGO EKRANU INFOTAINMENT"
    override fun infoLeftMargSlider(pct: Int) = "Margines lewy (start obrotomierza): $pct%"
    override val infoLeftMargSliderDesc = "Odsunięcie obrotomierza od lewej krawędzi ekranu środkowego"
    override fun infoTachoWidthSlider(pct: Int) = "Szerokość obrotomierza: $pct%"
    override val infoTachoWidthSliderDesc = "Szerokość kafelka ze sportowym obrotomierzem"
    override fun infoRightMargSlider(pct: Int) = "Margines prawy: $pct%"
    override val infoRightMargSliderDesc = "Odsunięcie prawej kolumny kafelków od prawej krawędzi"
    override fun infoBottomMargSlider(pct: Int) = "Margines dolny: $pct%"
    override val infoBottomMargSliderDesc = "Odsunięcie od dolnego paska nawigacyjnego Android Auto"
    override fun infoTopMargSlider(pct: Int) = "Margines górny: $pct%"
    override val infoTopMargSliderDesc = "Drobna korekta pionowa położenia całego kokpitu"
    override val btnRestoreDefaultsInfoFull = "Przywróć domyślne dla Pełnego Infotainment"
    override val infoSlidersSplitHeader = "SUWAKI DLA PODZIELONEGO EKRANU (TYLKO KAFELKI)"
    override val infoSplitLeftDesc = "Odsunięcie kafelków od lewej krawędzi okna w trybie podzielonym"
    override val infoSplitRightDesc = "Zabezpiecza przed ścięciem kafelków z prawej strony"
    override val btnRestoreDefaultsInfoSplit = "Przywróć domyślne dla Podzielonego Infotainment"
    override val infoScaleHeader = "SKALA ELEMENTÓW INFOTAINMENT"
    override fun infoScaleSlider(pct: Int) = "Skala elementów i czcionek: $pct%"
    override val btnRestoreDefaultsInfoScale = "Przywróć domyślną skalę (110%)"

    override val btDialogTitle = "Wybierz adapter vLinker"
    override val btDialogNoDevices = "Brak sparowanych urządzeń Bluetooth w telefonie.\n\nNajpierw wejdź w Ustawienia Bluetooth telefonu i sparuj swój vLinker (kod parowania to zwykle 1234)."
    override val btUnknownDevice = "Nieznane urządzenie"
    override val btDialogCancel = "Anuluj"

    override val aaShowTelemetry = "▶ Wyświetl ekran parametrów"
    override val aaShowTelemetryDesc = "Włącz zegary i telemetrię na żywo"
    override val aaStatusPrefix = "STATUS"
    override val aaStatusCanDesc = "Magistrala CAN Dacia Bigster Hybrid-G"
    override val aaMenuBack = "← Menu"

    override val gearLabelShort = "BIEG"
    override val modePrefix = "TRYB: "
    override val frontLabel = "PRZÓD"
    override val rearLabel = "TYŁ"
    override val rpmUnit = "obr/min"
    override val fuelBoostLabel = "DOTRYSK PALIWA"
    override val evBlockLabel = "BLOKADA EV"
    override val oilLabelShort = "OLEJ"
    override val gearboxLabelShort = "SKRZYNIA"
    override val elecMotorLabelShort = "SILNIK EL."
    override val coolantLabelShort = "PŁYN"
    override val bat48vLabel = "BATERIA 48V"
    override val bat12vLabel = "BATERIA 12V"
    override val bat12vWattsLabel = "POBÓR MOCY 12V"
    override val usedBatteryLabel = "ZUŻYTO Z BATERII"
    override val regenRecoveryLabel = "ODZYSK REGEN"
    override val fuelSavedLabel = "ZYSK PALIWA"
    override val regenAwdSub = "rekuperacja e-AWD"
    override val hybridSavingsSub = "oszczędność hybrydy"
    override val powerSuffix = "MOC"
    override val regenSuffix = "REGEN"
    override val stoppedStatus = "POSTÓJ"
    override val charging48vStatus = "ŁADOWANIE BATERII 48V"
    override val reversingStatus = "COFANIE (e-AWD TYŁ)"
    override val reversingIceStatus = "COFANIE (SILNIK SPALINOWY)"
    override val pureEvDriveStatus = "NAPĘD EV TYŁ"
    override val coastingStatus = "ŻEGLOWANIE"
    override val calibPreviewCardsInfoFull = "Kafelki parametrów\n(Moc, Temp, Bateria, Trip)"
    override val calibPreviewCardsInfoSplit = "Kafelki parametrów\n(Bieg, Tryb, 4x4, Baterie)"
    override fun langSwitchDesc(nextName: String) = "Kliknij, aby przełączyć na: $nextName"
    override val toggleHostSafeAreaTitle = "Zegary: obszar bezpieczny Android Auto"
    override val toggleHostSafeAreaSubtitle = "Rysuj tylko w obszarze zgłoszonym przez auto (marginesy % liczone wewnątrz niego). Po włączeniu sprawdź kalibrację."
    override fun modeName(mode: HybridMode): String = when (mode) {
        HybridMode.EV_READY -> "EV / READY"
        HybridMode.STANDSTILL -> "POSTÓJ"
        HybridMode.CHARGING_48V -> "ŁADOWANIE 48V"
        HybridMode.STANDBY -> "STANDBY"
        HybridMode.REGEN -> "REKUPERACJA"
        HybridMode.COASTING -> "ŻEGLOWANIE"
        HybridMode.PURE_EV -> "PURE EV"
        HybridMode.REVERSE -> "COFANIE (e-AWD)"
        HybridMode.REVERSE_ICE -> "COFANIE (ICE)"
        HybridMode.AWD_4X4 -> "4x4 E-AWD"
        HybridMode.EAWD_BOOST -> "E-AWD BOOST"
        HybridMode.HYBRID_EAWD -> "HYBRYDA e-AWD"
        HybridMode.HYBRID_AUTO -> "HYBRYDA AUTO"
    }

    override fun inhibitName(reason: EvInhibit): String = when (reason) {
        EvInhibit.SWITCHED_OFF -> "WYŁĄCZONY"
        EvInhibit.SYSTEM_FAULT -> "USTERKA"
        EvInhibit.SLOPE -> "POCHYLENIE"
        EvInhibit.VEHICLE_MOVING -> "RUCH AUTA"
        EvInhibit.ENERGY_12V_48V -> "ENERGIA 12V/48V"
        EvInhibit.ENGINE_GPF -> "SILNIK / GPF"
        EvInhibit.BRAKE -> "HAMULEC"
        EvInhibit.HOOD -> "MASKA"
        EvInhibit.DOOR -> "DRZWI"
        EvInhibit.SEATBELT -> "PASY"
        EvInhibit.REVERSE_GEAR -> "WSTECZNY"
        EvInhibit.HVAC -> "KLIMA / NAWIEW"
        EvInhibit.STEERING -> "KIEROWNICA"
        EvInhibit.KEYCARD -> "KARTA"
        EvInhibit.SYSTEM -> "SYSTEM"
        EvInhibit.CLUTCH -> "SPRZĘGŁO"
        EvInhibit.PTC_HEATER -> "GRZAŁKA PTC"
        EvInhibit.VEEM_12V -> "ENERGIA 12V (VEEM)"
        EvInhibit.BATTERY_48V -> "BATERIA 48V"
        EvInhibit.POWERTRAIN -> "SILNIK / NAPĘD"
        EvInhibit.COOLING -> "CHŁODZENIE / TERMIKA"
        EvInhibit.SAILING_CLUTCH -> "SPRZĘGŁO / SAILING"
        EvInhibit.BRAKE_ABS -> "HAMULEC / ABS"
        EvInhibit.COLD_ENGINE -> "ZIMNY SILNIK"
    }
}

/**
 * Wersja angielska (EN)
 */
object StringsEn : AppStrings {
    override val languageCode: String = "EN"

    override val appTitle = "Dacia Hybrid-G Telemetry"
    override val appSubtitle = "Android Auto & vLinker Cluster Setup"
    override val connectionStatusLabel = "CONNECTION STATUS"
    override val statusDemo = "Simulation Mode (Demo)"
    override fun statusConnected(deviceName: String) = "Connected: $deviceName"
    override fun statusConnecting(deviceName: String) = "Connecting to $deviceName..."
    override val statusError = "Connection Error (Check OBD power)"
    override val statusDisconnected = "Disconnected (Select adapter)"
    override val btnDisconnect = "Disconnect"
    override val demoModeTitle = "Simulation Mode (Demo)"
    override val demoModeSubtitle = "Generates dynamic telemetry without car connection"
    override val adapterProfileVlinker = "Profile: vLinker MS (Fast STN)"
    override val adapterProfileElm = "Profile: ELM327 (Standard)"
    override val adapterProfileVlinkerDesc = "Active: STPX commands, adaptive timing & 15-25 FPS refresh"
    override val adapterProfileElmDesc = "Active: Classic ELM327 emulation (ATSH header switching)"
    override fun btnChangeDevice(name: String) = "Change vLinker ($name)"
    override val btnSelectDevice = "Select paired vLinker adapter"
    override fun toastConnecting(target: String) = "Connecting to $target..."

    override val sectionModules = "MODULES & SETTINGS"
    override val cardCalibrationTitle = "📐 SCREEN CALIBRATION & UI SLIDERS"
    override val cardCalibrationDesc = "Independent calibration for Instrument Cluster and Center Display (Infotainment - full & split)"
    override val cardElementsTitle = "🎛️ INSTRUMENT CLUSTER ELEMENTS"
    override val cardElementsDesc = "Layout selection (Cards only / Full), power bar, 12V, 4x4 split, temps, trip stats, OBD logging"
    override val cardTelemetryTitle = "⚡ LIVE TELEMETRY PREVIEW"
    override val cardTelemetryDesc = "Motor power in kW, ICE/e-Motor RPM, component temps, 48V and 12V batteries live"
    override val cardDiagnosticsTitle = "📊 OBD DIAGNOSTICS & LOGS"
    override val cardDiagnosticsDesc = "Raw CAN bus command buffer preview, log file size and cleanup"
    override val btnOpen = "Open ➔"

    override val sectionLanguage = "APP LANGUAGE"
    override val languageTitle = "Interface Language"
    override val languageSubtitle = "Changes language on phone app and Android Auto display"
    override val langSystemOption = "📱 Auto (System)"
    override val langPlOption = "🇵🇱 Polski"
    override val langEnOption = "🇬🇧 English"

    override val btnBackToMenu = "← Back to menu"
    override val elementsScreenTitle = "Cluster Elements"
    override val elementsScreenSubtitle = "Toggle tiles and display features"
    override val elementsActiveLayoutTitle = "Active cluster layout in car"
    override val elementsActiveLayoutSubtitle = "Select layout displayed on Dacia cluster display"
    override val layoutCardsOnly = "Cards Only (For car menu)"
    override val layoutFull = "Full (Tachometer + Cards)"
    override val togglePowerBarTitle = "Multi-segment power bar"
    override val togglePowerBarSubtitle = "Combustion engine (orange), Rear (blue), HSG (green), Regen"
    override val toggleTachoInWideTitle = "Tachometer in wide mode (2/3 screen)"
    override val toggleTachoInWideSubtitle = "Automatically adds sport tachometer when expanding cluster in Dacia"
    override val toggle12vTitle = "12V Battery (IBS)"
    override val toggle12vSubtitle = "Voltage, state of charge % and DC-DC power"
    override val toggle12vWattsTitle = "12V Mode: Power draw in watts (W)"
    override val toggle12vWattsSubtitle = "Switches tile from voltage & % with arrow (e.g. 14.1V • 85% ↑) to power draw (12V POWER DRAW)"
    override val toggleTorqueSplitTitle = "4x4 Drive Split"
    override val toggleTorqueSplitSubtitle = "Torque vector: % Front vs % Rear"
    override val toggleTempsTitle = "Component Temperatures"
    override val toggleTempsSubtitle = "Coolant, ATF gearbox oil, rear motor, HV battery"
    override val toggleTripTitle = "Trip & Energy Stats"
    override val toggleTripSubtitle = "Battery energy consumption (kWh), % EV, regen energy recovery and fuel saved"
    override val toggleGForceTitle = "G-Force Acceleration"
    override val toggleGForceSubtitle = "Phone accelerometer measurement"
    override val toggleLogObdTitle = "Log OBD data to file"
    override val toggleLogObdSubtitle = "Saves raw CAN bus commands and responses to obd_telemetry.log"
    override val toggleExpPidsTitle = "Collect experimental PIDs"
    override val toggleExpPidsSubtitle = "Queries and logs extra parameters in background (fuel rail, exhaust/oil temps, wheel angle, HEVC/BMS) for analysis"

    override val telemetryTitle = "Live Telemetry"
    override val telemetrySubtitle = "Real-time parameters from vehicle CAN bus"
    override val boostActive = "Direct injection: ACTIVE"
    override val boostInactive = "Direct injection: off"
    override val gearLabel = "Gear"
    override val modeLabel = "Mode"
    override val speedLabel = "Speed"
    override val iceRpmLabel = "ICE RPM"
    override val eMotorLabel = "e-Motor"
    override val powerOutputLabel = "Power Output"
    override val regenLabel = "Regen"
    override val torqueSplitLabel = "4x4 Split"
    override val coolantLabel = "Coolant"
    override val engineOilLabel = "Engine Oil"
    override val gearboxAtfLabel = "ATF Gearbox"
    override val tripEvLabel = "Trip EV Share"
    override val tripRegenLabel = "Regen"
    override val tripSavedLabel = "Saved"
    override val btnResetTrip = "Reset trip computer (Trip / Regen / Consumption)"

    override val diagTitle = "OBD Diagnostics"
    override val diagSubtitle = "CAN bus commands & adapter query monitor"
    override fun diagLogActive(sizeKb: Long) = "Logging ACTIVE (size: $sizeKb KB)"
    override val diagLogDisabled = "Logging DISABLED"
    override fun diagFileLabel(path: String) = "File: $path"
    override val btnRefreshLog = "Refresh preview"
    override val btnClearLog = "Clear log"

    override val calibTitle = "UI Screen Calibration"
    override val calibSubtitle = "Adjust dimensions live in vehicle"
    override val calibTargetDisplayHeader = "SELECT DISPLAY TO CALIBRATE"
    override val displayCluster = "Cluster (Dashboard)"
    override val displayInfotainment = "Center Display (Infotainment)"
    override val calibLayoutHeader = "SELECT LAYOUT TO CALIBRATE"
    override fun activeModeOnCluster(mode: String) = "Active mode on car cluster: $mode (slider changes apply live!)"
    override val previewLiveHeader = "LIVE SCREEN LAYOUT PREVIEW"
    override fun previewSpeedo(pct: Int) = "Car speedometer\n($pct%)"
    override fun previewTacho(pct: Int) = "Tachometer\n($pct%)"
    override val previewCards = "Data tiles\n(right window)"
    override fun previewMargin(pct: Int) = "Margin\n($pct%)"
    override fun previewFreeSpaceSpeedo(pct: Int) = "Free space for speedometer\n& car menu ($pct%)"
    override val previewCardsRight = "Data tiles (right window)"

    override val fullModeSlidersHeader = "FULL MODE SLIDERS"
    override val cardsOnlySlidersHeader = "CARDS-ONLY MODE SLIDERS"
    override fun tachoWidthSlider(pct: Int) = "Left window width (Tachometer): $pct%"
    override val tachoWidthSliderDesc = "Adjusts width of tile with tachometer and gear"
    override fun tachoLeftOffsetSlider(pct: Int) = "Left window position (Left margin): $pct%"
    override val tachoLeftOffsetSliderDesc = "Tachometer offset from left edge (leaves space for speedometer)"
    override fun rightMarginSlider(pct: Int) = "Right margin (right edge protection): $pct%"
    override val rightMarginSliderDesc = "Protects right side of tiles from clipping by cluster bezel or menu"
    override fun bottomMarginSlider(pct: Int) = "Bottom margin (lift cockpit): $pct%"
    override val bottomMarginSliderDesc = "Protects tiles from overlap by factory LPG/fuel status bar"
    override fun topMarginSlider(pct: Int) = "Top margin (top offset): $pct%"
    override val topMarginSliderDesc = "Fine vertical adjustment of entire cockpit"
    override val btnRestoreDefaultsFull = "Reset to defaults for Full mode"
    override val btnRestoreDefaultsCardsOnly = "Reset to defaults for Cards Only"
    override fun cardsOnlyLeftSpaceSlider(pct: Int) = "Left window width (Space for speedometer): $pct%"
    override val cardsOnlyLeftSpaceSliderDesc = "Adjusts width of black window reserved for OEM speedometer and Dacia signs"
    override val cardsOnlyRightMarginSliderDesc = "Protects tiles from clipping by cluster bezel or Dacia side menu"

    override val fontSizeAndColorsHeader = "FONT SIZE & COLOR PALETTE"
    override fun uiScaleSlider(pct: Int) = "Element & font scale: $pct%"
    override val clusterBgColorTitle = "Cluster background (bezel blend)"
    override val clusterBgColorDesc = "Pure black (#000000) blends seamlessly with car's cluster bezel"
    override val presetPureBlack = "Pure Black"
    override val presetGraphite = "Graphite"
    override val presetAnthracite = "Anthracite"
    override val presetNavy = "Navy"
    override val cardColorTitle = "Telemetry tiles background (RGB Palette)"
    override val cardColorDesc = "Customize tile background color with RGB sliders (changes apply live)"
    override val redR = "Red (R)"
    override val greenG = "Green (G)"
    override val blueB = "Blue (B)"
    override val quickPresets = "Quick presets:"
    override val presetGraphiteOem = "Graphite OEM"
    override val presetBlack = "Black"
    override val presetNavyBlue = "Navy"
    override val presetOlive = "Olive"
    override val presetLight = "Light"

    override val infoDisplayModeHeader = "CENTER DISPLAY VIEW MODE"
    override val infoWithTacho = "With tachometer"
    override val infoCardsOnly = "Cards only"
    override val infoAutoCoolwalk = "Auto (Coolwalk)"
    override val infoDescTacho = "Forced wide view with tachometer (also works in split-screen with YT Music!)"
    override val infoDescCards = "Forced cards-only view (without tachometer)"
    override val infoDescAuto = "Automatic mode: Fullscreen = tachometer, Split-screen = cards only"
    override val infoPreviewHeader = "LIVE INFOTAINMENT LAYOUT PREVIEW"
    override val infoSlidersFullHeader = "FULL INFOTAINMENT SLIDERS"
    override fun infoLeftMargSlider(pct: Int) = "Left margin (tachometer start): $pct%"
    override val infoLeftMargSliderDesc = "Offset of tachometer from center screen left edge"
    override fun infoTachoWidthSlider(pct: Int) = "Tachometer width: $pct%"
    override val infoTachoWidthSliderDesc = "Width of tile with sport tachometer"
    override fun infoRightMargSlider(pct: Int) = "Right margin: $pct%"
    override val infoRightMargSliderDesc = "Offset of right tiles column from right edge"
    override fun infoBottomMargSlider(pct: Int) = "Bottom margin: $pct%"
    override val infoBottomMargSliderDesc = "Offset from bottom Android Auto navigation bar"
    override fun infoTopMargSlider(pct: Int) = "Top margin: $pct%"
    override val infoTopMargSliderDesc = "Fine vertical adjustment of entire cockpit"
    override val btnRestoreDefaultsInfoFull = "Reset to defaults for Full Infotainment"
    override val infoSlidersSplitHeader = "SPLIT-SCREEN SLIDERS (CARDS ONLY)"
    override val infoSplitLeftDesc = "Offset of tiles from left window edge in split mode"
    override val infoSplitRightDesc = "Protects tiles from clipping on right side"
    override val btnRestoreDefaultsInfoSplit = "Reset to defaults for Split Infotainment"
    override val infoScaleHeader = "INFOTAINMENT ELEMENT SCALE"
    override fun infoScaleSlider(pct: Int) = "Element & font scale: $pct%"
    override val btnRestoreDefaultsInfoScale = "Reset to default scale (110%)"

    override val btDialogTitle = "Select vLinker adapter"
    override val btDialogNoDevices = "No paired Bluetooth devices on phone.\n\nFirst go to Phone Bluetooth Settings and pair your vLinker (pairing PIN is usually 1234)."
    override val btUnknownDevice = "Unknown device"
    override val btDialogCancel = "Cancel"

    override val aaShowTelemetry = "▶ Open telemetry screen"
    override val aaShowTelemetryDesc = "Launch gauges & live telemetry"
    override val aaStatusPrefix = "STATUS"
    override val aaStatusCanDesc = "CAN Bus Dacia Bigster Hybrid-G"
    override val aaMenuBack = "← Menu"

    override val gearLabelShort = "GEAR"
    override val modePrefix = "MODE: "
    override val frontLabel = "FRONT"
    override val rearLabel = "REAR"
    override val rpmUnit = "RPM"
    override val fuelBoostLabel = "FUEL BOOST"
    override val evBlockLabel = "EV INHIBIT"
    override val oilLabelShort = "OIL"
    override val gearboxLabelShort = "GEARBOX"
    override val elecMotorLabelShort = "E-MOTOR"
    override val coolantLabelShort = "COOLANT"
    override val bat48vLabel = "48V BATTERY"
    override val bat12vLabel = "12V BATTERY"
    override val bat12vWattsLabel = "12V POWER DRAW"
    override val usedBatteryLabel = "BATTERY USED"
    override val regenRecoveryLabel = "REGEN RECOVERY"
    override val fuelSavedLabel = "FUEL SAVED"
    override val regenAwdSub = "e-AWD recuperation"
    override val hybridSavingsSub = "hybrid efficiency"
    override val powerSuffix = "POWER"
    override val regenSuffix = "REGEN"
    override val stoppedStatus = "STOPPED"
    override val charging48vStatus = "CHARGING 48V BATTERY"
    override val reversingStatus = "REVERSING (e-AWD REAR)"
    override val reversingIceStatus = "REVERSING (ENGINE)"
    override val pureEvDriveStatus = "EV REAR DRIVE"
    override val coastingStatus = "COASTING"
    override val calibPreviewCardsInfoFull = "Telemetry tiles\n(Power, Temp, Bat, Trip)"
    override val calibPreviewCardsInfoSplit = "Telemetry tiles\n(Gear, Mode, 4x4, Batteries)"
    override fun langSwitchDesc(nextName: String) = "Tap to switch to: $nextName"
    override val toggleHostSafeAreaTitle = "Cluster: Android Auto safe area"
    override val toggleHostSafeAreaSubtitle = "Draw only inside the area reported by the car (% margins apply within it). Re-check calibration after enabling."

    override fun modeName(mode: HybridMode): String = when (mode) {
        HybridMode.EV_READY -> "EV / READY"
        HybridMode.STANDSTILL -> "STOPPED"
        HybridMode.CHARGING_48V -> "CHARGING 48V"
        HybridMode.STANDBY -> "STANDBY"
        HybridMode.REGEN -> "RECUPERATION"
        HybridMode.COASTING -> "COASTING"
        HybridMode.PURE_EV -> "PURE EV"
        HybridMode.REVERSE -> "REVERSING (e-AWD)"
        HybridMode.REVERSE_ICE -> "REVERSING (ICE)"
        HybridMode.AWD_4X4 -> "4x4 E-AWD"
        HybridMode.EAWD_BOOST -> "E-AWD BOOST"
        HybridMode.HYBRID_EAWD -> "HYBRID e-AWD"
        HybridMode.HYBRID_AUTO -> "HYBRID AUTO"
    }

    override fun inhibitName(reason: EvInhibit): String = when (reason) {
        EvInhibit.SWITCHED_OFF -> "SWITCHED OFF"
        EvInhibit.SYSTEM_FAULT -> "SYSTEM FAULT"
        EvInhibit.SLOPE -> "SLOPE"
        EvInhibit.VEHICLE_MOVING -> "VEHICLE MOVING"
        EvInhibit.ENERGY_12V_48V -> "12V/48V ENERGY"
        EvInhibit.ENGINE_GPF -> "ENGINE / GPF"
        EvInhibit.BRAKE -> "BRAKE PEDAL"
        EvInhibit.HOOD -> "HOOD OPEN"
        EvInhibit.DOOR -> "DOOR OPEN"
        EvInhibit.SEATBELT -> "SEATBELT"
        EvInhibit.REVERSE_GEAR -> "REVERSE GEAR"
        EvInhibit.HVAC -> "A/C / BLOWER"
        EvInhibit.STEERING -> "STEERING ANGLE"
        EvInhibit.KEYCARD -> "KEY CARD"
        EvInhibit.SYSTEM -> "SYSTEM"
        EvInhibit.CLUTCH -> "CLUTCH"
        EvInhibit.PTC_HEATER -> "PTC HEATER"
        EvInhibit.VEEM_12V -> "12V ENERGY (VEEM)"
        EvInhibit.BATTERY_48V -> "48V BATTERY"
        EvInhibit.POWERTRAIN -> "ENGINE / POWERTRAIN"
        EvInhibit.COOLING -> "COOLING / THERMAL"
        EvInhibit.SAILING_CLUTCH -> "SAILING CLUTCH"
        EvInhibit.BRAKE_ABS -> "BRAKE / ABS"
        EvInhibit.COLD_ENGINE -> "COLD ENGINE"
    }
}

/**
 * Menedżer rozwiązywania ciągów tekstowych.
 * Zwraca instancję na podstawie konfiguracji lub języka telefonu.
 */
object StringsManager {
    fun get(configLanguage: String?): AppStrings {
        return when (AppLanguage.fromCode(configLanguage)) {
            AppLanguage.PL -> StringsPl
            AppLanguage.EN -> StringsEn
            AppLanguage.SYSTEM -> {
                val sysLang = Locale.getDefault().language.lowercase()
                if (sysLang == "pl") StringsPl else StringsEn
            }
        }
    }
}
