package com.dacia.hybridcluster.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Transport z osobnym wątkiem czytającym – symulacja adaptera na parze strumieni. */
class StreamElmLinkTest {

    /** "Adapter": dostaje komendy, odpowiada przez `respond`. */
    private class FakeAdapter {
        val toLink = PipedOutputStream()
        val linkInput = PipedInputStream(toLink, 4096)
        val commands = LinkedBlockingQueue<String>()
        val linkOutput = object : OutputStream() {
            private val sb = StringBuilder()
            override fun write(b: Int) {
                if (b == '\r'.code) { commands.offer(sb.toString()); sb.setLength(0) } else sb.append(b.toChar())
            }
        }
        fun respond(text: String) { toLink.write(text.toByteArray()); toLink.flush() }
    }

    @Test fun normalResponse() {
        val a = FakeAdapter()
        val link = StreamElmLink(a.linkInput, a.linkOutput) {}
        val responder = Thread {
            assertEquals("010C", a.commands.poll(1, TimeUnit.SECONDS))
            a.respond("410C0C55\r\r>")
        }.apply { start() }
        assertEquals("410C0C55", link.send("010C", 500))
        responder.join()
        link.close()
    }

    @Test fun lateResponseDoesNotShiftNextCommand() {
        val a = FakeAdapter()
        val link = StreamElmLink(a.linkInput, a.linkOutput) {}
        // 1. komenda: brak odpowiedzi w czasie (timeout 50 ms + 80 ms tolerancji)
        assertEquals("", link.send("22FD10", 50))
        // spóźniona odpowiedź dociera dopiero teraz
        a.respond("62FD1065\r>")
        Thread.sleep(50)
        // 2. komenda musi dostać SWOJĄ odpowiedź, a nie spóźnioną z poprzedniej
        val responder = Thread {
            a.commands.poll(1, TimeUnit.SECONDS) // 22FD10
            assertEquals("22FD11", a.commands.poll(1, TimeUnit.SECONDS))
            a.respond("62FD1103E8\r>")
        }.apply { start() }
        assertEquals("62FD1103E8", link.send("22FD11", 500))
        responder.join()
        link.close()
    }

    @Test fun closeFromOtherThreadUnblocksAndThrows() {
        val a = FakeAdapter()
        val link = StreamElmLink(a.linkInput, a.linkOutput) { a.linkInput.close() }
        Thread { Thread.sleep(100); link.close() }.start()
        val t0 = System.currentTimeMillis()
        try {
            link.send("010C", 5_000) // czeka na odpowiedź – zamknięcie z innego wątku musi je przerwać
            fail("przerwane send() powinno rzucić IOException (pętla OBD kończy wtedy sesję)")
        } catch (_: IOException) {
        }
        assertTrue("send() powinien wrócić szybko po close()", System.currentTimeMillis() - t0 < 2_000)
        try {
            link.send("010D", 100)
            fail("po zamknięciu send() powinien rzucić IOException")
        } catch (_: IOException) {
        }
    }
}
