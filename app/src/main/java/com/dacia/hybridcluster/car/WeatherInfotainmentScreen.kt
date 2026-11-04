package com.dacia.hybridcluster.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.*
import androidx.lifecycle.lifecycleScope
import com.dacia.hybridcluster.obd.ObdManager
import com.dacia.hybridcluster.weather.WeatherManager
import kotlinx.coroutines.launch

class WeatherInfotainmentScreen(carContext: CarContext) : Screen(carContext) {

    init {
        // Nasłuchiwanie zmian pogody i telemetrii w celu odświeżania ekranu środkowego
        lifecycleScope.launch {
            WeatherManager.weatherState.collect {
                invalidate()
            }
        }
        lifecycleScope.launch {
            ObdManager.telemetryState.collect {
                invalidate()
            }
        }
    }

    override fun onGetTemplate(): Template {
        val weather = WeatherManager.weatherState.value
        val telemetry = ObdManager.telemetryState.value

        val tempRow = Row.Builder()
            .setTitle("${weather.temperatureC}°C  •  ${weather.weatherCondition}")
            .addText("Odczuwalna przy obecnej prędkości: ${(weather.temperatureC - 1.5)}°C")
            .build()

        val rainRow = Row.Builder()
            .setTitle("Opady deszczu / śniegu")
            .addText(
                if (weather.precipitationMm > 0.0)
                    "${weather.precipitationMm} mm/h (Wycieraczki aktywne)"
                else
                    "Brak opadów w promieniu podróży (Radar czysty)"
            )
            .build()

        val windRow = Row.Builder()
            .setTitle("Wiatr i warunki drogowe")
            .addText("Prędkość wiatru: ${weather.windSpeedKmH} km/h  •  Opór powietrza w normie")
            .build()

        val vehicleRow = Row.Builder()
            .setTitle("Podsumowanie napędu E-Tech")
            .addText("Tryb: ${telemetry.hybridMode}  |  Bateria: ${telemetry.hvBatterySocPercent.toInt()}%  |  12V: ${telemetry.bat12vVoltage}V")
            .build()

        val pane = Pane.Builder()
            .addRow(tempRow)
            .addRow(rainRow)
            .addRow(windRow)
            .addRow(vehicleRow)
            .build()

        return PaneTemplate.Builder(pane)
            .setTitle("Stacja Pogodowa & Warunki Drogowe")
            .setHeaderAction(Action.APP_ICON)
            .build()
    }
}
