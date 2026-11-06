package com.weathercurrency.app

import com.weathercurrency.app.data.ApiError
import com.weathercurrency.app.data.OpenMeteoClient
import com.weathercurrency.app.data.WeatherCodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherParsingTest {

    private val client = OpenMeteoClient()

    private val sampleJson = """
        {
          "latitude": 55.75,
          "longitude": 37.62,
          "timezone": "Europe/Moscow",
          "current": {
            "time": "2026-11-04T14:00",
            "temperature_2m": 5.3,
            "relative_humidity_2m": 71,
            "apparent_temperature": 2.1,
            "weather_code": 3,
            "wind_speed_10m": 12.4
          },
          "daily": {
            "time": ["2026-11-04", "2026-11-05", "2026-11-06"],
            "weather_code": [3, 61, 71],
            "temperature_2m_max": [7.2, 4.0, 1.5],
            "temperature_2m_min": [1.0, -0.5, -3.2]
          }
        }
    """.trimIndent()

    @Test
    fun parseFullResponse() {
        val parsed = client.parse(sampleJson)
        assertEquals(5.3, parsed.current.temperatureC, 0.001)
        assertEquals(2.1, parsed.current.apparentTemperatureC, 0.001)
        assertEquals(71, parsed.current.humidityPercent)
        assertEquals(12.4, parsed.current.windSpeedKmh, 0.001)
        assertEquals(3, parsed.current.weatherCode)
        assertEquals("2026-11-04T14:00", parsed.current.timeIso)
        assertEquals("Europe/Moscow", parsed.timezone)
        assertEquals(3, parsed.forecast.size)
    }

    @Test
    fun forecastHasThreeDays() {
        val parsed = client.parse(sampleJson)
        assertEquals(3, parsed.forecast.size)
        assertEquals("2026-11-04", parsed.forecast[0].dateIso)
        assertEquals("2026-11-06", parsed.forecast[2].dateIso)
        assertEquals(61, parsed.forecast[1].weatherCode)
        assertEquals(7.2, parsed.forecast[0].maxC, 0.001)
        assertEquals(1.0, parsed.forecast[0].minC, 0.001)
        assertTrue(parsed.forecast[0].maxC >= parsed.forecast[0].minC)
    }

    @Test
    fun weatherCodesMapToRussian() {
        assertEquals("Ясно", WeatherCodes.describe(0).description)
        assertEquals("Облачно", WeatherCodes.describe(3).description)
        assertEquals("Туман", WeatherCodes.describe(45).description)
        assertEquals("Дождь", WeatherCodes.describe(63).description)
        assertEquals("Снег", WeatherCodes.describe(75).description)
        assertEquals("Гроза", WeatherCodes.describe(95).description)
        assertTrue(WeatherCodes.describe(0).icon.isNotEmpty())
    }

    @Test
    fun missingCurrentBlockThrowsParseError() {
        val e = assertThrows(ApiError.Parse::class.java) {
            client.parse("""{"daily":{"time":["2026-11-04"],"weather_code":[0],"temperature_2m_max":[1.0],"temperature_2m_min":[0.0]}}""")
        }
        assertTrue(e.message!!.contains("current"))
    }

    @Test
    fun missingTemperatureThrowsParseError() {
        assertThrows(ApiError.Parse::class.java) {
            client.parse(
                """
                {"current":{"weather_code":1},"daily":{"time":["2026-11-04"],"weather_code":[0],"temperature_2m_max":[1.0],"temperature_2m_min":[0.0]}}
                """.trimIndent()
            )
        }
    }

    @Test
    fun emptyDailyThrowsEmptyError() {
        assertThrows(ApiError.Empty::class.java) {
            client.parse(
                """
                {"current":{"temperature_2m":10.0,"weather_code":0},"daily":{"time":[],"weather_code":[],"temperature_2m_max":[],"temperature_2m_min":[]}}
                """.trimIndent()
            )
        }
    }

    @Test
    fun invalidJsonThrowsParseError() {
        assertThrows(ApiError.Parse::class.java) {
            client.parse("not-json{{{{")
        }
    }

    @Test
    fun openMeteoErrorObjectThrowsParse() {
        val e = assertThrows(ApiError.Parse::class.java) {
            client.parse("""{"error":true,"reason":"Invalid API key"}""")
        }
        assertTrue(e.message!!.contains("Invalid API key"))
    }

    @Test
    fun missingOptionalFieldsUseDefaults() {
        val parsed = client.parse(
            """
            {
              "current":{"temperature_2m":10.0,"weather_code":1,"time":"2026-11-04T10:00"},
              "daily":{"time":["2026-11-04"],"weather_code":[1],"temperature_2m_max":[12.0],"temperature_2m_min":[5.0]},
              "timezone":"UTC"
            }
            """.trimIndent()
        )
        assertEquals(10.0, parsed.current.temperatureC, 0.001)
        assertEquals(10.0, parsed.current.apparentTemperatureC, 0.001)
        assertEquals(0, parsed.current.humidityPercent)
        assertEquals(0.0, parsed.current.windSpeedKmh, 0.001)
    }

    @Test
    fun buildUrlContainsRequiredParams() {
        val url = client.buildUrl(55.75, 37.62).toString()
        assertTrue(url.startsWith("https://"))
        assertTrue(url.contains("latitude=55.75"))
        assertTrue(url.contains("longitude=37.62"))
        assertTrue(url.contains("timezone=auto"))
        assertTrue(url.contains("forecast_days=3"))
        assertTrue(url.contains("current="))
        assertTrue(url.contains("daily="))
    }

    @Test
    fun defaultEndpointsAreOfficialAndNoCustomerApi() {
        val eps = OpenMeteoClient.DEFAULT_ENDPOINTS
        assertTrue(eps.isNotEmpty())
        assertTrue(eps.all { it.startsWith("https://") })
        assertTrue(eps.none { it.contains("customer-api") })
        assertTrue(eps.any { it.contains("api.open-meteo.com") })
        // Fallback host with same schema (reachable when primary times out)
        assertTrue(eps.any { it.contains("historical-forecast-api.open-meteo.com") })
    }

    @Test
    fun buildUrlOnFallbackHostKeepsSchema() {
        val url = client.buildUrl(
            "https://historical-forecast-api.open-meteo.com",
            55.75, 37.62
        )!!
        assertTrue(url.toString().startsWith("https://historical-forecast-api.open-meteo.com/v1/forecast"))
        assertTrue(url.toString().contains("forecast_days=3"))
    }
}
