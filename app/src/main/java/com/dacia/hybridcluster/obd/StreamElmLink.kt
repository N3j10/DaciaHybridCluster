package com.dacia.hybridcluster.obd

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Transport ELM327 na parze strumieni (Bluetooth SPP lub strumienie testowe).
 *
 * Zamiast aktywnego czekania (`available()` + `Thread.sleep(1)`) osobny wątek wykonuje blokujący
 * `read()` i składa kompletne odpowiedzi zakończone znakiem '>' w kolejkę. Wątek OBD czeka na
 * odpowiedź przez `poll(timeout)`, więc nie zużywa CPU w trakcie oczekiwania.
 */
class StreamElmLink(
    private val input: InputStream,
    private val output: OutputStream,
    private val closeAction: () -> Unit
) : ElmLink {

    private val responses = LinkedBlockingQueue<String>()
    @Volatile private var closed = false
    @Volatile private var readError: IOException? = null

    private val reader = Thread({
        val buf = ByteArray(256)
        val sb = StringBuilder(64)
        try {
            while (!closed) {
                val n = input.read(buf)
                if (n < 0) throw IOException("Strumień zamknięty przez adapter")
                for (i in 0 until n) {
                    val ch = (buf[i].toInt() and 0xFF).toChar()
                    if (ch == '>') {
                        responses.offer(sb.toString())
                        sb.setLength(0)
                    } else {
                        sb.append(ch)
                    }
                }
            }
        } catch (e: IOException) {
            if (!closed) readError = e
        } finally {
            closed = true
            responses.offer(EOF) // obudź czekającego na odpowiedź
        }
    }, "obd-link-reader").apply { isDaemon = true }

    init {
        reader.start()
    }

    override fun send(cmd: String, timeoutMs: Long): String {
        checkOpen()
        // Odrzuć spóźnione odpowiedzi na poprzednie komendy (zapobiega przesunięciu kolejki)
        while (true) {
            val stale = responses.poll() ?: break
            if (stale === EOF) checkOpen()
        }
        output.write("$cmd\r".toByteArray())
        output.flush()

        var resp = responses.poll(timeoutMs, TimeUnit.MILLISECONDS)
        if (resp == null) {
            // Odpowiedź spóźniona przez lag Bluetooth – czekaj jeszcze chwilę, żeby nie trafiła do następnej komendy
            resp = responses.poll(LATE_GRACE_MS, TimeUnit.MILLISECONDS)
        }
        if (resp === EOF) {
            checkOpen()
            return ""
        }
        return resp?.replace('\r', ' ')?.replace('\n', ' ')?.trim() ?: ""
    }

    private fun checkOpen() {
        if (closed) throw readError ?: IOException("Połączenie zamknięte")
    }

    /** Można wołać z dowolnego wątku – zamknięcie gniazda przerywa blokujący read(). */
    override fun close() {
        closed = true
        try { closeAction() } catch (_: Exception) {}
        responses.offer(EOF)
    }

    private companion object {
        const val LATE_GRACE_MS = 80L
        /** Znacznik końca strumienia (porównywany przez referencję). */
        val EOF = String(charArrayOf('\u0000'))
    }
}
