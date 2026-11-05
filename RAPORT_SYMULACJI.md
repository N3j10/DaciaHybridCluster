# Raport: symulacja na danych z auta (5.11.2026)

## Metoda

Sześć logów z jazdy (ok. 108 tys. cykli odpytywania, ~10 godzin z 23 sesji połączenia) odtworzono przez
**wirtualny adapter ELM** (`ReplayElmLink`). Odpowiada on na komendy AT jak STN2120, a na PID-y odpowiedziami
zapisanymi w logu. Każde zapytanie 010C przesuwa symulację do następnego cyklu z logu i ustawia zegar.

Przez ten adapter przechodzi ten sam kod, który działa w aplikacji (`ObdProtocol → PidPoller → PowertrainCalculator`),
a równolegle kopia pętli sprzed refaktoru (`LegacyEngine`). Wyniki są porównywane w każdym cyklu.

## Wyniki

| Kontrola | Wynik |
|---|---|
| Nowy kod a stary kod, przy tej samej polityce ponawiania PID-ów | **identyczne wyniki we wszystkich 108 tys. cykli, na 6 logach** |
| Fizyczna spójność (NaN, ujemne moce, napęd i rekuperacja jednocześnie, suma mocy, 4x4 = 100%, moc na postoju) | **0 naruszeń** |
| Obroty na ekranie = obroty z ramki 010C tego cyklu | **100% cykli** |
| Trip nie spada przy ponownym połączeniu w trakcie jazdy | **OK** |
| Testy jednostkowe (dekodery, protokół, transport, trip, konfiguracja) | **30/30 OK** |

### Zamierzone różnice względem starej wersji

* **Trip.** Stary kod zerował trip przy każdym ponownym połączeniu. W najnowszym logu w ciągu jednego wieczoru było
  7 połączeń w ciągu ok. 4 godzin. Pod koniec tej jazdy stara aplikacja pokazywała **0,1 km**, a przejechane było **58,8 km**.
  Nowy trip jest ciągły i zaczyna się od zera dopiero po ponad 4 h przerwy (np. następnego dnia).
* **Ponawianie PID-ów.** Stary kod po 5 błędach z rzędu ponawiał PID według licznika wspólnego dla wszystkich
  PID-ów. Dotyczyło to także obrotów i prędkości, które mogły się wtedy „zamrozić” (widać to w logach
  `obd_telemetry_drive.log` i `obd_telemetry_latest.log`). Teraz 010C, 010D, moment ICE i eRAD są odpytywane
  zawsze, a pozostałe PID-y są ponawiane co 5 s.
* **Przyspieszenie wzdłużne (g).** Jest liczone z pochodnej prędkości. W logach zakres wynosi −0,28…+0,36 g;
  wcześniej w trybie rzeczywistym było zawsze 0.

### Obserwacje z danych

* Najczęstsza przyczyna blokady EV to **ENERGIA 12V (VEEM)**, flaga 220142. Ma wartość 1 w 68% odczytów
  z najnowszego logu i występuje w 81% cykli z pracującym silnikiem: zwykle to zarządzanie energią 12V nie pozwala zgasić ICE.
  Druga przyczyna to klimatyzacja / nawiew.
* Auto jeździło głównie na LPG (222172 = 1), dotrysk benzyny nie wystąpił.
* Moc ICE jest dostępna tylko w najnowszym logu. Starsze logi pochodzą z wersji bez PID 221039.
* **Niezweryfikowane w symulacji:** temperatura oleju (22100C) i skrzyni (2230C1). Żaden log nie zawiera tych PID-ów,
  bo starsze wersje aplikacji używały innych. Skalowanie obu sprawdzono w DDT4All.

## Weryfikacja PID-ów z bazą DDT4All (ecu_final.zip)

Wszystkie skalowania są zgodne z plikami EMS3253_C033_0400 e4WD, DW23 C1A V1.5, INV_RE_48V_ERAD_RSA06 V2.2,
BMS48V_SUNWODA V9 i DCDC_48V_HR12. Wyjątek to **220104** (prąd DCDC 12V): w DDT pole ma 10 bitów, a kod brał
pełne 16 bitów. W logach górne bity są zawsze zerem, więc wyniki się nie zmieniły, ale dodano maskę `0x3FF`.
Kody 220311 (komunikat Stop&Start) i 222172 (tryb paliwa) zgadzają się z listami wartości w DDT.

## Pełny raport

```
=== obd_telemetry.log ===
Sesje: 1 | cykle: 132 | czas: 1.8 min | 1.2 cykli/s
Zgodność ze starym kodem: 132/132 cykli identycznych
Zapytania do ECU: nowy 681, stary 397
Tryby (udział czasu): POSTÓJ 100.0%
Maks. moc: ICE 0.0 kW, tył 0.0 kW, HSG 0.0 kW, łącznie 0.0 kW, rekuperacja 0.0 kW
Przyspieszenie wzdłużne (nowe): 0.00 .. 0.00 g
Temperatury na końcu: płyn 41°C, olej --, skrzynia --, eRAD --
Trip NOWY (ciągły przez wszystkie sesje): 0.0 km (0% EV), zużyto 0.000 kWh, odzysk 0.000 kWh, paliwo +0.00 L
Trip STARY (wyświetlany na końcu, po resetach): 0.0 km (0% EV), zużyto 0.000 kWh, odzysk 0.000 kWh, paliwo +0.00 L
Trip STARY – suma wszystkich sesji: 0.0 km, zużyto 0.000 kWh, odzysk 0.000 kWh, paliwo +0.00 L
Kontrole poprawności: OK (0 naruszeń)
Czas obliczeń: 22.9 µs / cykl

=== obd_telemetry_drive.log ===
Sesje: 2 | cykle: 1618 | czas: 11.3 min | 2.4 cykli/s
Zgodność ze starym kodem: 311/1618 cykli identycznych | różne pola: {hvBatteryCurrent=1306, coolantTempC=1031, iceRpm=1213, hybridMode=1222, eGear=546, currentGear=1149, hvBatterySocPercent=1247, rearMotorTempC=1235, hvBatteryVoltage=1175, rearMotorRpm=983, speedKmH=945, rearMotorPowerKw=521, totalOutputPowerKw=702, rearMotorTorqueNm=766, frontTorquePercent=960, rearTorquePercent=960, hsgPowerKw=376, totalRegenPowerKw=234, rearRegenPowerKw=165}
  pierwsza różnica: sesja 1: hvBatteryCurrent
Zapytania do ECU: nowy 8775, stary 8625
Tryby (udział czasu): POSTÓJ 31.7%, ŁADOWANIE 48V 18.0%, HYBRYDA AUTO 13.1%, PURE EV 12.3%, REKUPERACJA 7.9%, EV / READY 7.8%, HYBRYDA e-AWD 3.5%, ŻEGLOWANIE 2.8%, COFANIE (e-AWD) 2.6%, 4x4 E-AWD 0.2%
Maks. moc: ICE 0.0 kW, tył 8.6 kW, HSG 8.5 kW, łącznie 11.7 kW, rekuperacja 12.5 kW
Przyspieszenie wzdłużne (nowe): -0.17 .. 0.13 g
Temperatury na końcu: płyn 54°C, olej --, skrzynia --, eRAD 23°C
Trip NOWY (ciągły przez wszystkie sesje): 1.0 km (42% EV), zużyto 0.186 kWh, odzysk 0.062 kWh, paliwo +0.02 L
Trip STARY (wyświetlany na końcu, po resetach): 0.8 km (37% EV), zużyto 0.138 kWh, odzysk 0.047 kWh, paliwo +0.01 L
Trip STARY – suma wszystkich sesji: 0.8 km, zużyto 0.140 kWh, odzysk 0.047 kWh, paliwo +0.01 L
Kontrole poprawności: OK (0 naruszeń)
Czas obliczeń: 12.0 µs / cykl

=== obd_telemetry_evening.log ===
Sesje: 4 | cykle: 7803 | czas: 66.3 min | 2.0 cykli/s
Zgodność ze starym kodem: 7803/7803 cykli identycznych
Zapytania do ECU: nowy 42330, stary 40309
Tryby (udział czasu): REKUPERACJA 29.8%, PURE EV 17.5%, EV / READY 16.9%, HYBRYDA AUTO 9.2%, ŁADOWANIE 48V 7.5%, POSTÓJ 6.6%, ŻEGLOWANIE 5.5%, HYBRYDA e-AWD 5.2%, COFANIE (e-AWD) 1.2%, 4x4 E-AWD 0.7%
Maks. moc: ICE 0.0 kW, tył 14.2 kW, HSG 7.7 kW, łącznie 14.2 kW, rekuperacja 19.0 kW
Przyspieszenie wzdłużne (nowe): -0.26 .. 0.33 g
Temperatury na końcu: płyn 57°C, olej --, skrzynia --, eRAD 35°C
Trip NOWY (ciągły przez wszystkie sesje): 12.2 km (39% EV), zużyto 1.212 kWh, odzysk 1.611 kWh, paliwo +0.45 L
Trip STARY (wyświetlany na końcu, po resetach): 0.2 km (99% EV), zużyto 0.034 kWh, odzysk 0.005 kWh, paliwo +0.00 L
Trip STARY – suma wszystkich sesji: 12.2 km, zużyto 1.212 kWh, odzysk 1.611 kWh, paliwo +0.45 L
Kontrole poprawności: OK (0 naruszeń)
Czas obliczeń: 0.9 µs / cykl

=== obd_telemetry_latest.log ===
Sesje: 2 | cykle: 1373 | czas: 11.2 min | 2.1 cykli/s
Zgodność ze starym kodem: 1283/1373 cykli identycznych | różne pola: {iceRpm=63, bat12vVoltage=90, rearMotorRpm=3}
  pierwsza różnica: sesja 0: iceRpm, bat12vVoltage
Zapytania do ECU: nowy 7353, stary 6703
Tryby (udział czasu): POSTÓJ 49.9%, ŁADOWANIE 48V 48.7%, STANDBY 0.5%, COFANIE (e-AWD) 0.4%, HYBRYDA AUTO 0.3%, EV / READY 0.2%
Maks. moc: ICE 0.0 kW, tył 0.0 kW, HSG 0.0 kW, łącznie 0.0 kW, rekuperacja 0.0 kW
Przyspieszenie wzdłużne (nowe): -0.03 .. 0.02 g
Temperatury na końcu: płyn 53°C, olej --, skrzynia --, eRAD 10°C
Trip NOWY (ciągły przez wszystkie sesje): 0.0 km (0% EV), zużyto 0.047 kWh, odzysk 0.000 kWh, paliwo +0.00 L
Trip STARY (wyświetlany na końcu, po resetach): 0.0 km (0% EV), zużyto 0.047 kWh, odzysk 0.000 kWh, paliwo +0.00 L
Trip STARY – suma wszystkich sesji: 0.0 km, zużyto 0.047 kWh, odzysk 0.000 kWh, paliwo +0.00 L
Kontrole poprawności: OK (0 naruszeń)
Czas obliczeń: 1.0 µs / cykl

=== log_jazda_A.log ===
Sesje: 5 | cykle: 25098 | czas: 131.0 min | 3.2 cykli/s
Zgodność ze starym kodem: 19066/25098 cykli identycznych | różne pola: {rearMotorPowerKw=1953, hsgPowerKw=1688, rearMotorRpm=6029, frontTorquePercent=2700, rearTorquePercent=2700, totalRegenPowerKw=305, rearRegenPowerKw=719, totalOutputPowerKw=448, hybridMode=124, rearMotorTorqueNm=1}
  pierwsza różnica: sesja 1: rearMotorPowerKw, hsgPowerKw, rearMotorRpm
Zapytania do ECU: nowy 135142, stary 123081
Tryby (udział czasu): HYBRYDA AUTO 50.0%, REKUPERACJA 18.9%, HYBRYDA e-AWD 14.7%, POSTÓJ 7.4%, ŁADOWANIE 48V 6.1%, COFANIE (e-AWD) 1.6%, 4x4 E-AWD 1.2%, EV / READY 0.0%
Maks. moc: ICE 0.0 kW, tył 7.7 kW, HSG 11.8 kW, łącznie 11.8 kW, rekuperacja 17.6 kW
Przyspieszenie wzdłużne (nowe): -0.21 .. 0.19 g
Temperatury na końcu: płyn 77°C, olej --, skrzynia --, eRAD 31°C
Trip NOWY (ciągły przez wszystkie sesje): 43.6 km (0% EV), zużyto 1.136 kWh, odzysk 1.582 kWh, paliwo +0.44 L
Trip STARY (wyświetlany na końcu, po resetach): 13.0 km (0% EV), zużyto 0.427 kWh, odzysk 0.543 kWh, paliwo +0.15 L
Trip STARY – suma wszystkich sesji: 43.6 km, zużyto 1.136 kWh, odzysk 1.596 kWh, paliwo +0.45 L
Kontrole poprawności: OK (0 naruszeń)
Czas obliczeń: 0.8 µs / cykl

=== log_jazda_B.log ===
Sesje: 9 | cykle: 71803 | czas: 392.0 min | 3.1 cykli/s
Zgodność ze starym kodem: 71803/71803 cykli identycznych
Zapytania do ECU: nowy 455843, stary 453907
Tryby (udział czasu): HYBRYDA AUTO 39.8%, POSTÓJ 21.6%, REKUPERACJA 11.7%, HYBRYDA e-AWD 8.8%, ŁADOWANIE 48V 6.5%, PURE EV 5.6%, EV / READY 2.4%, ŻEGLOWANIE 2.1%, COFANIE (e-AWD) 0.6%, E-AWD BOOST 0.4%, 4x4 E-AWD 0.4%, STANDBY 0.0%
Maks. moc: ICE 88.9 kW, tył 14.5 kW, HSG 11.9 kW, łącznie 93.9 kW, rekuperacja 18.9 kW
Przyspieszenie wzdłużne (nowe): -0.28 .. 0.36 g
Blokady EV (liczba cykli): ENERGIA 12V (VEEM) 44041, KLIMA / NAWIEW 10451, SILNIK / NAPĘD 198
Temperatury na końcu: płyn 75°C, olej --, skrzynia --, eRAD 40°C
Trip NOWY (ciągły przez wszystkie sesje): 22.0 km (10% EV), zużyto 1.553 kWh, odzysk 1.147 kWh, paliwo +0.32 L
Trip STARY (wyświetlany na końcu, po resetach): 12.5 km (17% EV), zużyto 1.045 kWh, odzysk 0.733 kWh, paliwo +0.21 L
Trip STARY – suma wszystkich sesji: 80.8 km, zużyto 3.599 kWh, odzysk 2.981 kWh, paliwo +0.83 L
Kontrole poprawności: OK (0 naruszeń)
Czas obliczeń: 1.0 µs / cykl
```
