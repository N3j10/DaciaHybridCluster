package com.dacia.hybridcluster.replay

// Kopia TripEnergyTracker sprzed refaktoru (z wstrzykniętym zegarem) – kod referencyjny.

class LegacyTripEnergyTracker(private val clock: () -> Long) {
    var totalElecEnergyJoules = 0.0      // [J] energia pobrana z baterii na przyspieszanie
    var totalRegenEnergyJoules = 0.0     // [J] odzyskana z hamowania
    var totalFuelLiters = 0.0            // [L] zużyte paliwo
    var totalDistanceMeters = 0.0
    var evDistanceMeters = 0.0

    private var lastTimestamp = 0L

    fun reset() {
        totalElecEnergyJoules = 0.0
        totalRegenEnergyJoules = 0.0
        totalFuelLiters = 0.0
        totalDistanceMeters = 0.0
        evDistanceMeters = 0.0
        lastTimestamp = 0L
    }

    fun setFactoryChargedKWh(kwh: Double) {}
    fun setFactoryDischargedKWh(kwh: Double) {}

    /**
     * Całkowanie numeryczne co krok czasowy dt z twardymi zabezpieczeniami fizycznymi.
     */
    fun update(
        voltage: Double,
        current: Double,
        speedKmH: Double,
        iceRpm: Int,
        fuelRateLh: Double,
        isRegenActive: Boolean = false,
        regenPowerKw: Double = 0.0,
        rearMotorPowerKw: Double = 0.0
    ) {
        val now = clock()
        if (lastTimestamp == 0L) {
            lastTimestamp = now
            return
        }
        val elapsedMs = now - lastTimestamp
        lastTimestamp = now

        // Zabezpieczenie przed skokami czasu (np. uśpienie aplikacji w tle, zerwanie połączenia OBD):
        // Jeśli minęło więcej niż 1.5 sekundy, ignorujemy ten krok i czekamy na ciągłą transmisję.
        if (elapsedMs > 1500L || elapsedMs < 5L) {
            return
        }
        val dt = elapsedMs / 1000.0

        // 1. Walidacja fizyczna parametrów elektrycznych:
        // Bateria 48V w Dacii ma napięcie robocze 35..56V i maks. prąd ok. 200..250A.
        val validVoltage = voltage.coerceIn(35.0, 56.0)
        val validCurrent = current.coerceIn(-250.0, 250.0)

        // Strefa martwa dla prądu w spoczynku: ignorujemy szum czujnika bocznikowego poniżej 0.25A
        val effectiveCurrent = if (Math.abs(validCurrent) < 0.25) 0.0 else validCurrent

        // 2. REKUPERACJA KINETYCZNA (ODZYSK REGEN):
        // Wyłącznie energia kinetyczna z toczenia i hamowania poruszającego się auta (speed > 3.0 km/h).
        // Ładowanie na postoju lub ładowanie baterii 48V z silnika spalinowego (HSG) kategorycznie NIE JEST rekuperacją!
        if (speedKmH > 3.0 && isRegenActive && regenPowerKw > 0.05) {
            val regenWatts = (regenPowerKw * 1000.0).coerceIn(0.0, 30000.0)
            totalRegenEnergyJoules += regenWatts * dt
        }

        // 3. ENERGIA POBRANA Z BATERII (ZUŻYTO Z BATERII):
        // Pobór mocy na napęd EV, wspomaganie e-AWD oraz instalację pokładową.
        // W Sunwoda BMS prąd ujemny oznacza rozładowanie (prąd płynie z baterii).
        val dischargeAmps = (-effectiveCurrent).coerceAtLeast(0.0)
        val driveWatts = when {
            dischargeAmps > 0.25 -> (validVoltage * dischargeAmps).coerceIn(0.0, 35000.0)
            rearMotorPowerKw > 0.02 -> (rearMotorPowerKw * 1000.0).coerceIn(0.0, 35000.0)
            else -> 0.0
        }
        if (driveWatts > 0.0) {
            totalElecEnergyJoules += driveWatts * dt
        }

        // 4. Zużyte paliwo (L/h -> L)
        val validFuelRate = fuelRateLh.coerceIn(0.0, 30.0)
        val litersThisStep = (validFuelRate / 3600.0) * dt
        totalFuelLiters += litersThisStep

        // 5. Dystans całkowity oraz bezemisyjny (km/h -> m)
        val validSpeed = speedKmH.coerceIn(0.0, 220.0)
        val distanceThisStep = (validSpeed / 3.6) * dt
        totalDistanceMeters += distanceThisStep
        if (iceRpm == 0 && validSpeed > 1.0) {
            evDistanceMeters += distanceThisStep
        }
    }

    fun getEvEnergySharePercent(): Double {
        val iceEnergyJoules = totalFuelLiters * 3.2 * 3_600_000.0
        val totalWork = iceEnergyJoules + totalElecEnergyJoules
        return if (totalWork > 0) (totalElecEnergyJoules / totalWork) * 100.0 else 0.0
    }

    fun getFuelSavedLiters(): Double {
        // Każda 1 kWh energii odzyskanej z rekuperacji i oddanej na koła (sprawność ~90%)
        // zastępuje ok. 0.28 L paliwa w silniku 1.2 TCe 48V (spójne z jednostkowym zużyciem 0.28 L/kWh)
        val regenKWh = getRegenKWh()
        return (regenKWh * 0.28).coerceAtLeast(0.0)
    }

    fun getEvDistancePercent(): Double {
        return if (totalDistanceMeters > 0) (evDistanceMeters / totalDistanceMeters) * 100.0 else 0.0
    }

    fun getDistanceKm(): Double = totalDistanceMeters / 1000.0

    fun getRegenKWh(): Double = totalRegenEnergyJoules / 3_600_000.0

    fun getUsedElecKWh(): Double = totalElecEnergyJoules / 3_600_000.0
}
