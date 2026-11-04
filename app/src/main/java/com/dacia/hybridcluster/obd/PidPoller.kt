package com.dacia.hybridcluster.obd

import com.dacia.hybridcluster.engine.PidDecoders
import com.dacia.hybridcluster.engine.RawReadings

/**
 * Harmonogram odpytywania PID-ów (czysty Kotlin, bez Androida).
 *
 * 1. Pętla szybka – w każdym cyklu (~12-15 Hz z STPX): RPM, prędkość, moment ICE, obroty i moment eRAD.
 * 2. Pętla średnia – 1 zapytanie na cykl, rotacja co 3 cykle: bieg DW23, prąd BMS, bieg eRAD.
 * 3. Pętla wolna – co 6 cykli jeden z 11 slotów (temperatury, SoC, napięcia, DC-DC, flagi blokady EV).
 *
 * Kolejność zapytań jest identyczna jak w wersji sprzed refaktoru (zweryfikowane symulacją na logach).
 */
class PidPoller(private val proto: ObdProtocol) {

    data class Options(
        val showTemperatures: Boolean = true,
        val show12vBattery: Boolean = true
    )

    val raw = RawReadings()
    private var loopCounter = 0
    private var ecmTorquePid = "0104"

    /** Wykrywa obsługiwany PID momentu ICE. Wywoływać po [ObdProtocol.initialize]. */
    fun detectCapabilities(log: ObdLog) {
        loopCounter = 0
        proto.setTargetEcu(Ecu.EMS)
        ecmTorquePid = when {
            proto.query("221039") != null -> {
                log.log("ECM 18DA15F1: Wykryto moment silnika z PID 221039 (Estimated engine torque)"); "221039"
            }
            proto.query("22117B") != null -> {
                log.log("ECM 18DA15F1: Wykryto moment silnika z PID 22117B (Estimated motor torque)"); "22117B"
            }
            else -> {
                log.log("ECM 18DA15F1: Użycie PID 0104 (Calculated Engine Load) do momentu silnika"); "0104"
            }
        }
    }

    /** Jeden pełny cykl odpytywania. Wyniki trafiają do [raw]. */
    fun pollCycle(opt: Options) {
        loopCounter++
        val r = raw

        // ===== 1. PĘTLA SZYBKA =====
        proto.setTargetEcu(Ecu.EMS)
        proto.query("010C", critical = true)?.let { if (it.size >= 2) r.rpm = PidDecoders.rpm(it) }
        proto.query("010D", critical = true)?.let { if (it.isNotEmpty()) r.speed = it[0].toDouble() }

        when (ecmTorquePid) {
            "221039" -> proto.query("221039", critical = true)?.let {
                if (it.size >= 2) { r.iceTorque = PidDecoders.torque221039(it, r.rpm); r.iceTorqueKnown = true }
            }
            "22117B" -> proto.query("22117B", critical = true)?.let {
                if (it.size >= 2) { r.iceTorque = PidDecoders.torque22117B(it, r.rpm); r.iceTorqueKnown = true }
            }
            else -> proto.query("0104")?.let {
                if (it.isNotEmpty()) { r.iceTorque = PidDecoders.torqueFromLoad(it[0], r.rpm); r.iceTorqueKnown = true }
            }
        }
        if (r.rpm < 250) {
            r.iceTorque = 0.0
            r.hsgLoad = 0.0
        }

        proto.setTargetEcu(Ecu.ERAD)
        proto.query("22FD10", critical = true)?.let {
            if (it.size >= 2) r.rearRpm = PidDecoders.eradRpm(it, r.speed)
        }
        proto.query("22FD11", critical = true)?.let {
            if (it.size >= 2) r.rearTorque = PidDecoders.eradTorque(it)
        }

        // ===== 2. PĘTLA ŚREDNIA =====
        when (loopCounter % 3) {
            0 -> {
                proto.setTargetEcu(Ecu.TCM)
                proto.query("222135")?.let {
                    if (it.size >= 2) PidDecoders.decodeGearbox(it[0], it[1], r.speed, r.rpm, r.gear)?.let { g -> r.gear = g }
                }
            }
            1 -> {
                proto.setTargetEcu(Ecu.BMS)
                proto.query("229021")?.let {
                    if (it.size >= 4) PidDecoders.bmsCurrentAmps(it)?.let { a -> r.hvAmp = a }
                }
            }
            2 -> {
                proto.setTargetEcu(Ecu.ERAD)
                proto.query("22FD05")?.let {
                    if (it.isNotEmpty()) {
                        r.eradGearRaw = it[0]
                        r.eGear = PidDecoders.decodeEradGear(it[0], r.gear == "R", r.eGear)
                    }
                }
            }
        }

        // ===== 3. PĘTLA WOLNA =====
        if (loopCounter % 6 == 0) pollSlowSlot((loopCounter / 6) % 11, opt)
    }

    private fun pollSlowSlot(slot: Int, opt: Options) {
        val r = raw
        when (slot) {
            0 -> { // Obciążenie generatora BSG 48V (EMS3253)
                proto.setTargetEcu(Ecu.EMS)
                proto.query("220157")?.let { if (it.isNotEmpty()) r.hsgLoad = (it[0] * 0.3922).coerceIn(0.0, 100.0) }
            }
            1 -> { // SoC baterii 48V (step 0.01 %)
                proto.setTargetEcu(Ecu.BMS)
                proto.query("229103")?.let {
                    if (it.size >= 2) { val soc = PidDecoders.u16(it) * 0.01; if (soc in 5.0..100.0) r.hvSoc = soc }
                }
            }
            2 -> { // Napięcie pakietu 48V (mV)
                proto.setTargetEcu(Ecu.BMS)
                proto.query("229017")?.let {
                    if (it.size >= 2) { val v = PidDecoders.u16(it) / 1000.0; if (v in 30.0..60.0) r.hvVolt = v }
                }
            }
            3 -> { // Temperatura oleju skrzyni (EMS 2230C1) i stojana eRAD (22FD60)
                if (opt.showTemperatures) {
                    proto.setTargetEcu(Ecu.EMS)
                    proto.query("2230C1")?.let {
                        if (it.isNotEmpty()) { val t = (it[0] - 40).toDouble(); if (t in -30.0..150.0) r.gearboxTemp = t }
                    }
                    proto.setTargetEcu(Ecu.ERAD)
                    proto.query("22FD60")?.let { if (it.isNotEmpty()) r.rearTemp = it[0] - 30.0 }
                }
            }
            4 -> { // Płyn chłodzący (0105) i olej silnikowy (22100C: step 0.01, offset -50)
                proto.setTargetEcu(Ecu.EMS)
                proto.query("0105")?.let { if (it.isNotEmpty()) r.coolant = it[0] - 40.0 }
                if (opt.showTemperatures) {
                    proto.query("22100C")?.let {
                        if (it.size >= 2) { val t = PidDecoders.u16(it) * 0.01 - 50.0; if (t in -30.0..160.0) r.oilTemp = t }
                    }
                }
            }
            5 -> { // Przetwornica DC-DC 48V/12V (DCDC_48V_HR12): 220106 napięcie LV (0.5 V), 220104 prąd LV (10 bit, offset -511 A)
                proto.setTargetEcu(Ecu.DCDC)
                if (opt.show12vBattery) {
                    proto.query("220106")?.let {
                        if (it.isNotEmpty()) { val v = it[0] * 0.5; if (v in 10.0..16.5) r.v12 = v }
                    }
                }
                proto.query("220104")?.let {
                    if (it.size >= 2) {
                        val amps = PidDecoders.dcdcLvCurrent(it).coerceAtLeast(0.0)
                        if (amps in 0.0..220.0) {
                            r.bat12vAmps = amps
                            val effV12 = if (r.v12 > 10.0) r.v12 else 14.4
                            r.bat12vWatts = effV12 * amps
                        }
                    }
                }
            }
            6 -> { // Blokada EV – grupa 1: 220311 kod komunikatu, 22034F HVAC, 2202D0 grzałka PTC
                proto.setTargetEcu(Ecu.EMS)
                proto.query("220311")?.let { if (it.isNotEmpty()) r.ssDisplayCode = it[0] }
                proto.query("22034F")?.let { if (it.isNotEmpty()) r.ssHvacForbid = it[0] == 1 }
                proto.query("2202D0")?.let { if (it.isNotEmpty()) r.ssPtcActive = it[0] == 1 }
            }
            7 -> { // Grupa 2: 2201BD sieć 48V, 220322 napęd, 220142 VEEM 12V
                proto.setTargetEcu(Ecu.EMS)
                proto.query("2201BD")?.let { if (it.isNotEmpty()) r.ssHvForbid = it[0] == 1 }
                proto.query("220322")?.let { if (it.isNotEmpty()) r.ssPowertrainForbid = it[0] == 1 }
                proto.query("220142")?.let { if (it.isNotEmpty()) r.ssVeemForbid = it[0] == 1 }
            }
            8 -> { // Tryb paliwa / dotrysk (222172) i SoC 12V (220141: step 0.5, offset +69 %)
                proto.setTargetEcu(Ecu.EMS)
                proto.query("222172")?.let { if (it.isNotEmpty()) r.fuelBoost = it[0] in FUEL_BOOST_MODES }
                if (opt.show12vBattery) {
                    proto.query("220141")?.let {
                        if (it.isNotEmpty()) r.ibsSoc = (it[0] * 0.5 + 69.0).toInt().coerceIn(5, 100)
                    }
                }
            }
            9 -> { // Grupa 3: 220254 chłodzenie, 220103 sprzęgło sailing, 220313 HFP
                proto.setTargetEcu(Ecu.EMS)
                proto.query("220254")?.let { if (it.isNotEmpty()) r.ssCoolingForbid = it[0] == 1 }
                proto.query("220103")?.let { if (it.isNotEmpty()) r.ssSailingClutchForbid = it[0] == 1 }
                proto.query("220313")?.let { if (it.isNotEmpty()) r.ssHfpForbid = it[0] == 1 }
            }
            10 -> { // Grupa 4: 22030C ABS; 22011F i 22016E tylko do logu (4-bajtowe rejestry przyczyn)
                proto.setTargetEcu(Ecu.EMS)
                proto.query("22030C")?.let { if (it.isNotEmpty()) r.ssAbsForbid = it[0] == 1 }
                proto.query("22011F")
                proto.query("22016E")
            }
        }
    }

    /** Czyści odczyty po ponownym połączeniu (trip zostaje – patrz TripEnergyTracker.onConnectionStarted). */
    fun resetReadings() {
        raw.resetForNewConnection()
        loopCounter = 0
    }

    private companion object {
        val FUEL_BOOST_MODES = setOf(2, 5, 6, 7, 8)
    }
}
