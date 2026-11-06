package com.weathercurrency.app.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

sealed class LocationOutcome {
    data class Success(val location: Location) : LocationOutcome()
    /** User denied permission(s). */
    object PermissionDenied : LocationOutcome()
    /** Location providers disabled in system settings. */
    object ProvidersDisabled : LocationOutcome()
    /** No fix within timeout. */
    object Timeout : LocationOutcome()
    object Unavailable : LocationOutcome()
}

/**
 * One-shot location fix using platform LocationManager.
 * No background tracking, no ACCESS_BACKGROUND_LOCATION.
 */
class LocationProvider(private val context: Context) {

    fun hasPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    fun providersEnabled(): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return try {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Requests a single current fix with [timeoutMillis].
     * Prefers a recent last-known fix, otherwise waits for a fresh update.
     */
    @SuppressLint("MissingPermission")
    suspend fun getCurrentLocation(timeoutMillis: Long = 12_000L): LocationOutcome {
        if (!hasPermission()) return LocationOutcome.PermissionDenied
        if (!providersEnabled()) return LocationOutcome.ProvidersDisabled

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        val last = lastRecent(lm)
        if (last != null) return LocationOutcome.Success(last)

        return withTimeoutOrNull(timeoutMillis) {
            suspendCancellableCoroutine { cont ->
                val handler = Handler(Looper.getMainLooper())
                var resumed = false
                var listener: LocationListener? = null

                fun finishWith(outcome: LocationOutcome) {
                    if (!resumed && cont.isActive) {
                        resumed = true
                        handler.removeCallbacksAndMessages(null)
                        try {
                            listener?.let { lm.removeUpdates(it) }
                        } catch (_: Exception) {
                        }
                        cont.resume(outcome)
                    }
                }

                listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        finishWith(LocationOutcome.Success(location))
                    }

                    @Deprecated("Deprecated in API 30")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
                    override fun onProviderEnabled(provider: String) = Unit
                    override fun onProviderDisabled(provider: String) {
                        finishWith(LocationOutcome.ProvidersDisabled)
                    }
                }

                cont.invokeOnCancellation {
                    try {
                        listener?.let { lm.removeUpdates(it) }
                    } catch (_: Exception) {
                    }
                }

                val providers = listOf(
                    LocationManager.NETWORK_PROVIDER,
                    LocationManager.PASSIVE_PROVIDER,
                    LocationManager.GPS_PROVIDER
                ).filter {
                    try {
                        lm.allProviders.contains(it)
                    } catch (_: Exception) {
                        false
                    }
                }

                var requested = false
                for (p in providers) {
                    try {
                        if (Build.VERSION.SDK_INT >= 30) {
                            lm.getCurrentLocation(
                                p,
                                CancellationSignal(),
                                context.mainExecutor
                            ) { loc ->
                                if (loc != null) finishWith(LocationOutcome.Success(loc))
                            }
                        } else {
                            @Suppress("DEPRECATION")
                            lm.requestSingleUpdate(p, listener!!, Looper.getMainLooper())
                        }
                        requested = true
                    } catch (_: SecurityException) {
                        finishWith(LocationOutcome.PermissionDenied)
                    } catch (_: Exception) {
                        // try next provider
                    }
                }

                if (!requested && !resumed) {
                    finishWith(LocationOutcome.Unavailable)
                }
            }
        } ?: LocationOutcome.Timeout
    }

    @SuppressLint("MissingPermission")
    private fun lastRecent(lm: LocationManager): Location? {
        return try {
            val candidates = mutableListOf<Location>()
            for (p in lm.allProviders) {
                @Suppress("DEPRECATION")
                lm.getLastKnownLocation(p)?.let { candidates += it }
            }
            candidates.maxByOrNull { it.time }
                ?.takeIf { System.currentTimeMillis() - it.time < 5 * 60_000L }
        } catch (_: SecurityException) {
            null
        } catch (_: Exception) {
            null
        }
    }
}
