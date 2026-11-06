package com.dacia.hybridcluster.engine

import com.dacia.hybridcluster.model.HybridMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cofanie: skrzynia DW23 ma bieg R (wałek nieparzysty = 0x09), a tylny silnik też potrafi cofać.
 * W logach z jazdy: ICE zgaszony -> cofa tył (~22 Nm), ICE pracuje -> cofa ICE (~29 Nm), tył bez momentu.
 */
class PowertrainCalculatorReverseTest {

    private fun reverse(rpm: Int, iceTorque: Double, rearTorque: Double, hvAmp: Double) = RawReadings().apply {
        gear = "R"; speed = 4.0; this.rpm = rpm
        this.iceTorque = iceTorque; iceTorqueKnown = true
        this.rearTorque = rearTorque; rearRpm = -350; eradGearRaw = 2
        this.hvAmp = hvAmp; hvVolt = 48.0
    }

    private fun steady(calc: PowertrainCalculator, r: RawReadings) =
        (1..15).map { calc.compute(r.apply { rearTorque = rearTorque }) }.last()

    @Test fun reversingWithEngineUsesIcePower() {
        val clock = longArrayOf(0)
        val calc = PowertrainCalculator(TripEnergyTracker { clock[0] }, { clock[0] })
        val t = steady(calc, reverse(rpm = 900, iceTorque = 29.0, rearTorque = 0.2, hvAmp = -2.0))
        assertEquals(HybridMode.REVERSE_ICE, t.hybridMode)
        assertEquals(29.0 * 900 / 9549.0, t.icePowerKw, 0.01) // ~2.7 kW
        assertEquals(0.0, t.rearMotorPowerKw, 1e-9)
        assertTrue("napęd z przodu, a nie 100% tył", t.frontTorquePercent > 90)
    }

    @Test fun reversingElectricallyWhenEngineOff() {
        val clock = longArrayOf(0)
        val calc = PowertrainCalculator(TripEnergyTracker { clock[0] }, { clock[0] })
        val t = steady(calc, reverse(rpm = 0, iceTorque = 0.0, rearTorque = -22.0, hvAmp = -25.0))
        assertEquals(HybridMode.REVERSE, t.hybridMode)
        assertEquals(0.0, t.icePowerKw, 1e-9)
        assertTrue(t.rearMotorPowerKw > 0.5)
        assertEquals(0, t.frontTorquePercent)
    }
}
