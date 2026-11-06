package com.weathercurrency.app.data

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.weathercurrency.app.model.CurrentWeather
import com.weathercurrency.app.model.ForecastDay
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/**
 * Open-Meteo forecast client with official public endpoint fallback.
 *
 * Primary host `api.open-meteo.com` is unreachable from some networks
 * (TCP timeout observed on RU mobile/VPS paths). The same documented
 * /v1/forecast schema is served by the official
 * `historical-forecast-api.open-meteo.com` host, which is used as fallback.
 * Docs: https://open-meteo.com/en/docs
 */
class OpenMeteoClient(
    private val http: OkHttpClient = defaultClient(),
    private val gson: Gson = Gson(),
    /** Ordered base URLs; first success wins. */
    private val endpoints: List<String> = DEFAULT_ENDPOINTS
) {

    data class ParsedWeather(
        val current: CurrentWeather,
        val forecast: List<ForecastDay>,
        val timezone: String
    )

    /**
     * Tries each endpoint in order. Throws [ApiError] with a precise cause.
     */
    @Throws(ApiError::class)
    fun fetch(latitude: Double, longitude: Double): ParsedWeather {
        if (endpoints.isEmpty()) throw ApiError.Empty("Нет доступных эндпоинтов погоды")

        var lastError: ApiError? = null
        var sawHttpError = false
        var sawParseError = false
        var networkFailed = false

        for (base in endpoints) {
            val url = buildUrl(base, latitude, longitude) ?: continue
            val request = Request.Builder().url(url).get()
                .header("Accept", "application/json")
                .build()

            try {
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        sawHttpError = true
                        lastError = ApiError.Http(response.code, "Open-Meteo HTTP ${response.code}")
                        NetworkDiag.logInfo("OpenMeteo", "HTTP ${response.code} at host=${hostOf(base)}")
                        return@use
                    }
                    val body = response.body?.string()
                    if (body.isNullOrBlank()) {
                        lastError = ApiError.Empty("Open-Meteo: пустой ответ")
                        return@use
                    }
                    try {
                        return parse(body)
                    } catch (e: ApiError.Parse) {
                        sawParseError = true
                        lastError = e
                        NetworkDiag.logError("OpenMeteo.parse@${hostOf(base)}", e)
                    }
                }
            } catch (e: ApiError) {
                lastError = e
            } catch (e: SocketTimeoutException) {
                networkFailed = true
                lastError = ApiError.Network("Погода: истёк таймаут подключения", e)
                NetworkDiag.logError("OpenMeteo@${hostOf(base)}", e)
            } catch (e: UnknownHostException) {
                networkFailed = true
                lastError = ApiError.Network("Погода: сервер не найден (DNS)", e)
                NetworkDiag.logError("OpenMeteo@${hostOf(base)}", e)
            } catch (e: SSLException) {
                networkFailed = true
                lastError = ApiError.Network("Погода: ошибка TLS/SSL", e)
                NetworkDiag.logError("OpenMeteo@${hostOf(base)}", e)
            } catch (e: IOException) {
                networkFailed = true
                lastError = ApiError.Network("Погода: сервер недоступен", e)
                NetworkDiag.logError("OpenMeteo@${hostOf(base)}", e)
            } catch (e: Exception) {
                lastError = ApiError.Parse("Погода: ошибка обработки ответа", e)
                NetworkDiag.logError("OpenMeteo@${hostOf(base)}", e)
            }
        }

        // Prefer a more specific reason when we have one.
        throw when {
            lastError != null && sawParseError && !networkFailed -> lastError!!
            lastError != null && sawHttpError && !networkFailed -> lastError!!
            networkFailed -> lastError ?: ApiError.Network("Погода: сервер недоступен")
            lastError != null -> lastError!!
            else -> ApiError.Network("Погода: все эндпоинты недоступны")
        }
    }

    fun buildUrl(base: String, latitude: Double, longitude: Double): HttpUrl? {
        val trimmed = base.trimEnd('/')
        return "$trimmed/v1/forecast".toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("latitude", latitude.toString())
            ?.addQueryParameter("longitude", longitude.toString())
            ?.addQueryParameter(
                "current",
                "temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m"
            )
            ?.addQueryParameter(
                "daily",
                "weather_code,temperature_2m_max,temperature_2m_min"
            )
            ?.addQueryParameter("timezone", "auto")
            ?.addQueryParameter("forecast_days", "3")
            ?.addQueryParameter("wind_speed_unit", "kmh")
            ?.build()
    }

    /** Back-compat helper used by tests. */
    fun buildUrl(latitude: Double, longitude: Double): HttpUrl =
        buildUrl(endpoints.first(), latitude, longitude)
            ?: throw ApiError.Empty("Некорректный URL погоды")

    /**
     * Parses Open-Meteo JSON (same schema on all official hosts).
     * Throws [ApiError.Parse] / [ApiError.Empty] on bad payloads.
     */
    @Throws(ApiError::class)
    fun parse(json: String): ParsedWeather {
        val root = try {
            gson.fromJson(json, JsonObject::class.java)
        } catch (e: Exception) {
            throw ApiError.Parse("Погода: некорректный JSON", e)
        } ?: throw ApiError.Empty("Погода: пустой JSON")

        // Explicit Open-Meteo error object
        if (root.has("error") && root.get("error").isJsonPrimitive) {
            val reason = if (root.has("reason")) root.get("reason").asString else "ошибка API"
            throw ApiError.Parse("Погода: $reason")
        }

        val currentObj = root.getAsJsonObject("current")
            ?: throw ApiError.Parse("Погода: нет блока current")
        val dailyObj = root.getAsJsonObject("daily")
            ?: throw ApiError.Parse("Погода: нет блока daily")

        val temperature = currentObj.optionalDouble("temperature_2m")
            ?: throw ApiError.Parse("Погода: нет temperature_2m")
        val apparent = currentObj.optionalDouble("apparent_temperature") ?: temperature
        val humidity = currentObj.optionalInt("relative_humidity_2m") ?: 0
        val wind = currentObj.optionalDouble("wind_speed_10m") ?: 0.0
        val code = currentObj.optionalInt("weather_code") ?: 0
        val time = currentObj.optionalString("time") ?: ""

        val times = dailyObj.stringArray("time")
        if (times.isEmpty()) throw ApiError.Empty("Погода: пустой прогноз")
        val codes = dailyObj.intArray("weather_code")
        val maxes = dailyObj.doubleArray("temperature_2m_max")
        val mins = dailyObj.doubleArray("temperature_2m_min")

        val forecast = times.mapIndexedNotNull { index, date ->
            val max = maxes.getOrNull(index) ?: return@mapIndexedNotNull null
            val min = mins.getOrNull(index) ?: return@mapIndexedNotNull null
            ForecastDay(
                dateIso = date,
                weatherCode = codes.getOrNull(index) ?: 0,
                minC = min,
                maxC = max
            )
        }
        if (forecast.isEmpty()) throw ApiError.Empty("Погода: нет дневных данных")

        val timezone = root.optionalString("timezone") ?: "auto"

        return ParsedWeather(
            current = CurrentWeather(
                temperatureC = temperature,
                apparentTemperatureC = apparent,
                humidityPercent = humidity,
                windSpeedKmh = wind,
                weatherCode = code,
                timeIso = time
            ),
            forecast = forecast,
            timezone = timezone
        )
    }

    private fun hostOf(base: String): String = try {
        base.toHttpUrlOrNull()?.host ?: base
    } catch (_: Exception) {
        base
    }

    private fun JsonObject.optionalDouble(name: String): Double? =
        if (has(name) && !get(name).isJsonNull) try {
            get(name).asDouble
        } catch (_: Exception) {
            null
        } else null

    private fun JsonObject.optionalInt(name: String): Int? =
        if (has(name) && !get(name).isJsonNull) try {
            get(name).asInt
        } catch (_: Exception) {
            null
        } else null

    private fun JsonObject.optionalString(name: String): String? =
        if (has(name) && !get(name).isJsonNull) try {
            get(name).asString
        } catch (_: Exception) {
            null
        } else null

    private fun JsonObject.stringArray(name: String): List<String> {
        if (!has(name) || !get(name).isJsonArray) return emptyList()
        return get(name).asJsonArray.mapNotNull {
            try {
                it.asString
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun JsonObject.intArray(name: String): List<Int> {
        if (!has(name) || !get(name).isJsonArray) return emptyList()
        return get(name).asJsonArray.mapNotNull {
            try {
                it.asInt
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun JsonObject.doubleArray(name: String): List<Double> {
        if (!has(name) || !get(name).isJsonArray) return emptyList()
        return get(name).asJsonArray.mapNotNull {
            try {
                it.asDouble
            } catch (_: Exception) {
                null
            }
        }
    }

    companion object {
        /**
         * Official public hosts with the same /v1/forecast contract.
         * customer-api.open-meteo.com is intentionally NOT listed (requires API key).
         */
        val DEFAULT_ENDPOINTS: List<String> = listOf(
            "https://api.open-meteo.com",
            "https://historical-forecast-api.open-meteo.com"
        )

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(18, TimeUnit.SECONDS)
            .writeTimeout(12, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
