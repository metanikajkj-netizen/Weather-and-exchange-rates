package com.weathercurrency.app.update

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Unique periodic OTA check (~15 min, network required).
 * UPDATE policy migrates an existing 6h job to the new interval without
 * creating a duplicate. Cancel when user disables auto-check.
 *
 * Android controls the actual schedule (Doze/battery) — 15 min is the
 * requested minimum interval, not a guaranteed exact cadence.
 */
object UpdateScheduler {

    const val UNIQUE_WORK_NAME = "weather_currency_update_check"
    /** Legacy one-time diagnostic job (v1.9) — cancelled on migration. */
    const val UNIQUE_ONCE_WORK_NAME = "weather_currency_update_check_once"

    fun schedule(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(
            UpdateAutoLogic.PERIOD_MINUTES, TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            // First run soon after install/update; then every ~15 min (Android may delay).
            .setInitialDelay(1, TimeUnit.MINUTES)
            .build()

        val wm = WorkManager.getInstance(context)
        // UPDATE: replaces existing unique periodic work (e.g. old 6h job) in place — no duplicates.
        wm.enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
        // Migration: drop leftover diagnostic one-time work from v1.9.
        wm.cancelUniqueWork(UNIQUE_ONCE_WORK_NAME)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
    }

    /** Apply user preference: schedule or cancel. */
    fun applyAutoCheckEnabled(context: Context, enabled: Boolean) {
        if (enabled) schedule(context) else cancel(context)
    }
}
