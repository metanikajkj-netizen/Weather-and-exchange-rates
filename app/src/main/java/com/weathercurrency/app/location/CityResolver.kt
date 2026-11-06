package com.weathercurrency.app.location

import android.content.Context
import android.location.Geocoder
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Locale

/**
 * Resolves a human-readable settlement label from coordinates.
 * Uses only locality / subAdminArea / adminArea — never house numbers or streets.
 * On failure returns [PlaceLabel.FALLBACK] («Моё местоположение»).
 * GPS coordinates used for weather requests are NOT modified here.
 */
object CityResolver {

    suspend fun resolve(context: Context, latitude: Double, longitude: Double): String =
        withContext(Dispatchers.IO) {
            try {
                if (!Geocoder.isPresent()) return@withContext PlaceLabel.FALLBACK
                val geocoder = Geocoder(context, Locale("ru"))
                val addr = if (Build.VERSION.SDK_INT >= 33) {
                    val latch = java.util.concurrent.CountDownLatch(1)
                    var result: android.location.Address? = null
                    geocoder.getFromLocation(latitude, longitude, 1) { addresses ->
                        result = addresses.firstOrNull()
                        latch.countDown()
                    }
                    latch.await(8, java.util.concurrent.TimeUnit.SECONDS)
                    result
                } else {
                    @Suppress("DEPRECATION")
                    geocoder.getFromLocation(latitude, longitude, 1)?.firstOrNull()
                }

                if (addr == null) {
                    PlaceLabel.FALLBACK
                } else {
                    PlaceLabel.resolveOrFallback(
                        locality = addr.locality,
                        subAdminArea = addr.subAdminArea,
                        adminArea = addr.adminArea,
                        countryName = addr.countryName
                    )
                }
            } catch (_: IOException) {
                PlaceLabel.FALLBACK
            } catch (_: Exception) {
                PlaceLabel.FALLBACK
            }
        }
}
