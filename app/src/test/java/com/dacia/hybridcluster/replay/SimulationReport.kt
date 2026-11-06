package com.dacia.hybridcluster.replay

import com.dacia.hybridcluster.model.EvInhibit
import com.dacia.hybridcluster.model.HybridMode
import com.dacia.hybridcluster.model.StringsPl
import java.io.File
import java.util.Locale

/**
 * Raport z symulacji na logach z jazdy (uruchamiany ręcznie albo z testu ReplayTest).
 *   kotlin -cp ... com.dacia.hybridcluster.replay.SimulationReportKt <katalog_z_logami>
 */
fun main(args: Array<String>) {
    val dir = args.firstOrNull()?.let { File(it) } ?: ReplaySimulator.findLogDir()
        ?: error("Podaj katalog z plikami *.log")
    val files = dir.listFiles { f -> f.name.endsWith(".log") }!!.sortedBy { it.name }
    println(SimulationReport.render(files.map { SimulationReport.analyze(ParsedLog.parse(it)) }))
}

object SimulationReport {

    class LogStats(
        val name: String,
        val sessions: Int,
        val cycles: Int,
        val durationMin: Double,
        val hz: Double,
        val compared: Int,
        val mismatchCycles: Int,
        val mismatchFields: Map<String, Int>,
        val firstMismatch: String?,
        val modeShare: Map<HybridMode, Double>,
        val inhibitCounts: Map<EvInhibit, Int>,
        val maxIce: Double, val maxRear: Double, val maxHsg: Double, val maxRegen: Double, val maxTotal: Double,
        val gRange: Pair<Double, Double>,
        val sanityErrors: Map<String, Int>,
        val newTrip: String,
        val legacyTripLastSession: String,
        val legacyTripSumSessions: String,
        val queriesNew: Int, val queriesLegacy: Int,
        val usPerCompute: Double,
        val tempsSeen: String
    )

    fun analyze(log: ParsedLog): LogStats {
        val r = ReplaySimulator.run(log)
        val cyc = r.cycles
        val mismatchFields = LinkedHashMap<String, Int>()
        var mismatchCycles = 0
        var compared = 0
        var first: String? = null
        val sanity = LinkedHashMap<String, Int>()
        fun bad(k: String) { sanity[k] = (sanity[k] ?: 0) + 1 }

        for (c in cyc) {
            val o = c.legacy
            if (o != null) {
                compared++
                val d = ReplaySimulator.diff(c.new, o)
                if (d.isNotEmpty()) {
                    mismatchCycles++
                    d.forEach { mismatchFields[it] = (mismatchFields[it] ?: 0) + 1 }
                    if (first == null) first = "sesja ${c.session}, t=${fmtTime(c.timeMs)}: " + d.joinToString { f -> f }
                }
            }
            val n = c.new
            val nums = listOf(n.icePowerKw, n.rearMotorPowerKw, n.hsgPowerKw, n.totalOutputPowerKw, n.totalRegenPowerKw,
                n.rearRegenPowerKw, n.gForce, n.tripRegenKWh, n.tripUsedElecKWh, n.tripDistanceKm, n.tripEvEnergySharePercent)
            if (nums.any { it.isNaN() || it.isInfinite() }) bad("NaN/Inf")
            if (nums.take(6).any { it < -1e-9 }) bad("ujemna moc")
            if (n.frontTorquePercent + n.rearTorquePercent != 100 || n.frontTorquePercent !in 0..100) bad("podział 4x4 ≠ 100%")
            if (n.totalOutputPowerKw > 0.05 && n.totalRegenPowerKw > 0.05) bad("napęd i rekuperacja jednocześnie")
            if (n.hybridMode == HybridMode.HYBRID_AUTO || n.hybridMode == HybridMode.HYBRID_EAWD || n.hybridMode == HybridMode.EAWD_BOOST || n.hybridMode == HybridMode.AWD_4X4 || n.hybridMode == HybridMode.REVERSE_ICE) {
                if (Math.abs(n.totalOutputPowerKw - (n.icePowerKw + n.rearMotorPowerKw + n.hsgPowerKw)) > 1e-6) bad("suma mocy ≠ ICE+tył+HSG")
            }
            if (n.speedKmH < 1.0 && (n.totalOutputPowerKw > 0 || n.totalRegenPowerKw > 0)) bad("moc na postoju")
            if (n.iceRpm == 0 && n.icePowerKw > 0) bad("moc ICE przy 0 obr/min")
            if (Math.abs(n.gForce) > 1.0) bad("|g| > 1")
        }

        // udział trybów liczony czasem
        val modeTime = HashMap<HybridMode, Long>()
        for (i in 0 until cyc.size - 1) {
            if (cyc[i].session != cyc[i + 1].session) continue
            val dt = (cyc[i + 1].timeMs - cyc[i].timeMs).coerceIn(0, 2000)
            modeTime[cyc[i].new.hybridMode] = (modeTime[cyc[i].new.hybridMode] ?: 0) + dt
        }
        val totalT = modeTime.values.sum().coerceAtLeast(1)
        val inhibits = HashMap<EvInhibit, Int>()
        cyc.forEach { c -> c.new.evInhibits.forEach { inhibits[it] = (inhibits[it] ?: 0) + 1 } }

        val last = cyc.lastOrNull()?.new
        val newTrip = if (last == null) "-" else tripStr(last.tripDistanceKm, last.tripEvDistancePercent, last.tripUsedElecKWh, last.tripRegenKWh, last.tripFuelSavedLiters)
        val lastLegacy = cyc.lastOrNull()?.legacy
        val legacyLast = if (lastLegacy == null) "-" else tripStr(lastLegacy.tripDistanceKm, lastLegacy.tripEvDistancePercent, lastLegacy.tripUsedElecKWh, lastLegacy.tripRegenKWh, lastLegacy.tripFuelSavedLiters)
        // suma po sesjach w starym kodzie (ostatnia ramka każdej sesji)
        val perSession = cyc.groupBy { it.session }.values.mapNotNull { it.lastOrNull()?.legacy }
        val legacySum = tripStr(
            perSession.sumOf { it.tripDistanceKm },
            0.0,
            perSession.sumOf { it.tripUsedElecKWh },
            perSession.sumOf { it.tripRegenKWh },
            perSession.sumOf { it.tripFuelSavedLiters }
        ).replace(" (0% EV)", "")

        val dur = log.sessions.sumOf { it.endMs - it.startMs } / 60000.0
        val temps = cyc.lastOrNull()?.new?.let {
            "płyn ${fmt0(it.coolantTempC)}, olej ${fmt0(it.oilTempC)}, skrzynia ${fmt0(it.gearboxTempC)}, eRAD ${fmt0(it.rearMotorTempC)}"
        } ?: "-"

        return LogStats(
            log.name, log.sessions.size, cyc.size, dur, if (dur > 0) cyc.size / (dur * 60) else 0.0,
            compared, mismatchCycles, mismatchFields, first,
            modeTime.mapValues { it.value * 100.0 / totalT },
            inhibits,
            cyc.maxOfOrNull { it.new.icePowerKw } ?: 0.0, cyc.maxOfOrNull { it.new.rearMotorPowerKw } ?: 0.0,
            cyc.maxOfOrNull { it.new.hsgPowerKw } ?: 0.0, cyc.maxOfOrNull { it.new.totalRegenPowerKw } ?: 0.0,
            cyc.maxOfOrNull { it.new.totalOutputPowerKw } ?: 0.0,
            (cyc.minOfOrNull { it.new.gForce } ?: 0.0) to (cyc.maxOfOrNull { it.new.gForce } ?: 0.0),
            sanity, newTrip, legacyLast, legacySum, r.newQueries, r.legacyQueries,
            if (cyc.isNotEmpty()) r.computeNanos / 1000.0 / cyc.size else 0.0,
            temps
        )
    }

    private fun tripStr(km: Double, evPct: Double, used: Double, regen: Double, fuel: Double) =
        String.format(Locale.US, "%.1f km (%.0f%% EV), zużyto %.3f kWh, odzysk %.3f kWh, paliwo +%.2f L", km, evPct, used, regen, fuel)

    private fun fmt0(t: Double) = if (t <= -100) "--" else String.format(Locale.US, "%.0f°C", t)

    private fun fmtTime(ms: Long): String {
        val s = (ms / 1000) % 86400
        return String.format(Locale.US, "%02d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
    }

    fun render(stats: List<LogStats>): String = buildString {
        for (s in stats) {
            appendLine("=== ${s.name} ===")
            appendLine(String.format(Locale.US, "Sesje: %d | cykle: %d | czas: %.1f min | %.1f cykli/s", s.sessions, s.cycles, s.durationMin, s.hz))
            appendLine("Zgodność ze starym kodem: ${s.compared - s.mismatchCycles}/${s.compared} cykli identycznych" +
                (if (s.mismatchCycles > 0) " | różne pola: ${s.mismatchFields}" else ""))
            s.firstMismatch?.let { appendLine("  pierwsza różnica: $it") }
            appendLine("Zapytania do ECU: nowy ${s.queriesNew}, stary ${s.queriesLegacy}")
            appendLine("Tryby (udział czasu): " + s.modeShare.entries.sortedByDescending { it.value }
                .joinToString { "${StringsPl.modeName(it.key)} ${String.format(Locale.US, "%.1f", it.value)}%" })
            appendLine(String.format(Locale.US, "Maks. moc: ICE %.1f kW, tył %.1f kW, HSG %.1f kW, łącznie %.1f kW, rekuperacja %.1f kW",
                s.maxIce, s.maxRear, s.maxHsg, s.maxTotal, s.maxRegen))
            appendLine(String.format(Locale.US, "Przyspieszenie wzdłużne (nowe): %.2f .. %.2f g", s.gRange.first, s.gRange.second))
            if (s.inhibitCounts.isNotEmpty()) appendLine("Blokady EV (liczba cykli): " +
                s.inhibitCounts.entries.sortedByDescending { it.value }.joinToString { "${StringsPl.inhibitName(it.key)} ${it.value}" })
            appendLine("Temperatury na końcu: ${s.tempsSeen}")
            appendLine("Trip NOWY (ciągły przez wszystkie sesje): ${s.newTrip}")
            appendLine("Trip STARY (wyświetlany na końcu, po resetach): ${s.legacyTripLastSession}")
            appendLine("Trip STARY – suma wszystkich sesji: ${s.legacyTripSumSessions}")
            appendLine("Kontrole poprawności: " + if (s.sanityErrors.isEmpty()) "OK (0 naruszeń)" else s.sanityErrors.toString())
            appendLine(String.format(Locale.US, "Czas obliczeń: %.1f µs / cykl", s.usPerCompute))
            appendLine()
        }
    }
}
