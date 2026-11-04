package com.dacia.hybridcluster.obd

import com.dacia.hybridcluster.engine.TripEnergyTracker
import com.dacia.hybridcluster.model.EvInhibit
import com.dacia.hybridcluster.model.HybridMode
import com.dacia.hybridcluster.model.TelemetryData
import kotlin.math.sin

/** Generator danych trybu demo (bez auta). Jedno wywołanie [next] = jedna ramka co ~150 ms. */
class DemoSource(private val trip: TripEnergyTracker) {
    private var tick = 0.0

    fun next(): TelemetryData {
        tick += 0.1

        val cycle = (sin(tick * 0.5) + 1.0) / 2.0 // 0.0 .. 1.0
        val isBraking = sin(tick * 0.5) < -0.2

        val speed = (cycle * 85.0).coerceAtLeast(0.0)
        val gear = when {
            speed < 1.0 -> "P"
            speed < 18.0 -> "D1"
            speed < 38.0 -> "D2"
            speed < 62.0 -> "D3"
            else -> "D4"
        }

        val iceRpm = if (isBraking || speed < 15.0) 0 else (1200 + cycle * 2200).toInt()
        val icePower = if (isBraking || speed < 15.0) 0.0 else (cycle * 55.0)
        val rearPower = if (isBraking) 0.0 else (cycle * 24.0)
        val hsgPower = if (isBraking) 0.0 else (if (cycle > 0.6) 8.0 else 2.0)

        val totalOutput = if (isBraking) 0.0 else (icePower + rearPower + hsgPower)
        val rearRegen = if (isBraking) (18.0 * cycle).coerceAtLeast(4.0) else 0.0
        val frontRegen = if (isBraking) (6.0 * cycle).coerceAtLeast(2.0) else 0.0
        val totalRegen = rearRegen + frontRegen

        val hvVoltage = 48.0 + sin(tick) * 1.5
        val currentAmps = if (isBraking) (totalRegen * 1000.0) / hvVoltage else -((rearPower + hsgPower) * 1000.0) / hvVoltage

        val v12Sim = 13.8 + sin(tick * 0.3) * 0.6
        val a12Sim = (14.0 + sin(tick * 0.6) * 10.0 + if (isBraking) 12.0 else 0.0).coerceAtLeast(3.0)

        val frontPower = icePower + hsgPower
        val totalShare = frontPower + rearPower
        val (frontPct, rearPct) = when {
            totalShare > 0.5 -> {
                val fp = ((frontPower / totalShare) * 100.0).toInt().coerceIn(0, 100)
                fp to 100 - fp
            }
            speed < 1.0 -> 50 to 50
            iceRpm == 0 -> 0 to 100
            else -> 100 to 0
        }

        trip.update(
            voltage = hvVoltage,
            current = currentAmps,
            speedKmH = speed,
            iceRpm = iceRpm,
            fuelRateLh = if (icePower > 0) 4.8 else 0.0,
            isRegenActive = isBraking,
            regenPowerKw = totalRegen,
            rearMotorPowerKw = rearPower
        )

        val isCoasting = (!isBraking && cycle < 0.08 && speed > 5.0)
        val isEv = (!isBraking && rearPower > 0.5 && icePower <= 0.1)

        val mode = when {
            isBraking -> HybridMode.REGEN
            isCoasting -> HybridMode.COASTING
            isEv -> HybridMode.PURE_EV
            icePower > 8.0 && rearPower > 3.0 -> HybridMode.EAWD_BOOST
            icePower > 0 -> HybridMode.HYBRID_AUTO
            else -> HybridMode.EV_READY
        }
        val eGear = when {
            speed > 45.0 -> "E2"
            speed > 1.0 -> "E1"
            else -> "N"
        }
        val inhibits = when {
            iceRpm > 0 && cycle in 0.25..0.40 -> listOf(EvInhibit.HVAC, EvInhibit.COLD_ENGINE)
            iceRpm > 0 && cycle in 0.40..0.58 -> listOf(EvInhibit.HVAC)
            else -> emptyList()
        }

        return TelemetryData(
            speedKmH = speed,
            currentGear = gear,
            eGear = eGear,
            iceRpm = iceRpm,
            icePowerKw = icePower,
            rearMotorPowerKw = if (isCoasting || isBraking) 0.0 else rearPower,
            hsgPowerKw = hsgPower,
            totalOutputPowerKw = totalOutput,
            totalRegenPowerKw = totalRegen,
            rearRegenPowerKw = rearRegen,
            iceTorqueNm = if (icePower > 0) 140.0 else 0.0,
            rearMotorTorqueNm = if (isBraking) -55.0 else (if (isCoasting) 0.0 else rearPower * 4.5),
            rearMotorRpm = (speed * 88.7).toInt(),
            hsgLoadPercent = 22.0,
            hvBatterySocPercent = 68.0 - (tick * 0.01) % 15.0,
            hvBatteryVoltage = hvVoltage,
            hvBatteryCurrent = currentAmps,
            bat12vVoltage = v12Sim,
            bat12vCurrent = a12Sim,
            bat12vSocPercent = (82 + sin(tick * 0.1) * 6.0).toInt().coerceIn(70, 95),
            bat12vPowerWatts = (v12Sim * a12Sim).coerceAtLeast(0.0),
            coolantTempC = 88.0,
            oilTempC = 85.0,
            gearboxTempC = 81.0,
            rearMotorTempC = 44.0,
            fuelInjectionBoostActive = (icePower > 35.0 || (cycle > 0.7 && icePower > 15.0)),
            evInhibits = inhibits,
            frontTorquePercent = frontPct,
            rearTorquePercent = rearPct,
            gForce = if (isBraking) -0.32 else (cycle * 0.42),
            hybridMode = mode,
            tripDistanceKm = trip.getDistanceKm(),
            tripEvDistancePercent = trip.getEvDistancePercent(),
            tripEvEnergySharePercent = trip.getEvEnergySharePercent(),
            tripRegenKWh = trip.getRegenKWh(),
            tripUsedElecKWh = trip.getUsedElecKWh(),
            tripFuelSavedLiters = trip.getFuelSavedLiters()
        )
    }
}
