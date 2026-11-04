package com.dacia.hybridcluster.replay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * Symulacja na prawdziwych logach z jazdy (pliki *.log w katalogu projektu lub w DACIA_LOG_DIR).
 * Gdy logów brak, testy są pomijane.
 */
class ReplayTest {

    companion object {
        private var logs: List<ParsedLog> = emptyList()

        @BeforeClass @JvmStatic
        fun load() {
            val dir = ReplaySimulator.findLogDir() ?: return
            logs = dir.listFiles { f: File -> f.name.endsWith(".log") }!!.sortedBy { it.name }
                .map { ParsedLog.parse(it) }
                .filter { it.totalCycles > 0 }
        }
    }

    /**
     * Najważniejszy test refaktoru: przy starej polityce ponawiania PID-ów nowy kod
     * (ObdProtocol + PidPoller + PowertrainCalculator) daje DOKŁADNIE te same wartości co pętla sprzed refaktoru.
     */
    @Test fun refactorIsEquivalentToLegacyCode() {
        assumeTrue("brak logów", logs.isNotEmpty())
        for (log in logs) {
            val r = ReplaySimulator.run(log, withLegacy = true, legacyRetryPolicy = true)
            val bad = r.cycles.firstOrNull { c -> c.legacy != null && ReplaySimulator.diff(c.new, c.legacy).isNotEmpty() }
            assertTrue(
                "${log.name}: różnica w sesji ${bad?.session}: ${bad?.let { ReplaySimulator.diff(it.new, it.legacy!!) }}",
                bad == null
            )
        }
    }

    @Test fun physicsSanityOnAllLogs() {
        assumeTrue("brak logów", logs.isNotEmpty())
        for (log in logs) {
            val stats = SimulationReport.analyze(log)
            assertTrue("${log.name}: ${stats.sanityErrors}", stats.sanityErrors.isEmpty())
        }
    }

    /** Obroty z każdej ramki 010C trafiają na ekran (010C jest PID-em krytycznym – nigdy nie jest pomijany). */
    @Test fun rpmAlwaysFresh() {
        assumeTrue("brak logów", logs.isNotEmpty())
        for (log in logs) {
            val r = ReplaySimulator.run(log, withLegacy = false)
            val anchors = log.sessions.flatMap { it.anchors }
            var valid = 0
            var fresh = 0
            r.cycles.forEachIndexed { i, c ->
                val a = anchors[i].resp.replace(" ", "")
                val idx = a.indexOf("410C")
                if (idx >= 0 && a.length >= idx + 8) {
                    valid++
                    if (c.new.iceRpm == a.substring(idx + 4, idx + 8).toInt(16) / 4) fresh++
                }
            }
            assertEquals("${log.name}: nieaktualne RPM", valid, fresh)
        }
    }

    /** Trip nie jest zerowany przy ponownym połączeniu w trakcie tej samej jazdy. */
    @Test fun tripNeverDropsWithinADay() {
        assumeTrue("brak logów", logs.isNotEmpty())
        for (log in logs) {
            val r = ReplaySimulator.run(log, withLegacy = false)
            var prev = 0.0
            var prevTime = 0L
            for (c in r.cycles) {
                val gapOk = c.timeMs - prevTime > com.dacia.hybridcluster.engine.TripEnergyTracker.NEW_TRIP_GAP_MS
                if (!gapOk) assertTrue("${log.name}: trip spadł z $prev do ${c.new.tripDistanceKm}", c.new.tripDistanceKm >= prev - 1e-9)
                prev = c.new.tripDistanceKm
                prevTime = c.timeMs
            }
        }
    }
}
