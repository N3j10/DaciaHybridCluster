# Dacia Hybrid-G 150 Telemetry & Android Auto Cluster

Aplikacja Android / Android Auto dla samochodów **Dacia Hybrid-G 150 e-AWD** (Duster III / Bigster):
* **Cyfrowe zegary (Cluster Display):** Telemetria napędu e-AWD, bieg, wielosegmentowy pasek mocy (ICE + Tył e-Motor + HSG + Rekuperacja), temperatury skrzyni i silników, 12V IBS, bilans podróży (% EV, odzyskane kWh).
* **Ekran środkowy (Media Nav Live / Infotainment):** Pogoda z Open-Meteo (temperatura, deszcz, radar, wiatr) zamiast mapy.
* **Telefon:** Aplikacja w Jetpack Compose z konfiguracją przełączników (włączanie/wyłączanie poszczególnych wskaźników) oraz wbudowanym **Trybem Symulacji (Demo Mode)**.

---

## Architektura projektu

```
com.dacia.hybridcluster/
├── MainActivity.kt               # Konfiguracja UI telefonu (przełączniki wskaźników, podgląd na żywo, tryb demo)
├── model/
│   ├── TelemetryData.kt          # Pełen model telemetrii napędu hybrydowego
│   └── UiConfig.kt               # Zapis preferencji wyświetlania (SharedPreferences + StateFlow)
├── engine/
│   ├── HybridModeDetector.kt     # Maszyna stanów (PURE EV, E-AWD BOOST, REKUPERACJA, FWD ICE, itd.)
│   └── TripEnergyTracker.kt      # Całkowanie numeryczne energii podróży (wsparcie EV %, odzyskane kWh, zaoszczędzone paliwo)
├── obd/
│   └── ObdManager.kt             # Obsługa vLinkera przez Bluetooth SPP (komendy UDS / Mode 22 i Mode 01) oraz generator symulacji Demo
├── weather/
│   └── WeatherManager.kt         # Klient Open-Meteo REST API (darmowa pogoda bez klucza)
└── car/
    ├── HybridCarAppService.kt    # Usługa CarAppService dla Android Auto (kategoria NAVIGATION)
    ├── HybridCarSession.kt       # Zarządzanie dwoma ekranami i NavigationManager
    ├── WeatherInfotainmentScreen.kt # Ekran centralny: PaneTemplate ze stacją pogodową
    └── ClusterSurfaceRenderer.kt # Ekran zegarów: pętla 30 FPS na Canvas Surface zegarów
```

---

## Jak uruchomić i przetestować?

### 1. Otwarcie w Android Studio
Otwórz folder:
`%USERPROFILE%\.gemini\antigravity\scratch\DaciaHybridCluster`
jako projekt Android w **Android Studio**.

### 2. Testowanie na komputerze bez wychodzenia do auta (Desktop Head Unit - DHU)
Google udostępnia emulator Android Auto (DHU) z symulacją drugiego ekranu (kastomizacji klastra):
1. W telefonie wejdź w: *Ustawienia -> Android Auto -> Wersja* (kliknij 10 razy, by odblokować Opcje Programisty).
2. W menu 3 kropek wybierz: **Uruchom serwer jednostki głównej**.
3. Zezwól na **Nieznane źródła** w opcjach programisty Android Auto.
4. Uruchom DHU na komputerze z włączonym wyświetlaczem klastra (`cluster`).
5. Aplikacja ma domyślnie włączony **Tryb Symulacji (Demo Mode)**, więc od razu zobaczysz ruszające się paski mocy, zmieniające się biegi i statystyki baterii!

### 3. W samochodzie z adapterem vLinker
1. Sparuj vLinker (np. MC+ lub FD+) w ustawieniach Bluetooth telefonu.
2. W aplikacji odznacz przełącznik **Tryb Symulacji (Demo Mode)** i połącz się z vLinkerem.
3. Podłącz telefon do Dacii (przewodowo lub bezprzewodowo przez Android Auto).
4. Na zegarach pojawi się panel telemetrii, a na ekranie centralnym – stacja pogodowa.
