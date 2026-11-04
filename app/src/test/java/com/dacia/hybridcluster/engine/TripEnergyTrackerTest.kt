package com.dacia.hybridcluster.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TripEnergyTrackerTest {

    private fun drive(t: TripEnergyTracker, clock: LongArray, seconds: Int, speed: Double = 36.0) {
        repeat(seconds * 10) {
            clock[0] += 100
            t.update(voltage = 48.0, current = -10.0, speedKmH = speed, iceRpm = 1500, fuelRateLh = 3.6)
        }
    }

    @Test fun tripSurvivesReconnect() {
        val clock = longArrayOf(1_000_000L)
        val t = TripEnergyTracker { clock[0] }
        t.onConnectionStarted()
        drive(t, clock, 100)                 // 100 s * 10 m/s = 1 km
        val km = t.getDistanceKm()
        assertEquals(1.0, km, 0.01)

        clock[0] += 30_000                   // przerwa BT 30 s
        t.onConnectionStarted()
        assertEquals(km, t.getDistanceKm(), 1e-9) // NIE wyzerowany (stare zachowanie: reset)
        drive(t, clock, 100)
        assertEquals(2.0, t.getDistanceKm(), 0.02)
    }

    @Test fun newTripAfterLongBreak() {
        val clock = longArrayOf(1_000_000L)
        val t = TripEnergyTracker { clock[0] }
        drive(t, clock, 50)
        clock[0] += TripEnergyTracker.NEW_TRIP_GAP_MS + 1
        t.onConnectionStarted()
        assertEquals(0.0, t.getDistanceKm(), 1e-12)
    }

    @Test fun snapshotRestore() {
        val clock = longArrayOf(1_000_000L)
        val a = TripEnergyTracker { clock[0] }
        drive(a, clock, 60)
        val b = TripEnergyTracker { clock[0] }
        b.restore(a.snapshot())
        assertEquals(a.getDistanceKm(), b.getDistanceKm(), 1e-12)
        assertEquals(a.getUsedElecKWh(), b.getUsedElecKWh(), 1e-12)
        assertTrue(b.getUsedElecKWh() > 0)
    }

    @Test fun shortStepsAreAccumulatedNotLost() {
        val clock = longArrayOf(1_000_000L)
        val t = TripEnergyTracker { clock[0] }
        t.update(48.0, 0.0, 36.0, 1000, 0.0)
        repeat(1000) { clock[0] += 2; t.update(48.0, 0.0, 36.0, 1000, 0.0) } // kroki po 2 ms
        // 2 s jazdy 10 m/s = 20 m (wcześniej kroki < 5 ms były gubione => 0 m)
        assertEquals(20.0, t.totalDistanceMeters, 0.5)
    }

    @Test fun evShareUsesSameFuelModelAsFuelSaved() {
        val clock = longArrayOf(1_000_000L)
        val t = TripEnergyTracker { clock[0] }
        drive(t, clock, 100)
        // 0.01 L paliwa = 0.0357 kWh na wale, 1.33 kWh... sprawdzamy tylko spójność stałej
        val iceKWh = t.totalFuelLiters / FuelModel.LITERS_PER_SHAFT_KWH
        val expected = t.getUsedElecKWh() / (t.getUsedElecKWh() + iceKWh) * 100
        assertEquals(expected, t.getEvEnergySharePercent(), 1e-9)
    }
}
