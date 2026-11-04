package com.dacia.hybridcluster.engine

/**
 * Całkowanie energii podróży.
 *
 * - Zegar jest wstrzykiwany (symulacja na logach przewija czas szybciej niż rzeczywisty).
 * - Stan można zapisać ([snapshot]) i odtworzyć ([restore]), dzięki czemu trip przeżywa
 *   zerwanie Bluetooth i restart procesu. Nowy trip zaczyna się dopiero po [NEW_TRIP_GAP_MS]
 *   bez danych (np. następnego dnia) albo po ręcznym resecie.
 */
class TripEnergyTracker(private val clock: () -> Long = System::currentTimeMillis) {

    data class Snapshot(
        val elecJoules: Double = 0.0,
        val regenJoules: Double = 0.0,
        val fuelLiters: Double = 0.0,
        val distanceMeters: Double = 0.0,
        val evDistanceMeters: Double = 0.0,
        val lastActivityMs: Long = 0L
    )

    var totalElecEnergyJoules = 0.0      // [J] energia pobrana z baterii
        private set
    var totalRegenEnergyJoules = 0.0     // [J] odzyskana z hamowania
        private set
    var totalFuelLiters = 0.0            // [L] szacowane zużycie paliwa
        private set
    var totalDistanceMeters = 0.0
        private set
    var evDistanceMeters = 0.0
        private set

    private var lastTimestamp = 0L
    /** Czas ostatniego kroku całkowania – służy do decyzji o automatycznym nowym tripie. */
    var lastActivityMs = 0L
        private set

    fun reset() {
        totalElecEnergyJoules = 0.0
        totalRegenEnergyJoules = 0.0
        totalFuelLiters = 0.0
        totalDistanceMeters = 0.0
        evDistanceMeters = 0.0
        lastTimestamp = 0L
        lastActivityMs = 0L
    }

    /**
     * Wywoływane przy (ponownym) połączeniu z adapterem. Zamiast bezwarunkowego resetu
     * (stare zachowanie – każda przerwa BT kasowała trip) kontynuuje trip, chyba że od
     * ostatnich danych minęło więcej niż [NEW_TRIP_GAP_MS].
     */
    fun onConnectionStarted() {
        lastTimestamp = 0L // nie całkuj przez przerwę w połączeniu
        val now = clock()
        if (lastActivityMs != 0L && now - lastActivityMs > NEW_TRIP_GAP_MS) reset()
    }

    fun snapshot() = Snapshot(
        totalElecEnergyJoules, totalRegenEnergyJoules, totalFuelLiters,
        totalDistanceMeters, evDistanceMeters, lastActivityMs
    )

    fun restore(s: Snapshot) {
        totalElecEnergyJoules = s.elecJoules
        totalRegenEnergyJoules = s.regenJoules
        totalFuelLiters = s.fuelLiters
        totalDistanceMeters = s.distanceMeters
        evDistanceMeters = s.evDistanceMeters
        lastActivityMs = s.lastActivityMs
        lastTimestamp = 0L
    }

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
        // Bardzo krótki krok (< 5 ms) – nie przesuwamy znacznika, czas zostanie doliczony w kolejnym kroku
        if (elapsedMs < 5L) return
        lastTimestamp = now

        // Skok czasu (uśpienie, przerwa w połączeniu) – pomijamy krok
        if (elapsedMs > 1500L) return
        val dt = elapsedMs / 1000.0
        lastActivityMs = now

        val validVoltage = voltage.coerceIn(35.0, 56.0)
        val validCurrent = current.coerceIn(-250.0, 250.0)
        val effectiveCurrent = if (Math.abs(validCurrent) < 0.25) 0.0 else validCurrent

        // Rekuperacja kinetyczna – wyłącznie w ruchu
        if (speedKmH > 3.0 && isRegenActive && regenPowerKw > 0.05) {
            val regenWatts = (regenPowerKw * 1000.0).coerceIn(0.0, 30000.0)
            totalRegenEnergyJoules += regenWatts * dt
        }

        // Energia pobrana z baterii (BMS Sunwoda: prąd ujemny = rozładowanie)
        val dischargeAmps = (-effectiveCurrent).coerceAtLeast(0.0)
        val driveWatts = when {
            dischargeAmps > 0.25 -> (validVoltage * dischargeAmps).coerceIn(0.0, 35000.0)
            rearMotorPowerKw > 0.02 -> (rearMotorPowerKw * 1000.0).coerceIn(0.0, 35000.0)
            else -> 0.0
        }
        if (driveWatts > 0.0) totalElecEnergyJoules += driveWatts * dt

        val validFuelRate = fuelRateLh.coerceIn(0.0, 30.0)
        totalFuelLiters += (validFuelRate / 3600.0) * dt

        val validSpeed = speedKmH.coerceIn(0.0, 220.0)
        val distanceThisStep = (validSpeed / 3.6) * dt
        totalDistanceMeters += distanceThisStep
        if (iceRpm == 0 && validSpeed > 1.0) evDistanceMeters += distanceThisStep
    }

    /**
     * Udział energii elektrycznej w pracy napędu. Paliwo przeliczane na pracę na wale tą samą
     * stałą co szacowanie spalania ([FuelModel.LITERS_PER_SHAFT_KWH]) – wcześniej były tu dwie
     * różne stałe (3.2 kWh/L oraz 0.28 L/kWh ≈ 3.57 kWh/L).
     */
    fun getEvEnergySharePercent(): Double {
        val iceEnergyJoules = (totalFuelLiters / FuelModel.LITERS_PER_SHAFT_KWH) * 3_600_000.0
        val totalWork = iceEnergyJoules + totalElecEnergyJoules
        return if (totalWork > 0) (totalElecEnergyJoules / totalWork) * 100.0 else 0.0
    }

    fun getFuelSavedLiters(): Double = (getRegenKWh() * FuelModel.LITERS_PER_SHAFT_KWH).coerceAtLeast(0.0)

    fun getEvDistancePercent(): Double =
        if (totalDistanceMeters > 0) (evDistanceMeters / totalDistanceMeters) * 100.0 else 0.0

    fun getDistanceKm(): Double = totalDistanceMeters / 1000.0
    fun getRegenKWh(): Double = totalRegenEnergyJoules / 3_600_000.0
    fun getUsedElecKWh(): Double = totalElecEnergyJoules / 3_600_000.0

    companion object {
        /** Po takiej przerwie bez danych (4 h) następne połączenie zaczyna nowy trip. */
        const val NEW_TRIP_GAP_MS = 4L * 60 * 60 * 1000
    }
}

/** Jeden model spalania używany w całej aplikacji (1.2 TCe 48V, ~210 g/kWh). */
object FuelModel {
    const val LITERS_PER_SHAFT_KWH = 0.28
    const val MIN_RUNNING_RATE_LH = 0.8

    fun fuelRateLh(shaftKw: Double): Double =
        if (shaftKw > 0) (shaftKw * LITERS_PER_SHAFT_KWH).coerceAtLeast(MIN_RUNNING_RATE_LH) else 0.0
}
