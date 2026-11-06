package com.weathercurrency.app

import com.google.gson.Gson
import com.weathercurrency.app.model.CurrencyRate
import com.weathercurrency.app.model.CurrencySnapshot
import com.weathercurrency.app.model.CurrentWeather
import com.weathercurrency.app.model.ForecastDay
import com.weathercurrency.app.model.WeatherSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Cache serialization round-trip tests (DataStore stores JSON produced by Gson).
 * Verifies last successful payload survives save/load without data loss.
 */
class CacheSerializationTest {

    private val gson = Gson()

    private val weather = WeatherSnapshot(
        cityLabel = "Москва",
        latitude = 55.75,
        longitude = 37.62,
        current = CurrentWeather(
            temperatureC = 5.5,
            apparentTemperatureC = 2.0,
            humidityPercent = 70,
            windSpeedKmh = 10.0,
            weatherCode = 3,
            timeIso = "2026-11-04T14:00"
        ),
        forecast = listOf(
            ForecastDay("2026-11-04", 3, 1.0, 7.0),
            ForecastDay("2026-11-05", 61, 0.0, 4.0),
            ForecastDay("2026-11-06", 71, -3.0, 1.0)
        ),
        fetchedAtMillis = 1_700_000_000_000L
    )

    private val currency = CurrencySnapshot(
        rates = listOf(
            CurrencyRate("USD", 92.1, 1),
            CurrencyRate("EUR", 99.5, 1),
            CurrencyRate("CNY", 12.83, 10)
        ),
        rateDate = "04.11.2026",
        fetchedAtMillis = 1_700_000_000_000L
    )

    @Test
    fun weatherRoundTrip() {
        val json = gson.toJson(weather)
        val back = gson.fromJson(json, WeatherSnapshot::class.java)
        assertEquals(weather.cityLabel, back.cityLabel)
        assertEquals(weather.latitude, back.latitude, 0.0)
        assertEquals(weather.longitude, back.longitude, 0.0)
        assertEquals(weather.current.temperatureC, back.current.temperatureC, 0.0)
        assertEquals(weather.current.humidityPercent, back.current.humidityPercent)
        assertEquals(weather.forecast.size, back.forecast.size)
        assertEquals(weather.forecast[2].weatherCode, back.forecast[2].weatherCode)
        assertEquals(weather.fetchedAtMillis, back.fetchedAtMillis)
    }

    @Test
    fun currencyRoundTrip() {
        val json = gson.toJson(currency)
        val back = gson.fromJson(json, CurrencySnapshot::class.java)
        assertEquals(currency.rateDate, back.rateDate)
        assertEquals(currency.rates.size, back.rates.size)
        assertEquals(92.1, back.rate("USD")!!.ratePerUnit, 0.0001)
        assertEquals(10, back.rate("CNY")!!.nominal)
        assertEquals(currency.fetchedAtMillis, back.fetchedAtMillis)
    }

    @Test
    fun corruptedJsonReturnsNullSemantics() {
        // Mirrors CacheStore.load* catch branch behaviour.
        val bad = "{not valid"
        val parsed: WeatherSnapshot? = try {
            gson.fromJson(bad, WeatherSnapshot::class.java)
        } catch (_: Exception) {
            null
        }
        assertNull(parsed)
    }

    @Test
    fun emptyJsonStringHandled() {
        val parsed: CurrencySnapshot? = try {
            gson.fromJson("", CurrencySnapshot::class.java)
        } catch (_: Exception) {
            null
        }
        // Gson may return null for empty input
        assertNull(parsed)
    }

    @Test
    fun cacheKeepsLastKnownValuesWhenOffline() {
        // Simulates offline path: UI must keep showing snapshot from cache.
        val cached: WeatherSnapshot? = gson.fromJson(gson.toJson(weather), WeatherSnapshot::class.java)
        assertNotNull(cached)
        assertEquals("Москва", cached!!.cityLabel)
        assertEquals(3, cached.forecast.size)
    }
}
