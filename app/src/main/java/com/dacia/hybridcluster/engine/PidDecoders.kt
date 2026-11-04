package com.dacia.hybridcluster.engine

import com.dacia.hybridcluster.model.EvInhibit
import com.dacia.hybridcluster.model.NO_TEMP

/**
 * Surowe odczyty z ECU trzymane między cyklami (sample & hold).
 * Wartości startowe odpowiadają stanowi zaraz po połączeniu z adapterem.
 */
class RawReadings {
    var speed = 0.0
    var rpm = 0
    var iceTorque = 0.0
    var iceTorqueKnown = false
    var rearRpm = 0
    var rearTorque = 0.0
    var eradGearRaw = 2 // 0=N, 1=E1, 2=E2 (domyślnie bieg szosowy)
    var gear = "-"
    var eGear = "-"

    var hvAmp = 0.0
    var hvVolt = 0.0
    var hvSoc = 0.0
    var hsgLoad = 0.0

    var v12 = 0.0
    var bat12vAmps = 0.0
    var bat12vWatts = 0.0
    var ibsSoc: Int? = null

    var coolant = NO_TEMP
    var oilTemp = NO_TEMP
    var gearboxTemp = NO_TEMP
    var rearTemp = NO_TEMP

    var fuelBoost = false

    // Flagi blokady EV / Stop & Start (EMS3253)
    var ssDisplayCode = 0
    var ssHvacForbid = false
    var ssHvForbid = false
    var ssPowertrainForbid = false
    var ssVeemForbid = false
    var ssPtcActive = false
    var ssCoolingForbid = false
    var ssSailingClutchForbid = false
    var ssHfpForbid = false
    var ssAbsForbid = false

    fun resetForNewConnection() {
        val fresh = RawReadings()
        speed = fresh.speed; rpm = fresh.rpm; iceTorque = fresh.iceTorque; iceTorqueKnown = fresh.iceTorqueKnown
        rearRpm = fresh.rearRpm; rearTorque = fresh.rearTorque; eradGearRaw = fresh.eradGearRaw
        gear = fresh.gear; eGear = fresh.eGear
        hvAmp = fresh.hvAmp; hvVolt = fresh.hvVolt; hvSoc = fresh.hvSoc; hsgLoad = fresh.hsgLoad
        v12 = fresh.v12; bat12vAmps = fresh.bat12vAmps; bat12vWatts = fresh.bat12vWatts; ibsSoc = fresh.ibsSoc
        coolant = fresh.coolant; oilTemp = fresh.oilTemp; gearboxTemp = fresh.gearboxTemp; rearTemp = fresh.rearTemp
        fuelBoost = fresh.fuelBoost
        ssDisplayCode = 0; ssHvacForbid = false; ssHvForbid = false; ssPowertrainForbid = false
        ssVeemForbid = false; ssPtcActive = false; ssCoolingForbid = false; ssSailingClutchForbid = false
        ssHfpForbid = false; ssAbsForbid = false
    }
}

/** Dekodery PID-ów (skalowania wg DDT4All / logów z auta). Czyste funkcje – łatwe do testowania. */
object PidDecoders {

    fun u16(b: IntArray, i: Int = 0): Int = (b[i] * 256) + b[i + 1]

    fun rpm(b: IntArray): Int = u16(b) / 4

    /** EMS3253 221039 – Estimated engine torque: step 0.03125, offset -400 Nm. */
    fun torque221039(b: IntArray, rpm: Int): Double {
        val tq = u16(b) * 0.03125 - 400.0
        return if (rpm > 250 && tq > 0.0) tq.coerceIn(0.0, 240.0) else 0.0
    }

    /** EMS3253 22117B – moment ze znakiem (signed 16-bit Nm). */
    fun torque22117B(b: IntArray, rpm: Int): Double {
        val raw = u16(b)
        val signed = if (raw > 32767) raw - 65536 else raw
        return if (rpm > 250 && signed > 0) signed.toDouble().coerceIn(0.0, 240.0) else 0.0
    }

    /** Fallback: obciążenie silnika (0104) przeliczone przez krzywą maksymalnego momentu. */
    fun torqueFromLoad(loadByte: Int, rpm: Int): Double {
        val loadRatio = (loadByte / 255.0).coerceIn(0.0, 1.0)
        return if (rpm > 250 && loadRatio > 0.16) {
            (((loadRatio - 0.16) / 0.84) * maxEngineTorqueAtRpm(rpm)).coerceIn(0.0, 235.0)
        } else 0.0
    }

    /** eRAD 22FD10 – obroty wirnika, offset -25000 obr/min. */
    fun eradRpm(b: IntArray, speedKmH: Double): Int {
        val raw = u16(b)
        val calc = if (raw > 0) raw - 25000 else 0
        return if (speedKmH < 1.0 && Math.abs(calc) <= 15) 0 else calc
    }

    /** eRAD 22FD11 – moment, step 0.1, offset -100 Nm. */
    fun eradTorque(b: IntArray): Double = u16(b) * 0.1 - 100.0

    /**
     * DCDC 48V/12V 220104 – prąd strony 12V: pole 10-bitowe (wg DDT4All), offset -511 A.
     * W logach górne 6 bitów są zawsze zerem (np. 0x021F = 32 A), maska chroni przed flagami w tych bitach.
     */
    fun dcdcLvCurrent(b: IntArray): Double = (u16(b) and 0x3FF) - 511.0

    /** BMS Sunwoda 229021 – prąd 32-bit ze znakiem w mA; null gdy poza zakresem ±300 A. */
    fun bmsCurrentAmps(b: IntArray): Double? {
        val raw = (b[0].toLong() shl 24) or (b[1].toLong() shl 16) or (b[2].toLong() shl 8) or b[3].toLong()
        val signedMa = if (raw > 2147483647L) raw - 4294967296L else raw
        val amps = signedMa / 1000.0
        return if (Math.abs(amps) <= 300.0) amps else null
    }

    /** Krzywa maksymalnego momentu 1.2 TCe 48V (HR12 / EMS3253). */
    fun maxEngineTorqueAtRpm(rpm: Int): Double = when {
        rpm < 900 -> 60.0
        rpm < 1500 -> 60.0 + ((rpm - 900) / 600.0) * 110.0
        rpm < 1750 -> 170.0 + ((rpm - 1500) / 250.0) * 60.0
        rpm <= 4000 -> 230.0
        else -> (230.0 - ((rpm - 4000) / 2000.0) * 50.0).coerceAtLeast(150.0)
    }

    private val ODD_GEARS = intArrayOf(1, 3, 5, 7)
    private val EVEN_GEARS = intArrayOf(2, 4, 6)

    /**
     * Skrzynia DW23 (222135): bajt A = wałek nieparzysty, bajt B = wałek parzysty.
     * Gdy zapięte są oba wałki (preselekcja), wybieramy bieg po rzeczywistym przełożeniu rpm / km/h.
     */
    fun decodeGearbox(a: Int, b: Int, speedKmH: Double, rpm: Int, prevGear: String): String? {
        if (a == 0x0C || b == 0x14) return "P"
        if (a == 0x09) return "R"
        if (a == 0x0B && b == 0x0B) return "N"

        val oddEngaged = a in ODD_GEARS
        val evenEngaged = b in EVEN_GEARS

        if (!oddEngaged && !evenEngaged) return if (speedKmH < 1.0 && prevGear == "P") "P" else "N"
        if (oddEngaged && !evenEngaged) return "D$a"
        if (evenEngaged && !oddEngaged) return "D$b"

        if (speedKmH > 3.0 && rpm > 450) {
            val ratio = rpm / speedKmH
            return if (Math.abs(ratio - dw23GearRatio(a)) <= Math.abs(ratio - dw23GearRatio(b))) "D$a" else "D$b"
        }
        if (speedKmH <= 3.0 && (a == 1 || b == 2)) return "D1"
        if (prevGear == "D$a" || prevGear == "D$b") return prevGear
        return if (speedKmH < 15.0) "D${Math.min(a, b)}" else "D$b"
    }

    /** Przełożenia DW23 w obr/min na km/h (z logów). */
    fun dw23GearRatio(gear: Int): Double = when (gear) {
        1 -> 152.0; 2 -> 100.0; 3 -> 65.8; 4 -> 44.3; 5 -> 29.8; 6 -> 20.3
        else -> 30.0
    }

    private val ERAD_GEARS = setOf("R2", "R1", "N", "E1", "E2")

    /** eRAD 22FD05: 0=N, 1=E1 (21.97:1), 2=E2 (11.60:1), 3=zmiana biegu (zachowaj poprzedni). */
    fun decodeEradGear(raw: Int, isReverse: Boolean, prevEGear: String): String = when (raw) {
        0 -> "N"
        1 -> if (isReverse) "R1" else "E1"
        2 -> if (isReverse) "R2" else "E2"
        3 -> if (prevEGear in ERAD_GEARS) prevEGear else (if (isReverse) "R2" else "E2")
        else -> "N"
    }

    private val DISPLAY_CODE_MAP: Map<Int, EvInhibit> = buildMap {
        listOf(2, 18).forEach { put(it, EvInhibit.SLOPE) }
        listOf(4, 20).forEach { put(it, EvInhibit.ENERGY_12V_48V) }
        listOf(5, 21).forEach { put(it, EvInhibit.ENGINE_GPF) }
        listOf(6, 22, 35).forEach { put(it, EvInhibit.BRAKE) }
        listOf(7, 23, 42, 47).forEach { put(it, EvInhibit.HOOD) }
        listOf(8, 24, 45, 46).forEach { put(it, EvInhibit.DOOR) }
        listOf(9, 25, 43).forEach { put(it, EvInhibit.SEATBELT) }
        listOf(10, 26).forEach { put(it, EvInhibit.REVERSE_GEAR) }
        listOf(11, 27).forEach { put(it, EvInhibit.HVAC) }
        listOf(12, 28).forEach { put(it, EvInhibit.STEERING) }
        listOf(13, 29).forEach { put(it, EvInhibit.KEYCARD) }
        listOf(14, 30).forEach { put(it, EvInhibit.SYSTEM) }
        put(34, EvInhibit.CLUTCH)
    }

    /**
     * Przyczyny blokady EV / Stop & Start z PID-ów EMS3253 (220311 + flagi).
     * Pusta lista, gdy brak blokady lub gdy silnik spalinowy już nie pracuje (rpm < 300).
     */
    fun decodeEvInhibits(r: RawReadings): List<EvInhibit> {
        if (r.rpm < 300) return emptyList()
        val reasons = LinkedHashSet<EvInhibit>()
        val code = r.ssDisplayCode
        if (code == 36) reasons.add(EvInhibit.SWITCHED_OFF)
        if (code == 1 || code == 17 || code == 32) reasons.add(EvInhibit.SYSTEM_FAULT)
        if (code == 3 || code == 19) {
            if (r.speed < 2.0) reasons.add(EvInhibit.VEHICLE_MOVING)
        } else {
            DISPLAY_CODE_MAP[code]?.let { reasons.add(it) }
        }
        if (r.ssHvacForbid) reasons.add(EvInhibit.HVAC)
        if (r.ssPtcActive) reasons.add(EvInhibit.PTC_HEATER)
        if (r.ssVeemForbid) reasons.add(EvInhibit.VEEM_12V)
        if (r.ssHvForbid) reasons.add(EvInhibit.BATTERY_48V)
        if (r.ssPowertrainForbid) reasons.add(EvInhibit.POWERTRAIN)
        if (r.ssCoolingForbid) reasons.add(EvInhibit.COOLING)
        if (r.ssSailingClutchForbid) reasons.add(EvInhibit.SAILING_CLUTCH)
        if (r.ssHfpForbid || r.ssAbsForbid) reasons.add(EvInhibit.BRAKE_ABS)
        return reasons.toList()
    }
}
