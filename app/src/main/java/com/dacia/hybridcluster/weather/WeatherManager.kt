package com.dacia.hybridcluster.weather

import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request

data class WeatherInfo(
    val temperatureC: Double = 18.5,
    val weatherCondition: String = "Częściowo pochmurno",
    val precipitationMm: Double = 0.0,
    val windSpeedKmH: Double = 12.0,
    val forecast3h: List<HourlyForecast> = listOf(
        HourlyForecast("15:00", 18.0, 0.0),
        HourlyForecast("16:00", 17.5, 0.1),
        HourlyForecast("17:00", 16.0, 0.0)
    )
)

data class HourlyForecast(
    val time: String,
    val tempC: Double,
    val rainMm: Double
)

object WeatherManager {
    private val client = OkHttpClient()
    private val gson = Gson()

    private val _weatherState = MutableStateFlow(WeatherInfo())
    val weatherState: StateFlow<WeatherInfo> = _weatherState

    fun startPolling(scope: CoroutineScope, lat: Double = 0.0, lon: Double = 0.0) {
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                fetchWeather(lat, lon)
                delay(10 * 60 * 1000) // co 10 minut
            }
        }
    }

    private fun fetchWeather(lat: Double, lon: Double) {
        try {
            val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,precipitation,weather_code,wind_speed_10m&hourly=temperature_2m,precipitation&forecast_days=1"
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return
                    val json = gson.fromJson(body, OpenMeteoResponse::class.java)

                    val condition = decodeWmoCode(json.current?.weather_code ?: 0)
                    val temp = json.current?.temperature_2m ?: 18.0
                    val rain = json.current?.precipitation ?: 0.0
                    val wind = json.current?.wind_speed_10m ?: 10.0

                    _weatherState.value = WeatherInfo(
                        temperatureC = temp,
                        weatherCondition = condition,
                        precipitationMm = rain,
                        windSpeedKmH = wind
                    )
                }
            }
        } catch (_: Exception) {
            // fallback do domyślnych danych przy braku internetu
        }
    }

    private fun decodeWmoCode(code: Int): String {
        return when (code) {
            0 -> "Bezchmurnie (Słonecznie)"
            1, 2, 3 -> "Częściowo pochmurno"
            45, 48 -> "Mgła"
            51, 53, 55 -> "Mżawka"
            61, 63, 65 -> "Deszcz"
            71, 73, 75 -> "Opady śniegu"
            80, 81, 82 -> "Ulewa"
            95, 96, 99 -> "Burza z piorunami"
            else -> "Umiarkowane zachmurzenie"
        }
    }

    private data class OpenMeteoResponse(
        val current: CurrentUnits?
    )
    private data class CurrentUnits(
        val temperature_2m: Double?,
        val precipitation: Double?,
        val weather_code: Int?,
        val wind_speed_10m: Double?
    )
}
