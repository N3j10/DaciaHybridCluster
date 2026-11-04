package com.dacia.hybridcluster.obd

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ObdLogger {
    private var logFile: File? = null
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    @Volatile private var enabled: Boolean = false

    fun init(context: Context) {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        logFile = File(dir, "obd_telemetry.log")
    }

    fun setEnabled(isEnabled: Boolean) {
        enabled = isEnabled
        if (isEnabled) {
            log("=== ROZPOCZĘTO REJESTRACJĘ TELEMETRII OBD (CMF-B 29-BIT) ===")
        } else {
            log("=== ZATRZYMANO REJESTRACJĘ OBD ===")
        }
    }

    fun isEnabled(): Boolean = enabled

    fun getLogPath(): String = logFile?.absolutePath ?: "Nie zainicjalizowano"

    fun getFileSizeKb(): Long = (logFile?.length() ?: 0L) / 1024L

    @Synchronized
    fun log(message: String) {
        if (!enabled) return
        val file = logFile ?: return
        try {
            val timestamp = dateFormat.format(Date())
            FileWriter(file, true).use { fw ->
                PrintWriter(fw).use { pw ->
                    pw.println("[$timestamp] $message")
                }
            }
        } catch (_: Exception) {}
    }

    fun logCommand(cmd: String, rawResp: String, note: String? = null) {
        if (!enabled) return
        val extra = if (note != null) " | $note" else ""
        log("CMD: $cmd -> RESP: '$rawResp'$extra")
    }

    @Synchronized
    fun clearLog() {
        try {
            logFile?.delete()
            logFile?.createNewFile()
            if (enabled) {
                log("=== WYCZYSZCZONO PLIK LOGU ===")
            }
        } catch (_: Exception) {}
    }

    fun getLastLines(count: Int = 15): List<String> {
        val file = logFile ?: return emptyList()
        if (!file.exists()) return emptyList()
        return try {
            file.readLines().takeLast(count)
        } catch (_: Exception) {
            emptyList()
        }
    }
}
