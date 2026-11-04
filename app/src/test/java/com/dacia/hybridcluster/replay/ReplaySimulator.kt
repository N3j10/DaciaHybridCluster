package com.dacia.hybridcluster.replay

import com.dacia.hybridcluster.engine.PowertrainCalculator
import com.dacia.hybridcluster.engine.TripEnergyTracker
import com.dacia.hybridcluster.model.HybridMode
import com.dacia.hybridcluster.model.StringsPl
import com.dacia.hybridcluster.model.TelemetryData
import com.dacia.hybridcluster.obd.ObdLog
import com.dacia.hybridcluster.obd.ObdProtocol
import com.dacia.hybridcluster.obd.PidPoller
import java.io.File

/** Jeden cykl odpytywania w symulacji: nowy silnik obliczeń i (opcjonalnie) kod sprzed refaktoru. */
class CycleResult(val session: Int, val timeMs: Long, val new: TelemetryData, val legacy: LegacyTelemetryData?)

class SimulationResult(
    val log: ParsedLog,
    val cycles: List<CycleResult>,
    val newQueries: Int,
    val legacyQueries: Int,
    val computeNanos: Long
)

object ReplaySimulator {

    /**
     * Odtwarza log przez dokładnie ten sam kod co w aplikacji (ObdProtocol -> PidPoller -> PowertrainCalculator)
     * oraz równolegle przez kod referencyjny sprzed refaktoru ([LegacyEngine]).
     * Trip w nowym kodzie jest jeden dla całego logu (tak jak w aplikacji po poprawce),
     * w starym – zerowany przy każdym połączeniu.
     *
     * [legacyRetryPolicy] = true włącza w nowym kodzie starą politykę ponawiania nieudanych PID-ów –
     * wtedy oba silniki muszą dać identyczne wyniki na KAŻDYM logu (dowód, że refaktor nie zmienił fizyki).
     */
    fun run(log: ParsedLog, withLegacy: Boolean = true, legacyRetryPolicy: Boolean = false): SimulationResult {
        val clock = SimClock()
        val trip = TripEnergyTracker(clock::read)
        val calculator = PowertrainCalculator(trip, clock::read)
        val out = ArrayList<CycleResult>(log.totalCycles)
        var newQueries = 0
        var legacyQueries = 0
        var computeNanos = 0L

        for (session in log.sessions) {
            // --- nowy kod ---
            val newResults = ArrayList<Pair<Long, TelemetryData>>(session.anchors.size)
            val link = ReplayElmLink(session, clock)
            val proto = ObdProtocol(link, ObdLog.None, { true }, legacyRetryPolicy, clock::read)
            val poller = PidPoller(proto)
            clock.now = session.startMs
            proto.initialize()
            trip.onConnectionStarted()
            calculator.reset()
            poller.resetReadings()
            poller.detectCapabilities(ObdLog.None)
            try {
                while (true) {
                    poller.pollCycle(PidPoller.Options(showTemperatures = true, show12vBattery = true))
                    val t0 = System.nanoTime()
                    val td = calculator.compute(poller.raw)
                    computeNanos += System.nanoTime() - t0
                    newResults.add(clock.now to td)
                }
            } catch (_: EndOfSession) {
            }
            newQueries += link.queries

            // --- kod sprzed refaktoru ---
            val legacyResults = ArrayList<LegacyTelemetryData>()
            if (withLegacy) {
                val lclock = SimClock()
                val llink = ReplayElmLink(session, lclock)
                lclock.now = session.startMs
                val engine = LegacyEngine(llink, lclock::read)
                engine.initialize()
                try {
                    while (true) legacyResults.add(engine.cycle())
                } catch (_: EndOfSession) {
                }
                legacyQueries += llink.queries
            }
            newResults.forEachIndexed { i, (time, td) ->
                out.add(CycleResult(session.index, time, td, legacyResults.getOrNull(i)))
            }
        }
        return SimulationResult(log, out, newQueries, legacyQueries, computeNanos)
    }

    /** Odwzorowanie trybu z enuma na napis używany przez stary kod. */
    fun legacyModeName(m: HybridMode): String = when (m) {
        HybridMode.EV_READY -> "EV / READY"
        HybridMode.STANDSTILL -> "POSTÓJ"
        HybridMode.CHARGING_48V -> "ŁADOWANIE 48V"
        HybridMode.STANDBY -> "STANDBY"
        HybridMode.REGEN -> "REKUPERACJA"
        HybridMode.COASTING -> "ŻEGLOWANIE"
        HybridMode.PURE_EV -> "PURE EV"
        HybridMode.REVERSE -> "COFANIE (e-AWD)"
        HybridMode.AWD_4X4 -> "4x4 E-AWD"
        HybridMode.EAWD_BOOST -> "E-AWD BOOST"
        HybridMode.HYBRID_EAWD -> "HYBRYDA e-AWD"
        HybridMode.HYBRID_AUTO -> "HYBRYDA AUTO"
    }

    /**
     * Porównanie pól, które MUSZĄ być identyczne (cała fizyka, dekodowanie, tryby, blokady EV).
     * Trip i g-force są pomijane – tam zmiana zachowania jest zamierzona.
     * Zwraca listę nazw pól, które się różnią.
     */
    fun diff(n: TelemetryData, o: LegacyTelemetryData): List<String> {
        val d = ArrayList<String>()
        fun num(name: String, a: Double, b: Double) { if (Math.abs(a - b) > 1e-9 && !(a.isNaN() && b.isNaN())) d.add(name) }
        fun eq(name: String, a: Any?, b: Any?) { if (a != b) d.add(name) }
        num("speedKmH", n.speedKmH, o.speedKmH)
        eq("currentGear", n.currentGear, o.currentGear)
        eq("eGear", n.eGear, o.eGear)
        eq("iceRpm", n.iceRpm, o.iceRpm)
        num("icePowerKw", n.icePowerKw, o.icePowerKw)
        num("rearMotorPowerKw", n.rearMotorPowerKw, o.rearMotorPowerKw)
        num("hsgPowerKw", n.hsgPowerKw, o.hsgPowerKw)
        num("totalOutputPowerKw", n.totalOutputPowerKw, o.totalOutputPowerKw)
        num("totalRegenPowerKw", n.totalRegenPowerKw, o.totalRegenPowerKw)
        num("rearRegenPowerKw", n.rearRegenPowerKw, o.rearRegenPowerKw)
        num("iceTorqueNm", n.iceTorqueNm, o.iceTorqueNm)
        num("rearMotorTorqueNm", n.rearMotorTorqueNm, o.rearMotorTorqueNm)
        eq("rearMotorRpm", n.rearMotorRpm, o.rearMotorRpm)
        num("hsgLoadPercent", n.hsgLoadPercent, o.hsgLoadPercent)
        num("hvBatterySocPercent", n.hvBatterySocPercent, o.hvBatterySocPercent)
        num("hvBatteryVoltage", n.hvBatteryVoltage, o.hvBatteryVoltage)
        num("hvBatteryCurrent", n.hvBatteryCurrent, o.hvBatteryCurrent)
        num("bat12vVoltage", n.bat12vVoltage, o.bat12vVoltage)
        num("bat12vCurrent", n.bat12vCurrent, o.bat12vCurrent)
        eq("bat12vSocPercent", n.bat12vSocPercent, o.bat12vSocPercent)
        num("bat12vPowerWatts", n.bat12vPowerWatts, o.bat12vPowerWatts)
        num("coolantTempC", n.coolantTempC, o.coolantTempC)
        num("oilTempC", n.oilTempC, o.oilTempC)
        num("gearboxTempC", n.gearboxTempC, o.gearboxTempC)
        num("rearMotorTempC", n.rearMotorTempC, o.rearMotorTempC)
        eq("fuelInjectionBoostActive", n.fuelInjectionBoostActive, o.fuelInjectionBoostActive)
        eq("evInhibits", n.evInhibits.joinToString("\n") { StringsPl.inhibitLine(it) }, o.startStopInhibitReason)
        eq("frontTorquePercent", n.frontTorquePercent, o.frontTorquePercent)
        eq("rearTorquePercent", n.rearTorquePercent, o.rearTorquePercent)
        eq("hybridMode", legacyModeName(n.hybridMode), o.hybridMode)
        return d
    }

    /** Katalog z logami: zmienna środowiskowa DACIA_LOG_DIR albo ../logs względem modułu app. */
    fun findLogDir(): File? {
        val candidates = listOfNotNull(
            System.getenv("DACIA_LOG_DIR")?.let { File(it) },
            File("../logs"), File("logs"), File("..")
        )
        return candidates.firstOrNull { d -> d.isDirectory && (d.listFiles()?.any { it.name.endsWith(".log") } == true) }
    }
}
