package com.dacia.hybridcluster.engine

import com.dacia.hybridcluster.model.HybridMode
import com.dacia.hybridcluster.model.TelemetryData

/**
 * Bilans mocy napędu e-AWD: surowe odczyty ([RawReadings]) -> [TelemetryData].
 *
 * Czysta logika bez Androida i bez I/O – testowana symulacją na logach z jazdy.
 * Fizyka i progi są przeniesione 1:1 z pętli ObdManager sprzed refaktoru.
 */
class PowertrainCalculator(
    private val trip: TripEnergyTracker,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Tylko dla testów porównawczych: stary model cofania (zawsze tylko tylny silnik). */
    private val legacyReverseModel: Boolean = false
) {
    private var smoothedFrontPct = 50.0
    private var regenConfirmCount = 0

    // Przyspieszenie wzdłużne z pochodnej prędkości (wygładzone)
    private var lastSpeedKmH = Double.NaN
    private var lastSpeedTs = 0L
    private var smoothedG = 0.0

    fun reset() {
        smoothedFrontPct = 50.0
        regenConfirmCount = 0
        lastSpeedKmH = Double.NaN
        lastSpeedTs = 0L
        smoothedG = 0.0
    }

    fun compute(r: RawReadings): TelemetryData {
        val speed = r.speed
        val rpm = r.rpm
        val rearTorque = r.rearTorque
        val hvAmp = r.hvAmp
        val hvVolt = r.hvVolt
        val iceTorque = r.iceTorque
        val gearStr = r.gear

        var hsgPowerKw: Double
        var hsgRegenKw: Double

        val isStandstill = speed < 1.0
        val isParkOrNeutral = (gearStr == "P" || gearStr == "N")
        val isReverse = gearStr == "R"

        // Pomiary elektryczne BMS 48V (Sunwoda): prąd dodatni = ładowanie
        val safeHvVolt = if (hvVolt in 30.0..60.0) hvVolt else 48.0
        val isBatteryCharging = hvAmp > 0.5
        val isBatteryDischarging = hvAmp < -0.5
        val batChargeKw = if (isBatteryCharging) ((hvAmp * safeHvVolt) / 1000.0).coerceIn(0.0, 30.0) else 0.0
        val batDischargeKw = if (isBatteryDischarging) ((-hvAmp * safeHvVolt) / 1000.0).coerceIn(0.0, 30.0) else 0.0

        // Moc pobierana z szyny 48V przez DC-DC 12V (sprawność ~92%) – nie jest mocą napędu kół
        val dcdc48vKw = if (r.bat12vWatts > 5.0) ((r.bat12vWatts / 1000.0) / 0.92).coerceIn(0.0, 3.5) else 0.0
        val netTractionBatDischargeKw = (batDischargeKw - dcdc48vKw).coerceAtLeast(0.0)

        // Oś tylna eRAD: E1 = 168.0 obr/min na km/h, E2 = 88.7; 0 = N, 3 = zmiana biegu (sprzęgło rozłączone)
        val isEradMechanicallyEngaged = (r.eradGearRaw == 1 || r.eradGearRaw == 2)
        val kinematicFallbackRatio = when (r.eradGearRaw) {
            1 -> 168.0
            2 -> 88.7
            else -> 0.0
        }
        val activeRearRpm = when {
            r.rearRpm != 0 -> Math.abs(r.rearRpm)
            speed > 1.0 && isEradMechanicallyEngaged -> (speed * kinematicFallbackRatio).toInt()
            else -> 0
        }

        // Moc napędowa osi tylnej (moment * obroty wirnika); w N / przy zmianie biegu moment tylko synchronizuje
        val calcRearDriveKw = when {
            !isEradMechanicallyEngaged -> 0.0
            isReverse -> if (Math.abs(rearTorque) > 0.8 && activeRearRpm > 0) {
                ((Math.abs(rearTorque) * activeRearRpm) / 9549.0).coerceIn(0.0, 30.0)
            } else 0.0
            rearTorque > 0.8 && activeRearRpm > 0 -> ((rearTorque * activeRearRpm) / 9549.0).coerceIn(0.0, 30.0)
            else -> 0.0
        }

        // Rekuperacja osi tylnej: zapięty bieg i moment hamujący poniżej -4 Nm (eliminuje szum toczenia)
        val calcRearRegenKw = if (isEradMechanicallyEngaged && !isReverse && rearTorque < -4.0 && activeRearRpm > 0) {
            ((-rearTorque * activeRearRpm) / 9549.0).coerceIn(0.0, 25.0)
        } else 0.0

        // Wspomaganie BSG 48V: prąd trakcyjny z baterii niezużyty przez tylny falownik (sprawność 90%)
        val rearElecDriveKw = if (calcRearDriveKw > 0.1) (calcRearDriveKw / 0.90) else 0.0
        val hsgElecAssistKw = (netTractionBatDischargeKw - rearElecDriveKw).coerceAtLeast(0.0)
        hsgPowerKw = if (rpm > 250 && hsgElecAssistKw > 0.15) (hsgElecAssistKw * 0.90).coerceIn(0.0, 12.0) else 0.0

        // Rekuperacja BSG: generacja na szynę 48V minus odzysk z tyłu
        val totalGenElecKw = batChargeKw + dcdc48vKw
        val rearRegenElecKw = calcRearRegenKw * 0.90
        val hsgGenElecKw = (totalGenElecKw - rearRegenElecKw).coerceAtLeast(0.0)
        hsgRegenKw = if (rpm > 250 && hsgGenElecKw > 0.2) (hsgGenElecKw / 0.90).coerceIn(0.0, 15.0) else 0.0

        val isAcceleratingOrCruising = (
            (rpm > 600 && iceTorque > 6.0) ||
                (isEradMechanicallyEngaged && rearTorque > 1.2 && netTractionBatDischargeKw > 0.2)
            )

        val rawKineticRegen = !isStandstill && !isParkOrNeutral && !isReverse && speed > 3.0 && !isAcceleratingOrCruising && (
            calcRearRegenKw > 0.8 ||
                (rpm == 0 && batChargeKw > 1.0) ||
                (rpm > 500 && iceTorque <= 3.0 && batChargeKw > 1.0) ||
                (batChargeKw > 1.5 && (calcRearRegenKw > 0.4 || hsgRegenKw > 0.8))
            )
        regenConfirmCount = if (rawKineticRegen) (regenConfirmCount + 1).coerceAtMost(10) else 0
        val isKineticRegen = regenConfirmCount >= 2

        val elecFromBatPureEv = (netTractionBatDischargeKw * 0.90).coerceIn(0.0, 25.0)
        val isPureEv = (rpm == 0 && speed > 1.0 && !isReverse && !isParkOrNeutral && !isKineticRegen &&
            (calcRearDriveKw > 0.15 || (elecFromBatPureEv > 0.15 && rearTorque > 0.3)))
        val isCoasting = (rpm == 0 && speed > 2.0 && !isReverse && !isParkOrNeutral && !isKineticRegen && !isPureEv)

        var rawIceShaftKw = 0.0
        var icePowerKw = 0.0
        var rearPowerKw = 0.0
        var rearFromBatKw = 0.0
        var totalOutput = 0.0
        var totalRegenKw = 0.0
        var rearRegenKw = 0.0

        when {
            isStandstill || isParkOrNeutral || isCoasting -> {
                hsgRegenKw = 0.0
                hsgPowerKw = 0.0
            }
            isKineticRegen -> {
                val totalR = Math.max(calcRearRegenKw + hsgRegenKw, batChargeKw).coerceIn(0.0, 25.0)
                val rearR = calcRearRegenKw.coerceAtMost(totalR)
                totalRegenKw = totalR
                rearRegenKw = rearR
                hsgRegenKw = (totalR - rearR).coerceAtLeast(0.0)
                hsgPowerKw = 0.0
            }
            isPureEv -> {
                val eDrive = Math.max(elecFromBatPureEv, calcRearDriveKw)
                rearPowerKw = eDrive
                rearFromBatKw = eDrive
                totalOutput = eDrive
                hsgRegenKw = 0.0
                hsgPowerKw = 0.0
            }
            isReverse && rpm >= 400 && !legacyReverseModel -> {
                // Cofanie silnikiem spalinowym przez bieg R skrzyni DW23 (wałek nieparzysty = 0x09).
                // Logi z jazdy: przy pracującym ICE tył miał moment tylko w 2% odczytów,
                // a ICE średnio ~29 Nm – więc liczymy jak jazdę hybrydową, nie jak napęd e-AWD.
                val shaftIceKw = if (r.iceTorqueKnown && iceTorque > 0.0) {
                    ((iceTorque * rpm) / 9549.0).coerceIn(0.0, 100.0)
                } else 0.0
                rawIceShaftKw = shaftIceKw
                val rearKw = calcRearDriveKw
                rearFromBatKw = if (rearKw > 0.0 && netTractionBatDischargeKw > 0.15) {
                    rearKw.coerceAtMost(netTractionBatDischargeKw * 0.90)
                } else 0.0
                val genToRearKw = (rearKw - rearFromBatKw).coerceAtLeast(0.0)
                icePowerKw = (shaftIceKw - genToRearKw).coerceAtLeast(0.0)
                rearPowerKw = rearKw
                totalOutput = icePowerKw + rearPowerKw + hsgPowerKw
                hsgRegenKw = 0.0
            }
            isReverse -> {
                // Cofanie na samym tylnym silniku elektrycznym (ICE zgaszony)
                val elecFromBat = (netTractionBatDischargeKw * 0.90).coerceIn(0.0, 20.0)
                val rPower = if (elecFromBat > 0.15) Math.max(elecFromBat, calcRearDriveKw) else calcRearDriveKw
                rearPowerKw = rPower
                rearFromBatKw = if (elecFromBat > 0.15) rPower else 0.0
                totalOutput = rPower
                hsgRegenKw = 0.0
                hsgPowerKw = 0.0
            }
            else -> {
                // Normalna jazda hybrydowa w przód
                val shaftIceKw = if (rpm >= 400 && r.iceTorqueKnown && iceTorque > 0.0) {
                    ((iceTorque * rpm) / 9549.0).coerceIn(0.0, 100.0)
                } else 0.0
                rawIceShaftKw = shaftIceKw

                val rearKw = if (isEradMechanicallyEngaged && rearTorque > 0.8) calcRearDriveKw else 0.0
                rearFromBatKw = if (rearKw > 0.0 && netTractionBatDischargeKw > 0.15) {
                    rearKw.coerceAtMost(netTractionBatDischargeKw * 0.90)
                } else 0.0

                // Część mocy tyłu pochodząca z generatora BSG (paskiem z wału) – odejmujemy od ICE, żeby nie liczyć podwójnie
                val genToRearKw = (rearKw - rearFromBatKw).coerceAtLeast(0.0)
                icePowerKw = (shaftIceKw - genToRearKw).coerceAtLeast(0.0)
                rearPowerKw = rearKw
                totalOutput = icePowerKw + rearPowerKw + hsgPowerKw
                hsgRegenKw = 0.0
            }
        }

        val frontPower = (icePowerKw + hsgPowerKw).coerceAtLeast(0.0)
        val rearPower = rearPowerKw.coerceAtLeast(0.0)

        val dynamicRearTorque = when {
            speed < 1.0 && activeRearRpm <= 15 -> 0.0
            !isEradMechanicallyEngaged -> 0.0
            Math.abs(rearTorque) > 0.5 -> rearTorque
            rearPowerKw > 0 && activeRearRpm > 0 -> (rearPowerKw * 9549.0 / activeRearRpm).coerceIn(0.0, 180.0)
            else -> 0.0
        }

        // Podział 4x4 przód / tył z wygładzaniem EMA
        val totalShare = frontPower + rearPower
        val rawFrontPct = when {
            isStandstill || isParkOrNeutral -> 50.0
            isPureEv -> 0.0
            isReverse && frontPower <= 0.15 -> 0.0 // cofanie EV: 100% tył
            isKineticRegen -> {
                val totReg = hsgRegenKw + rearRegenKw
                if (totReg > 0.2) ((hsgRegenKw / totReg) * 100.0).coerceIn(0.0, 100.0) else 0.0
            }
            totalShare > 0.3 -> when {
                rearPower <= 0.15 -> 100.0
                frontPower <= 0.15 -> 0.0
                else -> ((frontPower / totalShare) * 100.0).coerceIn(0.0, 100.0)
            }
            else -> 50.0
        }
        smoothedFrontPct = (smoothedFrontPct * 0.55) + (rawFrontPct * 0.45)
        val frontPct = Math.round(smoothedFrontPct).toInt().coerceIn(0, 100)

        val final12vSoc = r.ibsSoc?.takeIf { it in 5..100 } ?: 0

        // Prąd do całkowania tripu: tylko energia faktycznie pobrana / oddana do baterii 48V
        val activeHvAmp = if (Math.abs(hvAmp) >= 0.25) {
            hvAmp
        } else {
            val safeV = if (hvVolt > 35.0) hvVolt else 48.0
            when {
                totalRegenKw > 0.05 -> (totalRegenKw * 1000.0) / safeV
                rearFromBatKw > 0.02 || hsgPowerKw > 0.02 -> -((rearFromBatKw + hsgPowerKw) * 1000.0) / safeV
                else -> 0.0
            }
        }

        val fuelShaftKw = if (rawIceShaftKw > 0.0) rawIceShaftKw else icePowerKw
        trip.update(
            voltage = if (hvVolt > 35.0) hvVolt else 48.0,
            current = activeHvAmp,
            speedKmH = speed,
            iceRpm = rpm,
            fuelRateLh = FuelModel.fuelRateLh(fuelShaftKw),
            isRegenActive = isKineticRegen,
            regenPowerKw = totalRegenKw,
            rearMotorPowerKw = rearFromBatKw
        )

        val mode = when {
            isStandstill -> when {
                rpm > 200 && batChargeKw > 0.5 -> HybridMode.CHARGING_48V
                rpm > 200 || isParkOrNeutral -> HybridMode.STANDSTILL
                else -> HybridMode.EV_READY
            }
            isParkOrNeutral -> if (rpm > 200 && batChargeKw > 0.5) HybridMode.CHARGING_48V else HybridMode.STANDBY
            isKineticRegen -> HybridMode.REGEN
            isCoasting -> HybridMode.COASTING
            isPureEv -> HybridMode.PURE_EV
            isReverse -> if (rpm >= 400 && !legacyReverseModel) HybridMode.REVERSE_ICE else HybridMode.REVERSE
            rearPowerKw > 0.3 && rearFromBatKw <= 0.2 -> HybridMode.AWD_4X4
            icePowerKw > 8.0 && rearPowerKw > 2.5 -> HybridMode.EAWD_BOOST
            rearPowerKw > 0.5 -> HybridMode.HYBRID_EAWD
            icePowerKw > 0.5 || rpm > 300 -> HybridMode.HYBRID_AUTO
            else -> HybridMode.EV_READY
        }

        val eGear = if (r.eGear.isNotBlank() && r.eGear != "-") r.eGear
        else PidDecoders.decodeEradGear(r.eradGearRaw, isReverse, r.eGear)

        // Sample & hold: przy nieudanym odczycie w następnym cyklu zostaje ostatnia wyświetlona wartość
        r.rearRpm = activeRearRpm
        r.rearTorque = dynamicRearTorque
        r.eGear = eGear

        return TelemetryData(
            speedKmH = speed,
            currentGear = gearStr,
            eGear = eGear,
            iceRpm = rpm,
            icePowerKw = icePowerKw,
            rearMotorPowerKw = rearPowerKw,
            hsgPowerKw = hsgPowerKw,
            totalOutputPowerKw = totalOutput,
            totalRegenPowerKw = totalRegenKw,
            rearRegenPowerKw = rearRegenKw,
            iceTorqueNm = iceTorque,
            rearMotorTorqueNm = dynamicRearTorque,
            rearMotorRpm = activeRearRpm,
            hsgLoadPercent = r.hsgLoad,
            hvBatterySocPercent = r.hvSoc,
            hvBatteryVoltage = hvVolt,
            hvBatteryCurrent = hvAmp,
            bat12vVoltage = r.v12,
            bat12vCurrent = r.bat12vAmps,
            bat12vSocPercent = final12vSoc,
            bat12vPowerWatts = r.bat12vWatts,
            coolantTempC = r.coolant,
            oilTempC = r.oilTemp,
            gearboxTempC = r.gearboxTemp,
            rearMotorTempC = r.rearTemp,
            fuelInjectionBoostActive = r.fuelBoost,
            evInhibits = PidDecoders.decodeEvInhibits(r),
            frontTorquePercent = frontPct,
            rearTorquePercent = 100 - frontPct,
            gForce = longitudinalG(speed),
            hybridMode = mode,
            tripDistanceKm = trip.getDistanceKm(),
            tripEvDistancePercent = trip.getEvDistancePercent(),
            tripEvEnergySharePercent = trip.getEvEnergySharePercent(),
            tripRegenKWh = trip.getRegenKWh(),
            tripUsedElecKWh = trip.getUsedElecKWh(),
            tripFuelSavedLiters = trip.getFuelSavedLiters()
        )
    }

    /**
     * Przyspieszenie wzdłużne [g] z pochodnej prędkości OBD (rozdzielczość 1 km/h, więc mocno wygładzone).
     * Wcześniej w trybie rzeczywistym zawsze 0 – wartość była tylko w symulacji demo.
     */
    private fun longitudinalG(speedKmH: Double): Double {
        val now = clock()
        if (lastSpeedKmH.isNaN() || now - lastSpeedTs > 2000L) {
            lastSpeedKmH = speedKmH
            lastSpeedTs = now
            smoothedG = 0.0
            return 0.0
        }
        val dtMs = now - lastSpeedTs
        if (dtMs < 150L) return smoothedG // za krótki krok przy 1 km/h rozdzielczości
        val accel = ((speedKmH - lastSpeedKmH) / 3.6) / (dtMs / 1000.0) // m/s²
        val g = (accel / 9.81).coerceIn(-1.2, 1.2)
        smoothedG = smoothedG * 0.7 + g * 0.3
        lastSpeedKmH = speedKmH
        lastSpeedTs = now
        return smoothedG
    }
}
