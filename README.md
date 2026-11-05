# Dacia Hybrid-G 150 Telemetry & Android Auto Cluster

Aplikacja Android / Android Auto dla samochodów **Dacia Hybrid-G 150 e-AWD** (Duster III / Bigster):

* **Zegary (Cluster Display):** telemetria napędu e-AWD, biegi DW23 i eRAD, wielosegmentowy pasek mocy
  (ICE + tylny e-Motor + HSG + rekuperacja), podział 4x4, temperatury, baterie 48V / 12V, bilans podróży,
  przyczyny blokady trybu EV.
* **Ekran środkowy (Infotainment):** ten sam zestaw kafelków z obrotomierzem (pełny ekran) lub bez (podzielony ekran Coolwalk).
* **Telefon:** konfiguracja w Jetpack Compose (elementy, kalibracja, diagnostyka OBD, podgląd na żywo) i tryb demo.

---

## Architektura

```
com.dacia.hybridcluster/
├── DaciaApp.kt                    # Inicjalizacja przy starcie procesu (także gdy uruchamia go samo Android Auto)
├── MainActivity.kt                # UI telefonu
├── model/
│   ├── TelemetryData.kt           # Model telemetrii (bez pól, których nic nie wypełniało)
│   ├── HybridMode.kt              # Enumy: tryb napędu + przyczyny blokady EV
│   ├── UiConfig.kt                # Konfiguracja zapisywana jako JSON (Gson) + migracja ze starych kluczy
│   └── AppStrings.kt              # PL / EN (także nazwy trybów i blokad)
├── engine/
│   ├── PidDecoders.kt             # Skalowania PID-ów (zweryfikowane z DDT4All ecu_final.zip) + RawReadings
│   ├── PowertrainCalculator.kt    # Bilans mocy, tryby, podział 4x4 – czysta logika, bez I/O
│   └── TripEnergyTracker.kt       # Trip: przeżywa zerwanie BT i restart procesu, nowy po 4 h przerwy
├── obd/
│   ├── ElmLink.kt                 # Interfejs transportu ELM
│   ├── StreamElmLink.kt           # Transport na strumieniach – wątek czytający + kolejka odpowiedzi
│   ├── BluetoothElmLink.kt        # Połączenie SPP z vLinkerem
│   ├── ObdProtocol.kt             # Inicjalizacja STN/ELM, STPX, adresowanie ECU, polityka ponawiania PID-ów
│   ├── PidPoller.kt               # Harmonogram: pętla szybka / średnia / wolna
│   ├── DemoSource.kt              # Dane trybu demo (osobny trip)
│   ├── ObdLogger.kt               # Log w tle z buforem i rotacją (8 MB)
│   └── ObdManager.kt              # Orkiestracja: jedna korutyna na wątku "obd-io"
└── car/
    ├── HybridCarAppService.kt     # CarAppService (NAVIGATION + FEATURE_CLUSTER)
    ├── HybridCarSession.kt        # Ekran środkowy + "trasa-widmo" uruchamiająca klaster
    ├── ClusterCarSession.kt       # Sesja zegarów
    ├── TelemetryRendererBase.kt   # Wspólne sekcje rysowania obu ekranów, rysowanie bez alokacji
    ├── ClusterSurfaceRenderer.kt  # Układ zegarów
    ├── InfotainmentSurfaceRenderer.kt # Układ ekranu środkowego
    └── InfotainmentScreens.kt     # Szablony Android Auto (menu, elementy, podgląd)
```

Sterowniki (29-bit CAN, ATSP7): EMS3253 `18DA15F1`, eRAD `18DAE5F1`, DW23 `18DA18F1`, BMS Sunwoda `18DA92F1`, DCDC `18DADDF1`.

---

## Budowanie

```
build_apk.bat          # APK debug -> DaciaHybridCluster.apk
build_apk.bat test     # najpierw testy JVM i symulacja na logach, potem APK
install_phone.bat      # instalacja przez ADB
```

`build_apk.bat` używa lokalnego Gradle 8.10.2 (zgodnego z AGP 8.7.2). Wrapper (`gradlew`) wskazuje na Gradle 9.6.0
ustawiony przez Android Studio – z AGP 8.7.2 może nie działać.

## Testy i symulacja na danych z auta

Testy JVM (`app/src/test`) nie potrzebują telefonu ani auta:

* `PidDecodersTest`, `ObdProtocolTest`, `StreamElmLinkTest`, `TripEnergyTrackerTest`, `ConfigJsonTest`;
* `ReplayTest` – odtwarza logi `*.log` z katalogu projektu (lub `DACIA_LOG_DIR`) przez wirtualny adapter ELM
  i sprawdza:
  * że nowy kod liczy **dokładnie** to samo co pętla sprzed refaktoru (`LegacyEngine` – kopia starego kodu),
  * fizyczną spójność (brak NaN, ujemnych mocy, napędu i rekuperacji naraz, suma mocy, 4x4 = 100%),
  * że obroty są zawsze aktualne i trip nie spada przy ponownym połączeniu.

Raport z symulacji: uruchom `SimulationReportKt.main` (w Android Studio: zielona strzałka przy `fun main`).

## Uruchomienie w aucie / na DHU

1. Sparuj vLinker w ustawieniach Bluetooth telefonu, wybierz go w aplikacji (wyłącz tryb demo).
2. Podłącz telefon do Android Auto. Zapamiętany adapter łączy się automatycznie – także wtedy,
   gdy aplikacja na telefonie nie była otwierana.
3. Test bez auta: `run_dhu.ps1` (DHU z ekranem zegarów).
