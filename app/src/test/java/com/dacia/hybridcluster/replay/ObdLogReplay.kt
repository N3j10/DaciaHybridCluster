package com.dacia.hybridcluster.replay

import com.dacia.hybridcluster.obd.ElmLink
import java.io.File
import java.io.IOException

/**
 * Odtwarzanie logów OBD zapisanych przez aplikację (ObdLogger) jako "wirtualny adapter ELM".
 *
 * Format linii (obecny i starszy):
 *   [HH:MM:SS.mmm] CMD: 221039 -> RESP: '621039330E' | OK [33,e]
 *   [HH:MM:SS.mmm] CMD STPX h:18DA15F1, d:010C, t:120, r:1 -> '410C0C5A'
 * Granica sesji (nowe połączenie z adapterem): linia "Inicjalizacja OBD".
 */
class ParsedLog(val name: String, val sessions: List<Session>) {
    class Event(val t: Long, val pid: String, val resp: String)

    class Session(val index: Int, val events: List<Event>) {
        /** Kotwice cykli – każde zapytanie 010C (RPM) zaczyna nowy cykl odpytywania. */
        val anchors: List<Event> = events.filter { it.pid == "010C" }
        private val byPid: Map<String, List<Event>> = events.groupBy { it.pid }

        fun eventsFor(pid: String): List<Event> = byPid[pid] ?: emptyList()
        val startMs get() = events.firstOrNull()?.t ?: 0L
        val endMs get() = events.lastOrNull()?.t ?: 0L
    }

    val totalCycles get() = sessions.sumOf { it.anchors.size }

    companion object {
        private val LINE = Regex("""^\[(\d\d):(\d\d):(\d\d)\.(\d{3})] (.*)$""")
        private val CMD_NEW = Regex("""^CMD: ([0-9A-F]+) -> RESP: '([^']*)'""")
        private val CMD_STPX = Regex("""^CMD STPX h:[0-9A-F]+, d:([0-9A-F]+), t:\d+, r:\d+ -> '([^']*)'""")

        fun parse(file: File): ParsedLog {
            val lines = file.readLines(Charsets.UTF_8)
            val useNewFormat = lines.any { it.contains("] CMD: ") }
            val sessions = mutableListOf<MutableList<Event>>()
            var current: MutableList<Event>? = null
            var dayOffset = 0L
            var lastRaw = -1L
            for (line in lines) {
                val m = LINE.find(line) ?: continue
                val (hh, mm, ss, ms, rest) = m.destructured
                var raw = ((hh.toLong() * 60 + mm.toLong()) * 60 + ss.toLong()) * 1000 + ms.toLong()
                if (lastRaw >= 0 && raw + 12 * 3600_000L < lastRaw) dayOffset += 24 * 3600_000L // północ
                lastRaw = raw
                raw += dayOffset

                if (rest.startsWith("Inicjalizacja OBD")) {
                    current = mutableListOf<Event>().also { sessions.add(it) }
                    continue
                }
                val ev = if (useNewFormat) CMD_NEW.find(rest) else CMD_STPX.find(rest)
                if (ev != null) {
                    if (current == null) current = mutableListOf<Event>().also { sessions.add(it) }
                    current.add(Event(raw, ev.groupValues[1], ev.groupValues[2]))
                }
            }
            return ParsedLog(
                file.name,
                sessions.filter { s -> s.count { it.pid == "010C" } >= 20 }.mapIndexed { i, ev -> Session(i, ev) }
            )
        }
    }
}

/** Zegar symulacji – ustawiany na znacznik czasu odtwarzanej ramki. */
class SimClock {
    var now = 0L
    fun read(): Long = now
}

/** Koniec odtwarzanej sesji = zerwanie połączenia (jak wyłączenie zapłonu / utrata BT). */
class EndOfSession : IOException("Koniec sesji w logu")

/**
 * Wirtualny adapter: odpowiada na komendy AT jak STN2120, a na PID-y odpowiedziami z logu.
 *
 * - Każde 010C przesuwa się do następnego cyklu z logu i ustawia zegar symulacji.
 * - Inny PID dostaje swoją odpowiedź z tego samego cyklu, a gdy w tym cyklu nie był odpytywany –
 *   ostatnią wcześniejszą (sample & hold, maks. 10 s), w przeciwnym razie "NO DATA".
 */
class ReplayElmLink(private val session: ParsedLog.Session, private val clock: SimClock) : ElmLink {
    private var cycle = -1
    private val cursors = HashMap<String, Int>()
    var queries = 0
        private set

    override fun send(cmd: String, timeoutMs: Long): String {
        when {
            cmd == "ATZ" -> return "ELM327 v2.3"
            cmd == "STI" -> return "STN2120 v5.8.1"
            cmd.startsWith("AT") -> return "OK"
            cmd.startsWith("STPX h:18DA15F1, d:0100") -> return "4100BE3FA813"
        }
        val pid = if (cmd.startsWith("STPX")) cmd.substringAfter("d:").substringBefore(",") else cmd
        queries++
        if (pid == "010C") {
            cycle++
            if (cycle >= session.anchors.size) throw EndOfSession()
            val a = session.anchors[cycle]
            clock.now = a.t
            return a.resp
        }
        val windowStart = if (cycle < 0) session.startMs else session.anchors[cycle].t
        val windowEnd = if (cycle + 1 < session.anchors.size) session.anchors[cycle + 1].t else Long.MAX_VALUE
        val list = session.eventsFor(pid)
        if (list.isEmpty()) return "NO DATA"
        // przesuwany kursor = ostatnie zdarzenie z t < windowStart
        var i = cursors[pid] ?: -1
        while (i + 1 < list.size && list[i + 1].t < windowStart) i++
        cursors[pid] = i
        val next = i + 1
        if (next < list.size && list[next].t < windowEnd) {
            cursors[pid] = next
            return list[next].resp
        }
        if (i >= 0 && windowStart - list[i].t <= 10_000L) return list[i].resp
        return "NO DATA"
    }

    override fun close() {}
}
