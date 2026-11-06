package com.weathercurrency.app.model

/** Current weather conditions from Open-Meteo (subset we display). */
data class CurrentWeather(
    val temperatureC: Double,
    val apparentTemperatureC: Double,
    val humidityPercent: Int,
    val windSpeedKmh: Double,
    val weatherCode: Int,
    val timeIso: String
)

/** One day of the 3-day forecast. */
data class ForecastDay(
    /** ISO date yyyy-MM-dd as returned by the API (already in local timezone). */
    val dateIso: String,
    val weatherCode: Int,
    val minC: Double,
    val maxC: Double
)

/** Full weather snapshot persisted as cache. */
data class WeatherSnapshot(
    val cityLabel: String,
    val latitude: Double,
    val longitude: Double,
    val current: CurrentWeather,
    val forecast: List<ForecastDay>,
    /** Epoch millis of last successful fetch. */
    val fetchedAtMillis: Long
)

/** Result of a weather repository operation. */
sealed class WeatherResult {
    data class Success(val snapshot: WeatherSnapshot, val fromCache: Boolean) : WeatherResult()
    data class Failure(val message: String, val cached: WeatherSnapshot?) : WeatherResult()
}
