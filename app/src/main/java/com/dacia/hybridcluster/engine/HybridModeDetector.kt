package com.dacia.hybridcluster.engine

object HybridModeDetector {

    /**
     * Szybka maszyna stanów wyliczana w czasie rzeczywistym
     */
    fun detectMode(
        iceRpm: Int,
        icePowerKw: Double,
        rearMotorPowerKw: Double,
        batteryPowerKw: Double,
        speedKmH: Double
    ): String {
        return when {
            // Rekuperacja (hamowanie silnikiem / odzysk energii do baterii)
            batteryPowerKw < -1.0 -> "REKUPERACJA"

            // Silnik spalinowy wyłączony, jazda na baterii
            iceRpm == 0 && speedKmH > 1.0 -> {
                if (rearMotorPowerKw > 0.5) "PURE EV" else "ŻEGLOWANIE"
            }

            // Postój z włączonym silnikiem spalinowym doładowującym baterię
            speedKmH < 1.0 && iceRpm > 0 && batteryPowerKw < 0 -> "ŁADOWANIE POSTÓJ"

            // Ostry start / przyspieszanie z użyciem obu osi (e-AWD Boost)
            icePowerKw > 8.0 && rearMotorPowerKw > 3.0 -> "E-AWD BOOST"

            // Jazda autostradowa lub stała – przód ciągnie, tył rozłączony
            icePowerKw > 5.0 && Math.abs(rearMotorPowerKw) <= 1.0 -> "FWD ICE ONLY"

            // Delikatne manewrowanie / ruszanie tyłem
            rearMotorPowerKw > 2.0 && icePowerKw <= 5.0 -> "TYŁ ELEKTRYK ECO"

            else -> "HYBRYDA AUTO"
        }
    }
}
