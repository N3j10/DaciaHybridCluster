package com.dacia.hybridcluster.obd

import java.io.IOException

/**
 * Transport komend tekstowych ELM327/STN. Implementacje:
 * - [BluetoothElmLink] – vLinker przez Bluetooth SPP,
 * - ReplayElmLink (testy/symulacja) – odtwarza odpowiedzi z logów z jazdy.
 */
interface ElmLink {
    /**
     * Wysyła komendę (bez '\r') i zwraca odpowiedź do znaku zachęty '>'
     * (CR/LF zamienione na spacje, przycięta). Pusty tekst = brak odpowiedzi w czasie [timeoutMs].
     * @throws IOException gdy połączenie zostało zerwane.
     */
    @Throws(IOException::class)
    fun send(cmd: String, timeoutMs: Long): String

    fun close()
}

/** Minimalny interfejs logowania, żeby warstwa protokołu nie zależała od Androida. */
interface ObdLog {
    val enabled: Boolean
    fun log(message: String)

    object None : ObdLog {
        override val enabled = false
        override fun log(message: String) {}
    }
}
