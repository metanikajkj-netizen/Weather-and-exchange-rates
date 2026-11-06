package com.weathercurrency.app.data

import android.content.Context
import com.weathercurrency.app.location.CityResolver
import com.weathercurrency.app.location.LocationOutcome
import com.weathercurrency.app.location.LocationProvider
import com.weathercurrency.app.model.WeatherResult
import com.weathercurrency.app.model.WeatherSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Orchestrates location → city label → weather fetch → cache.
 * Errors are mapped to precise Russian UI messages (not a single generic one).
 */
class WeatherRepository(
    private val context: Context,
    private val api: OpenMeteoClient = OpenMeteoClient(),
    private val cache: CacheStore = CacheStore(context),
    private val locationProvider: LocationProvider = LocationProvider(context)
) {

    suspend fun refresh(forceNewLocation: Boolean = true): WeatherResult =
        withContext(Dispatchers.IO) {
            val cached = cache.loadWeather()

            if (!NetworkDiag.isOnline(context)) {
                return@withContext WeatherResult.Failure(
                    "Нет подключения к интернету",
                    cached
                )
            }

            val location = when (val outcome = locationProvider.getCurrentLocation()) {
                is LocationOutcome.Success -> outcome.location

                LocationOutcome.PermissionDenied ->
                    return@withContext WeatherResult.Failure(
                        "Нет разрешения на геолокацию", cached
                    )

                LocationOutcome.ProvidersDisabled ->
                    return@withContext WeatherResult.Failure(
                        "Геолокация выключена", cached
                    )

                LocationOutcome.Timeout, LocationOutcome.Unavailable ->
                    if (cached != null) {
                        return@withContext fetchFor(
                            cached.latitude,
                            cached.longitude,
                            cached.cityLabel,
                            cached
                        )
                    } else {
                        return@withContext WeatherResult.Failure(
                            "Геолокация недоступна: не удалось определить местоположение",
                            null
                        )
                    }
            }

            val lat = location.latitude
            val lon = location.longitude
            val city = if (forceNewLocation || cached == null) {
                // Settlement label only; GPS lat/lon below stay unchanged for the weather API.
                CityResolver.resolve(context, lat, lon)
            } else {
                cached.cityLabel
            }
            fetchFor(lat, lon, city, cached)
        }

    private suspend fun fetchFor(
        lat: Double,
        lon: Double,
        city: String,
        cached: WeatherSnapshot?
    ): WeatherResult {
        return try {
            val parsed = api.fetch(lat, lon)
            val snapshot = WeatherSnapshot(
                cityLabel = city,
                latitude = lat,
                longitude = lon,
                current = parsed.current,
                forecast = parsed.forecast,
                fetchedAtMillis = System.currentTimeMillis()
            )
            cache.saveWeather(snapshot)
            NetworkDiag.logInfo("Weather", "refresh ok cityLen=${city.length}")
            WeatherResult.Success(snapshot, fromCache = false)
        } catch (e: SecurityException) {
            NetworkDiag.logError("Weather.refresh", e)
            WeatherResult.Failure("Нет разрешения на геолокацию", cached)
        } catch (e: ApiError) {
            NetworkDiag.logError("Weather.refresh", e)
            WeatherResult.Failure(uiMessage(e), cached)
        } catch (e: Exception) {
            NetworkDiag.logError("Weather.refresh", e)
            WeatherResult.Failure("Ошибка обработки ответа погоды", cached)
        }
    }

    suspend fun cached(): WeatherSnapshot? = cache.loadWeather()

    /** Maps typed API errors to short user-facing Russian messages. */
    internal fun uiMessage(e: ApiError): String = when (e) {
        is ApiError.Network -> e.message ?: "Нет подключения к интернету"
        is ApiError.Http -> when {
            e.code >= 500 -> "Сервис погоды недоступен (HTTP ${e.code})"
            e.code == 404 -> "Сервис погоды: неверный запрос (HTTP 404)"
            else -> "Ошибка HTTP ${e.code} при загрузке погоды"
        }
        is ApiError.Parse -> "Ошибка обработки ответа погоды"
        is ApiError.Empty -> "Ошибка обработки ответа погоды"
    }
}
