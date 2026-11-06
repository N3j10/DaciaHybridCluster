# Dacia Telemetry 1.1.0

## Instalacja – wymagany KingInstaller

**Aplikację trzeba zainstalować przez [KingInstaller](https://github.com/fcaronte/KingInstaller).**
Android Auto pokazuje tylko aplikacje zainstalowane ze Sklepu Play. Po zwykłej instalacji pliku APK
(np. z menedżera plików) aplikacja zainstaluje się na telefonie, ale **Android Auto jej nie zobaczy**.
KingInstaller instaluje APK tak, jakby pochodził ze Sklepu Play.

1. Pobierz `DaciaHybridCluster-v1.1.0.apk` i skopiuj go na telefon.
2. Zainstaluj [KingInstaller](https://github.com/fcaronte/KingInstaller/releases) na telefonie.
3. W KingInstaller wybierz pobrany plik APK i zainstaluj go.

APK jest podpisany kluczem debug z komputera, na którym go zbudowano.

## Nowości
- **Cofanie**: rozróżnienie cofania silnikiem spalinowym (bieg R skrzyni) i na samym tylnym silniku
  elektrycznym. Nowy tryb „COFANIE (ICE)” z prawdziwą mocą silnika i podziałem przód/tył.
- **Przyspieszenie wzdłużne (g)** liczone z prędkości – wcześniej w aucie zawsze 0.
- Ustawienie „Zegary: obszar bezpieczny Android Auto” (rysowanie w obszarze zgłoszonym przez auto).

## Poprawki
- Zegary działają z zapamiętanym adapterem i kalibracją także wtedy, gdy aplikacja na telefonie
  nie była otwierana (Android Auto uruchamia ją samo).
- Trip nie zeruje się przy każdym zerwaniu Bluetooth ani restarcie aplikacji; nowy trip po 4 h przerwy.
- Obroty i prędkość nie „zamarzają” po serii błędów odczytu.
- Ekran diagnostyki nie czyta już całego logu przy każdej ramce (groziło zawieszeniem telefonu).
- Brak rzekomego SoC 12V (82% / 75%) zgadywanego z napięcia; tryb W pokazuje waty i ampery.
- Nazwy trybów i przyczyn blokady EV poprawnie po angielsku.
- Prąd DC-DC 12V dekodowany wg bazy DDT (pole 10-bitowe).

## Wydajność
- Rysowanie zegarów bez tworzenia obiektów w każdej klatce i tylko przy zmianie danych.
- Komunikacja z vLinkerem bez aktywnego czekania (mniejsze zużycie CPU i baterii), jeden wątek OBD.
- Log OBD zapisywany w tle z buforem i rotacją (8 MB).

## Usunięte
- Nieużywana pogoda wraz z uprawnieniami do lokalizacji i internetu.

---

# Dacia Telemetry 1.1.0 (English)

## Installation – KingInstaller required

**The app must be installed with [KingInstaller](https://github.com/fcaronte/KingInstaller).**
Android Auto only shows apps installed from the Play Store. If you install the APK the usual way
(e.g. from a file manager), the app will install on the phone, but **Android Auto will not see it**.
KingInstaller installs the APK as if it came from the Play Store.

1. Download `DaciaHybridCluster-v1.1.0.apk` and copy it to the phone.
2. Install [KingInstaller](https://github.com/fcaronte/KingInstaller/releases) on the phone.
3. In KingInstaller, pick the downloaded APK and install it.

The APK is signed with the debug key of the computer it was built on.

## New
- **Reversing**: the app now tells apart reversing with the combustion engine (the gearbox's R gear) and
  reversing on the rear electric motor alone. New mode "REVERSING (ICE)" with real engine power and front/rear split.
- **Longitudinal acceleration (g)** calculated from vehicle speed – previously always 0 in the car.
- New setting "Cluster: Android Auto safe area" (draw only inside the area reported by the car).

## Fixes
- The cluster connects to the saved adapter and keeps its calibration even when the phone app
  was never opened (Android Auto starts it on its own).
- The trip no longer resets on every Bluetooth drop or app restart; a new trip starts after a 4-hour break.
- Engine and vehicle speed no longer "freeze" after a series of read errors.
- The diagnostics screen no longer reads the whole log file on every frame (it could freeze the phone).
- No more 12V SoC guessed from voltage (82% / 75%); watts mode now shows watts and amps.
- Drive mode and EV inhibit reason names are properly translated into English.
- 12V DC-DC current decoded according to the DDT database (10-bit field).

## Performance
- The cluster is drawn without allocating objects every frame, and only when data changes.
- Communication with the vLinker without busy waiting (lower CPU and battery use), single OBD thread.
- OBD log written in the background with buffering and rotation (8 MB).

## Removed
- Unused weather feature, together with the location and internet permissions.
