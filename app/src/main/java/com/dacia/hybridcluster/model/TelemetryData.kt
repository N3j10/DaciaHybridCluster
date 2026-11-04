package com.dacia.hybridcluster.model

data class TelemetryData(
    // Prędkość i skrzynia
    val speedKmH: Double = 0.0,
    val currentGear: String = "-", // "P", "R", "N", "D1", "D2", "D3", "D4", "D5", "D6"
    val eGear: String = "-",       // Bieg silnika elektrycznego: "EV1", "EV2", "N"
    val iceRpm: Int = 0,

    // Moce mechaniczne i elektryczne (kW)
    val icePowerKw: Double = 0.0,
    val rearMotorPowerKw: Double = 0.0,
    val hsgPowerKw: Double = 0.0,
    val totalOutputPowerKw: Double = 0.0,
    val totalRegenPowerKw: Double = 0.0,
    val rearRegenPowerKw: Double = 0.0,

    // Momenty obrotowe
    val iceTorqueNm: Double = 0.0,
    val rearMotorTorqueNm: Double = 0.0,
    val rearMotorRpm: Int = 0,

    // Bateria i generator 48V MHEV
    val hsgVoltage48v: Double = 0.0,
    val hsgLoadPercent: Double = 0.0,
    val hsgPowerWatts: Double = 0.0,

    // Bateria Trakcyjna (HV jeśli dostępna)
    val hvBatterySocPercent: Double = 0.0,
    val hvBatteryVoltage: Double = 0.0,
    val hvBatteryCurrent: Double = 0.0, // ujemne = regen, dodatnie = rozładowanie
    val hvBatteryTempC: Double = -999.0,

    // Akumulator 12V i Przetwornica DC-DC
    val bat12vVoltage: Double = 0.0,
    val bat12vCurrent: Double = 0.0,       // Całkowity prąd z DC-DC (A)
    val bat12vSocPercent: Int = 0,
    val bat12vPowerWatts: Double = 0.0,    // Łączna moc wyjściowa DC-DC (U * I)
    val bat12vLoadWatts: Double = 0.0,     // Moc pobierana przez urządzenia pokładowe (światła, elektronika)
    val bat12vNetChargeWatts: Double = 0.0,// Rzeczywista moc ładowania/rozładowania akumulatora 12V (+ ładowanie, - rozładowanie)

    // Temperatury (-999.0 oznacza brak odczytu)
    val coolantTempC: Double = -999.0,
    val oilTempC: Double = -999.0, // Temperatura oleju silnikowego (ECM 22100C)
    val gearboxTempC: Double = -999.0, // Temperatura oleju skrzyni biegów (ECM 2230C1)
    val rearMotorTempC: Double = -999.0,
    val rearInverterTempC: Double = -999.0,

    // Parametry silnika TCe & LPG
    val boostPressureMbar: Double = 0.0,
    val fuelRailPressureBar: Double = 0.0,
    val fuelInjectionBoostActive: Boolean = false, // Kontrolka: Dotrysk paliwa
    val startStopInhibitReason: String = "",       // Komunikat przyczyny blokady Start & Stop / EV (pusty = brak blokady)

    // Napęd 4x4 & Teren
    val frontTorquePercent: Int = 100,
    val rearTorquePercent: Int = 0,
    val gForce: Double = 0.0,
    val pitchDegrees: Double = 0.0, // kąt wzniesienia/zjazdu

    // Tryb pracy
    val hybridMode: String = "EV / READY",

    // Statystyki podróży (Trip & Bilans Energii)
    val tripDistanceKm: Double = 0.0,
    val tripEvDistancePercent: Double = 0.0,
    val tripEvEnergySharePercent: Double = 0.0,
    val tripRegenKWh: Double = 0.0,
    val tripUsedElecKWh: Double = 0.0,
    val tripFuelSavedLiters: Double = 0.0
)
