package com.dacia.hybridcluster.model

/**
 * Tryb pracy napędu wyliczany przez [com.dacia.hybridcluster.engine.PowertrainCalculator].
 * Tłumaczenia nazw są w [AppStrings.modeName] – nigdzie w kodzie nie porównujemy już napisów.
 */
enum class HybridMode {
    EV_READY,        // gotowość, silnik spalinowy wyłączony
    STANDSTILL,      // postój z pracującym silnikiem
    CHARGING_48V,    // postój / P / N – silnik ładuje baterię 48V
    STANDBY,         // P lub N w ruchu
    REGEN,           // rekuperacja kinetyczna
    COASTING,        // żeglowanie, ICE wyłączony
    PURE_EV,         // jazda wyłącznie na tylnym silniku
    REVERSE,         // bieg wsteczny na tylnym silniku elektrycznym (ICE zgaszony)
    REVERSE_ICE,     // bieg wsteczny R skrzyni DW23 – cofa silnik spalinowy
    AWD_4X4,         // tył napędzany prądem z przedniego generatora
    EAWD_BOOST,      // ICE + tył pod dużym obciążeniem
    HYBRID_EAWD,     // ICE + tył
    HYBRID_AUTO;     // ICE (FWD)

    /** Tryby, w których kolorujemy elementy na zielono (EV / odzysk). */
    val isGreen: Boolean get() = this == PURE_EV || this == REGEN
}

/**
 * Przyczyny blokady trybu EV / Stop & Start dekodowane z PID-ów EMS3253 (18DA15F1).
 * Kolejność deklaracji = kolejność wyświetlania.
 */
enum class EvInhibit {
    SWITCHED_OFF,     // 220311 = 36
    SYSTEM_FAULT,     // 220311 = 1, 17, 32
    SLOPE,            // 2, 18
    VEHICLE_MOVING,   // 3, 19 (tylko na postoju)
    ENERGY_12V_48V,   // 4, 20
    ENGINE_GPF,       // 5, 21
    BRAKE,            // 6, 22, 35
    HOOD,             // 7, 23, 42, 47
    DOOR,             // 8, 24, 45, 46
    SEATBELT,         // 9, 25, 43
    REVERSE_GEAR,     // 10, 26
    HVAC,             // 11, 27 lub flaga 22034F
    STEERING,         // 12, 28
    KEYCARD,          // 13, 29
    SYSTEM,           // 14, 30
    CLUTCH,           // 34
    PTC_HEATER,       // 2202D0
    VEEM_12V,         // 220142
    BATTERY_48V,      // 2201BD
    POWERTRAIN,       // 220322
    COOLING,          // 220254
    SAILING_CLUTCH,   // 220103
    BRAKE_ABS,        // 220313 / 22030C
    COLD_ENGINE       // tylko symulacja demo
}
