package com.dacia.hybridcluster.obd

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObdProtocolTest {

    /** Adapter testowy: odpowiedzi z mapy, zapisuje wysłane komendy. */
    private class FakeLink(val answer: (String) -> String) : ElmLink {
        val sent = ArrayList<String>()
        override fun send(cmd: String, timeoutMs: Long): String { sent.add(cmd); return answer(cmd) }
        override fun close() {}
    }

    @Test fun positivePrefix() {
        assertEquals("410C", ObdProtocol.positivePrefix("010C"))
        assertEquals("621039", ObdProtocol.positivePrefix("221039"))
        assertEquals("62FD10", ObdProtocol.positivePrefix("22FD10"))
    }

    @Test fun stpxModeAddressesEcuInOneCommand() {
        val link = FakeLink { cmd ->
            when {
                cmd == "STI" -> "STN2120 v5.8.1"
                cmd.startsWith("STPX h:18DA15F1, d:0100") -> "4100BE3FA813"
                cmd.startsWith("STPX h:18DAE5F1, d:22FD11") -> "62FD1103E8"
                else -> "OK"
            }
        }
        val p = ObdProtocol(link)
        assertTrue(p.initialize())
        p.setTargetEcu(Ecu.ERAD)
        assertArrayEquals(intArrayOf(0x03, 0xE8), p.query("22FD11"))
        assertEquals("STPX h:18DAE5F1, d:22FD11, t:85, r:1", link.sent.last())
        assertFalse(link.sent.any { it.startsWith("ATSH") }) // bez przełączania nagłówków
    }

    @Test fun fallbackToElmHeadersWhenStpxRejected() {
        val link = FakeLink { cmd ->
            when {
                cmd.startsWith("STPX h:18DA15F1, d:0100") -> "4100BE3FA813"
                cmd.startsWith("STPX") -> "?"
                cmd == "010C" -> "410C0C55"
                else -> "OK"
            }
        }
        val p = ObdProtocol(link)
        p.initialize()
        p.setTargetEcu(Ecu.EMS)
        assertArrayEquals(intArrayOf(0x0C, 0x55), p.query("010C"))
        assertFalse(p.stpxSupported)
        assertTrue(link.sent.contains("ATSHDA15F1"))
        assertTrue(link.sent.contains("ATCRA18DAF115"))
    }

    @Test fun negativeResponseAndNoDataReturnNull() {
        val p = ObdProtocol(FakeLink { if (it.contains("221039")) "7F2231" else "NO DATA" }, fastProfile = { false })
        p.setTargetEcu(Ecu.EMS)
        assertNull(p.query("221039"))
        assertNull(p.query("010D"))
    }

    @Test fun failingPidRetriedEvery5sButCriticalAlways() {
        var now = 0L
        val link = FakeLink { "NO DATA" }
        val p = ObdProtocol(link, fastProfile = { false }, clock = { now })
        p.setTargetEcu(Ecu.EMS)
        repeat(5) { p.query("220157") }            // 5 błędów
        val before = link.sent.count { it == "220157" }
        repeat(20) { p.query("220157") }           // w ciągu 5 s – pomijany
        assertEquals(before, link.sent.count { it == "220157" })
        now += 5_001
        p.query("220157")                          // po 5 s – ponowienie
        assertEquals(before + 1, link.sent.count { it == "220157" })

        repeat(30) { p.query("010C", critical = true) } // krytyczny – zawsze wysyłany
        assertEquals(30, link.sent.count { it == "010C" })
    }
}
