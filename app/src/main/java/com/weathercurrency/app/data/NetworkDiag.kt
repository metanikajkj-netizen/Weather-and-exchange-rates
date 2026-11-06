package com.weathercurrency.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log

/**
 * Connectivity helpers + safe logging (no personal data, no coordinates).
 * Log failures are swallowed so unit tests without Android framework still pass.
 */
object NetworkDiag {

    private const val TAG = "WeatherCurrency"

    fun isOnline(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            if (Build.VERSION.SDK_INT >= 23) {
                val network = cm.activeNetwork ?: return false
                val caps = cm.getNetworkCapabilities(network) ?: return false
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            } else {
                @Suppress("DEPRECATION")
                val info = cm.activeNetworkInfo
                @Suppress("DEPRECATION")
                info?.isConnected == true
            }
        } catch (_: Exception) {
            true
        }
    }

    /** Log technical detail only — never lat/lon, city, or user identifiers. */
    fun logError(where: String, e: Throwable) {
        try {
            Log.e(TAG, "$where: ${e.javaClass.simpleName}: ${e.message}")
        } catch (_: Throwable) {
            // android.util.Log unavailable in plain JVM unit tests
        }
    }

    fun logInfo(where: String, message: String) {
        try {
            Log.i(TAG, "$where: $message")
        } catch (_: Throwable) {
        }
    }
}
