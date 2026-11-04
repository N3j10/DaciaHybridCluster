package com.dacia.hybridcluster.obd

import android.content.Context
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Logger telemetrii OBD.
 *
 * - Zapis odbywa się na osobnym wątku przez jeden [BufferedWriter] (wcześniej każda linia otwierała
 *   i zamykała plik synchronicznie w pętli OBD, spowalniając odpytywanie).
 * - Rotacja: po przekroczeniu [MAX_BYTES] bieżący plik staje się `obd_telemetry.1.log`.
 * - Format linii bez zmian – stare skrypty analizy i symulacja nadal działają.
 */
object ObdLogger {
    private const val MAX_BYTES = 8L * 1024 * 1024
    private const val FLUSH_INTERVAL_MS = 1000L

    private var logFile: File? = null
    private var writer: BufferedWriter? = null
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US) // używany tylko na wątku zapisu
    private val io = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "obd-logger").apply { isDaemon = true }
    }
    @Volatile private var enabled: Boolean = false

    fun init(context: Context) {
        if (logFile != null) return
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        logFile = File(dir, "obd_telemetry.log")
        io.scheduleWithFixedDelay({ flushNow() }, FLUSH_INTERVAL_MS, FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS)
    }

    fun setEnabled(isEnabled: Boolean) {
        if (enabled == isEnabled) return
        if (isEnabled) {
            enabled = true
            log("=== ROZPOCZĘTO REJESTRACJĘ TELEMETRII OBD (CMF-B 29-BIT) ===")
        } else {
            log("=== ZATRZYMANO REJESTRACJĘ OBD ===")
            enabled = false
            io.execute { flushNow() }
        }
    }

    fun isEnabled(): Boolean = enabled

    fun getLogPath(): String = logFile?.absolutePath ?: "Nie zainicjalizowano"

    fun getFileSizeKb(): Long = (logFile?.length() ?: 0L) / 1024L

    fun log(message: String) {
        if (!enabled || logFile == null) return
        val ts = System.currentTimeMillis()
        io.execute { write(ts, message) }
    }

    fun clearLog() {
        io.execute {
            try {
                writer?.close()
                writer = null
                logFile?.delete()
                logFile?.createNewFile()
            } catch (_: Exception) {}
            if (enabled) write(System.currentTimeMillis(), "=== WYCZYSZCZONO PLIK LOGU ===")
        }
    }

    /** Ostatnie linie pliku – czyta tylko koniec (max ~32 KB), nie cały, potencjalnie wielomegabajtowy plik. */
    fun getLastLines(count: Int = 15): List<String> {
        val file = logFile ?: return emptyList()
        if (!file.exists()) return emptyList()
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val len = raf.length()
                val readLen = minOf(len, 32L * 1024).toInt()
                raf.seek(len - readLen)
                val buf = ByteArray(readLen)
                raf.readFully(buf)
                val lines = String(buf, Charsets.UTF_8).split('\n').filter { it.isNotBlank() }
                // pierwsza linia może być ucięta w połowie
                (if (readLen < len) lines.drop(1) else lines).takeLast(count)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ----- tylko wątek obd-logger -----

    private fun write(ts: Long, message: String) {
        val file = logFile ?: return
        try {
            val w = writer ?: BufferedWriter(FileWriter(file, true), 16 * 1024).also { writer = it }
            w.write("[${dateFormat.format(Date(ts))}] $message")
            w.newLine()
            if (file.length() > MAX_BYTES) rotate(file)
        } catch (_: Exception) {
            writer = null
        }
    }

    private fun rotate(file: File) {
        try {
            writer?.close()
            writer = null
            val old = File(file.parentFile, "obd_telemetry.1.log")
            old.delete()
            file.renameTo(old)
        } catch (_: Exception) {}
    }

    private fun flushNow() {
        try { writer?.flush() } catch (_: Exception) { writer = null }
    }
}
