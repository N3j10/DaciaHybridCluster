package com.dacia.hybridcluster.engine

import com.dacia.hybridcluster.model.EvInhibit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Skalowania zweryfikowane z bazą DDT4All (ecu_final.zip) i odpowiedziami z logów z jazdy. */
class PidDecodersTest {

    private fun hex(s: String) = IntArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16) }

    @Test fun rpm010C() = assertEquals(789, PidDecoders.rpm(hex("0C55")))

    @Test fun torque221039_stepAndOffset() {
        // EMS3253: step 0.03125, offset -400 Nm -> 0x3480 = 13440 * 0.03125 - 400 = 20 Nm
        assertEquals(20.0, PidDecoders.torque221039(hex("3480"), rpm = 1500), 1e-9)
        // przy zgaszonym silniku moment = 0
        assertEquals(0.0, PidDecoders.torque221039(hex("3480"), rpm = 0), 1e-9)
        // moment ujemny (hamowanie silnikiem) obcinany do 0
        assertEquals(0.0, PidDecoders.torque221039(hex("3000"), rpm = 1500), 1e-9)
    }

    @Test fun torque22117B_signed() {
        assertEquals(120.0, PidDecoders.torque22117B(hex("0078"), 2000), 1e-9)
        assertEquals(0.0, PidDecoders.torque22117B(hex("FF88"), 2000), 1e-9) // -120 Nm -> 0
    }

    @Test fun eradSpeedAndTorque() {
        // 22FD10: offset -25000 rpm
        assertEquals(1000, PidDecoders.eradRpm(hex("6590"), speedKmH = 20.0))
        assertEquals(-500, PidDecoders.eradRpm(hex("5FB4"), speedKmH = 20.0))
        // szum na postoju
        assertEquals(0, PidDecoders.eradRpm(hex("61B2"), speedKmH = 0.0)) // +10 rpm
        // 22FD11: step 0.1, offset -100 Nm (0x03E8 = 1000 -> 0 Nm)
        assertEquals(0.0, PidDecoders.eradTorque(hex("03E8")), 1e-9)
        assertEquals(-55.0, PidDecoders.eradTorque(hex("01C2")), 1e-9)
    }

    @Test fun bmsCurrentSigned32mA() {
        // z logu: 629021FFFFEEB9 -> -4423 mA (rozładowanie)
        assertEquals(-4.423, PidDecoders.bmsCurrentAmps(hex("FFFFEEB9"))!!, 1e-9)
        assertEquals(12.5, PidDecoders.bmsCurrentAmps(hex("000030D4"))!!, 1e-9)
        assertNull(PidDecoders.bmsCurrentAmps(hex("7FFFFFFF"))) // poza ±300 A
    }

    @Test fun dcdcCurrent10bit() {
        // z logu: 620104021F -> 543 - 511 = 32 A
        assertEquals(32.0, PidDecoders.dcdcLvCurrent(hex("021F")), 1e-9)
        // górne 6 bitów (flagi) nie może zmieniać wyniku – pole ma 10 bitów wg DDT
        assertEquals(32.0, PidDecoders.dcdcLvCurrent(hex("FE1F")), 1e-9)
    }

    @Test fun gearboxDW23() {
        assertEquals("P", PidDecoders.decodeGearbox(0x0C, 0x14, 0.0, 800, "-"))
        assertEquals("R", PidDecoders.decodeGearbox(0x09, 0x10, 3.0, 900, "P"))
        assertEquals("D3", PidDecoders.decodeGearbox(3, 0x11, 40.0, 2600, "D2"))
        assertEquals("D4", PidDecoders.decodeGearbox(0x0E, 4, 60.0, 2650, "D3"))
        // preselekcja (oba wałki): decyduje przełożenie rpm/km/h. 2650/60 = 44.2 -> D4
        assertEquals("D4", PidDecoders.decodeGearbox(5, 4, 60.0, 2650, "D4"))
        // 1780/60 = 29.7 -> D5
        assertEquals("D5", PidDecoders.decodeGearbox(5, 4, 60.0, 1780, "D4"))
        // oba neutralne na postoju po P zostaje P
        assertEquals("P", PidDecoders.decodeGearbox(0x0D, 0x10, 0.0, 800, "P"))
    }

    @Test fun eradGear() {
        assertEquals("E1", PidDecoders.decodeEradGear(1, false, "-"))
        assertEquals("R2", PidDecoders.decodeEradGear(2, true, "-"))
        assertEquals("E1", PidDecoders.decodeEradGear(3, false, "E1")) // zmiana biegu – trzymaj poprzedni
        assertEquals("N", PidDecoders.decodeEradGear(4, false, "E2"))  // 4 = Invalid wg DDT
    }

    @Test fun evInhibitsFromEms() {
        val r = RawReadings().apply {
            rpm = 1200; speed = 30.0
            ssDisplayCode = 11      // stop_inhi_ac
            ssHvacForbid = true     // ta sama przyczyna – bez duplikatu
            ssVeemForbid = true
        }
        assertEquals(listOf(EvInhibit.HVAC, EvInhibit.VEEM_12V), PidDecoders.decodeEvInhibits(r))
        r.rpm = 0 // silnik zgaszony -> brak blokady
        assertTrue(PidDecoders.decodeEvInhibits(r).isEmpty())
    }

    @Test fun torqueCurveMonotonicUntilPeak() {
        var prev = 0.0
        for (rpm in 800..1750 step 50) {
            val t = PidDecoders.maxEngineTorqueAtRpm(rpm)
            assertTrue(t >= prev); prev = t
        }
        assertEquals(230.0, PidDecoders.maxEngineTorqueAtRpm(3000), 1e-9)
    }
}
