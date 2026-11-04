package com.dacia.hybridcluster.obd

/**
 * Warstwa protokołu ELM327 / STN2120 (vLinker MS) dla Dacii CMF-B: 29-bit CAN 500k (ATSP7, ATCP18).
 *
 * Czysty Kotlin bez zależności od Androida – działa identycznie na telefonie i w symulacji na logach.
 */
class ObdProtocol(
    private val link: ElmLink,
    private val log: ObdLog = ObdLog.None,
    /** Czy użytkownik wybrał profil vLinker MS (STPX). Czytane przy każdym zapytaniu. */
    private val fastProfile: () -> Boolean = { true }
) {
    var stpxSupported = false
        private set
    private var currentTargetEcu = ""
    private var currentRespEcu = ""
    private var lastElmConfiguredEcu = ""

    /** Licznik kolejnych błędów i pominięć dla klucza "ECU:PID". */
    private class FailState { var failures = 0; var skipped = 0 }
    private val failStates = HashMap<String, FailState>()

    private val useFast get() = stpxSupported && fastProfile()

    /** Inicjalizacja adaptera. Zwraca true, jeśli aktywny jest szybki tryb STPX. */
    fun initialize(): Boolean {
        val isFastMode = fastProfile()
        log.log("Inicjalizacja OBD (Profil: ${if (isFastMode) "vLinker MS / STN" else "Standardowy ELM327"})")

        send("ATZ", 2000)
        send("ATE0")   // echo off
        send("ATL0")   // no linefeed
        send("ATS0")   // no spaces
        send("ATH0")   // headers off

        failStates.clear()
        stpxSupported = false
        lastElmConfiguredEcu = ""
        currentTargetEcu = ""
        currentRespEcu = ""

        val stnInfo = send("STI", 400)
        val isStnChip = !stnInfo.contains("?") && (stnInfo.contains("STN", ignoreCase = true) ||
            stnInfo.contains("vLinker", ignoreCase = true) || stnInfo.contains("OBDLink", ignoreCase = true))

        if (isFastMode) {
            if (isStnChip) log.log("Wykryto procesor STN: '$stnInfo'")
            else log.log("Wybrano profil vLinker MS (STI: '$stnInfo')")
            // ATAT2: agresywny adaptacyjny timing, ATST14: ~80 ms timeout (ECU odpowiadają w 2-18 ms)
            send("ATAT2")
            send("ATST14")
        } else {
            log.log("Wybrano profil standardowy ELM327 (ATAT1, standardowe timeouty)")
            send("ATAT1")
            send("ATST64")
        }

        // ISO 15765-4 CAN 29-bit 500k. ATFCSH musi poprzedzać ATFCSM1 (inaczej STN2120 zwraca '?').
        send("ATSP7")
        send("ATCP18")
        send("ATFCSH18DA15F1")
        send("ATFCSD300000")
        send("ATFCSM1")

        if (isFastMode) {
            val stpxTest = send("STPX h:18DA15F1, d:0100, t:80, r:1", 250)
            stpxSupported = !stpxTest.contains("?")
            log.log(
                if (stpxSupported) "Protokół STPX aktywny: bezpośrednie adresowanie pakietów CAN (1 komenda zamiast 4)!"
                else "Adapter nie zaakceptował rozkazu STPX, automatyczny powrót do trybu ELM327"
            )
        }
        return stpxSupported
    }

    fun setTargetEcu(ecu: Ecu) = setTargetEcu(ecu.request, ecu.response)

    fun setTargetEcu(target29: String, resp29: String) {
        currentTargetEcu = target29
        currentRespEcu = resp29
        if (useFast) return
        if (lastElmConfiguredEcu == target29) return
        lastElmConfiguredEcu = target29
        val shortTarget = if (target29.startsWith("18DA")) target29.substring(2) else target29
        send("ATFCSH$target29", 80)
        send("ATSH$shortTarget", 80)
        send("ATCRA$resp29", 80)
    }

    /**
     * Wysyła PID do bieżącego ECU i zwraca bajty danych po nagłówku odpowiedzi (np. "410C" -> [A, B]).
     * Zwraca null przy NO DATA, odpowiedzi negatywnej (7F) lub braku odpowiedzi.
     *
     * PID, który zawiódł 5 razy z rzędu, jest ponawiany tylko co [RETRY_EVERY] wywołań – chyba że jest
     * oznaczony jako [critical] (obroty, prędkość, moment): takie PID-y odpytujemy zawsze, bo ich brak
     * zwykle oznacza chwilowy problem Bluetooth, a nie brak obsługi w ECU.
     */
    fun query(cmd: String, critical: Boolean = false): IntArray? {
        val expectedPrefix = positivePrefix(cmd)
        val key = "$currentTargetEcu:$cmd"
        val st = failStates.getOrPut(key) { FailState() }
        if (!critical && st.failures >= 5) {
            st.skipped++
            if (st.skipped % RETRY_EVERY != 0) return null
        }

        val fast = useFast && currentTargetEcu.isNotEmpty()
        val rawCmd = if (fast) "STPX h:$currentTargetEcu, d:$cmd, t:85, r:1" else cmd
        // Normalna odpowiedź przychodzi w 21-26 ms; wyższy timeout chroni przed lagiem Bluetooth.
        val resp = send(rawCmd, if (fast) 260 else 300).replace(" ", "").uppercase()

        if (fast && resp.contains("?")) {
            log.log("Komenda STPX nieobsługiwana ($rawCmd) -> '$resp'. Przełączanie w tryb ELM327.")
            stpxSupported = false
            lastElmConfiguredEcu = ""
            setTargetEcu(currentTargetEcu, currentRespEcu)
            return query(cmd, critical)
        }

        val idx = resp.indexOf(expectedPrefix)
        if (idx < 0) {
            if (log.enabled && st.failures < 5) {
                log.log("CMD: $cmd -> RESP: '$resp' | BRAK '$expectedPrefix' na $currentTargetEcu")
            }
            st.failures++
            return null
        }
        st.failures = 0
        st.skipped = 0
        val hex = resp.substring(idx + expectedPrefix.length).takeWhile { it.isLetterOrDigit() }
        if (hex.length < 2) {
            if (log.enabled) log.log("CMD: $cmd -> RESP: '$resp' | ZA KRÓTKA ODPOWIEDŹ")
            return null
        }
        return try {
            val bytes = IntArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16) }
            if (log.enabled) log.log("CMD: $cmd -> RESP: '$resp' | OK [${bytes.joinToString(",") { it.toString(16) }}]")
            bytes
        } catch (_: NumberFormatException) {
            null
        }
    }

    private fun send(cmd: String, timeoutMs: Long = 200): String {
        val clean = link.send(cmd, timeoutMs)
        if (log.enabled && (cmd.startsWith("AT") || cmd.startsWith("STI") || cmd.startsWith("STPX h:18DA15F1, d:0100"))) {
            log.log("CMD $cmd -> '$clean'")
        }
        return clean
    }

    companion object {
        const val RETRY_EVERY = 30

        /** "010C" -> "410C", "221039" -> "621039" (odpowiedź pozytywna = SID + 0x40). */
        fun positivePrefix(cmd: String): String {
            val sid = cmd.substring(0, 2).toInt(16) + 0x40
            return sid.toString(16).uppercase().padStart(2, '0') + cmd.substring(2)
        }
    }
}

/** Adresy diagnostyczne sterowników Dacii CMF-B (request / response, 29-bit). */
enum class Ecu(val request: String, val response: String) {
    EMS("18DA15F1", "18DAF115"),     // EMS3253 – silnik 1.2 TCe 48V
    ERAD("18DAE5F1", "18DAF1E5"),    // INV_RE_48V_ERAD_RSA06 – tylny silnik e-AWD
    TCM("18DA18F1", "18DAF118"),     // DW23 – skrzynia dwusprzęgłowa
    BMS("18DA92F1", "18DAF192"),     // BMS48V Sunwoda
    DCDC("18DADDF1", "18DAF1DD")     // DCDC 48V/12V
}
