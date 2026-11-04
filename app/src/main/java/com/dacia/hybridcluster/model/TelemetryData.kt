package com.dacia.hybridcluster.model

/** Wartość temperatury oznaczająca brak odczytu. */
const val NO_TEMP = -999.0

data class TelemetryData(
    // Prędkość i skrzynia
    val speedKmH: Double = 0.0,
    val currentGear: String = "-", // "P", "R", "N", "D1".."D6"
    val eGear: String = "-",       // Bieg tylnej przekładni eRAD: "E1", "E2", "R1", "R2", "N"
    val iceRpm: Int = 0,

    // Moce mechaniczne (kW)
    val icePowerKw: Double = 0.0,
    val rearMotorPowerKw: Double = 0.0,
    val hsgPowerKw: Double = 0.0,
    val totalOutputPowerKw: Double = 0.0,
    val totalRegenPowerKw: Double = 0.0,
    val rearRegenPowerKw: Double = 0.0,

    // Momenty obrotowe (diagnostyka)
    val iceTorqueNm: Double = 0.0,
    val rearMotorTorqueNm: Double = 0.0,
    val rearMotorRpm: Int = 0,

    // Generator BSG 48V – obciążenie z EMS3253 (220157)
    val hsgLoadPercent: Double = 0.0,

    // Bateria 48V (BMS Sunwoda)
    val hvBatterySocPercent: Double = 0.0,
    val hvBatteryVoltage: Double = 0.0,
    val hvBatteryCurrent: Double = 0.0, // dodatnie = ładowanie, ujemne = rozładowanie

    // Instalacja 12V – przetwornica DC-DC 48V/12V
    val bat12vVoltage: Double = 0.0,
    val bat12vCurrent: Double = 0.0,    // prąd wyjściowy DC-DC (A)
    val bat12vSocPercent: Int = 0,
    val bat12vPowerWatts: Double = 0.0, // moc wyjściowa DC-DC (U * I)

    // Temperatury (NO_TEMP = brak odczytu)
    val coolantTempC: Double = NO_TEMP,
    val oilTempC: Double = NO_TEMP,
    val gearboxTempC: Double = NO_TEMP,
    val rearMotorTempC: Double = NO_TEMP,

    // Kontrolki
    val fuelInjectionBoostActive: Boolean = false,
    val evInhibits: List<EvInhibit> = emptyList(),

    // Napęd 4x4
    val frontTorquePercent: Int = 100,
    val rearTorquePercent: Int = 0,
    val gForce: Double = 0.0, // przyspieszenie wzdłużne (z pochodnej prędkości)

    // Tryb pracy
    val hybridMode: HybridMode = HybridMode.EV_READY,

    // Statystyki podróży
    val tripDistanceKm: Double = 0.0,
    val tripEvDistancePercent: Double = 0.0,
    val tripEvEnergySharePercent: Double = 0.0,
    val tripRegenKWh: Double = 0.0,
    val tripUsedElecKWh: Double = 0.0,
    val tripFuelSavedLiters: Double = 0.0
)
