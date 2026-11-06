package com.weathercurrency.app.update

import android.content.Context
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.weathercurrency.app.data.CacheStore
import com.weathercurrency.app.data.NetworkDiag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Periodic background check of GitHub Releases.
 * - Network-only via WorkManager constraints (see UpdateScheduler).
 * - Never downloads APK (user action only).
 * - Errors swallowed (Result.success) to avoid crash / retry storms.
 * - Skips network when auto-check setting is off.
 */
class UpdateCheckWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val cache = CacheStore(applicationContext)
            val settings = cache.loadUpdateSettings()
            if (UpdateAutoLogic.workerShouldSkip(settings)) {
                NetworkDiag.logInfo("UpdateWorker", "skipped: auto-check disabled")
                return@withContext Result.success()
            }

            val installed = installedVersionCode(applicationContext)
            val client = GitHubReleaseClient()
            val release = client.fetchLatest()

            when (val decision = UpdateLogic.decide(
                installedVersionCode = installed,
                installedPackage = applicationContext.packageName,
                release = release
            )) {
                is UpdateCheckResult.UpdateAvailable -> {
                    val now = System.currentTimeMillis()
                    val latest = cache.loadUpdateSettings()
                    if (UpdateAutoLogic.shouldNotify(
                            latest,
                            installed,
                            decision.metadata.versionCode.toLong()
                        )
                    ) {
                        val posted = UpdateNotifier.notifyUpdateAvailable(
                            applicationContext,
                            decision.metadata.versionName
                        )
                        if (posted) {
                            cache.saveUpdateSettings(
                                latest.copy(
                                    lastNotifiedVersionCode = UpdateAutoLogic.afterNotify(
                                        now,
                                        decision.metadata.versionCode.toLong(),
                                        latest.lastNotifiedVersionCode
                                    ),
                                    lastCheckMillis = UpdateAutoLogic.afterCheck(now)
                                )
                            )
                        } else {
                            cache.saveUpdateSettings(
                                latest.copy(lastCheckMillis = UpdateAutoLogic.afterCheck(now))
                            )
                        }
                    } else {
                        cache.saveUpdateSettings(
                            latest.copy(lastCheckMillis = UpdateAutoLogic.afterCheck(now))
                        )
                    }
                    NetworkDiag.logInfo(
                        "UpdateWorker",
                        "update available vc=${decision.metadata.versionCode}"
                    )
                }
                is UpdateCheckResult.UpToDate -> {
                    val latest = cache.loadUpdateSettings()
                    cache.saveUpdateSettings(
                        latest.copy(lastCheckMillis = UpdateAutoLogic.afterCheck(System.currentTimeMillis()))
                    )
                    NetworkDiag.logInfo("UpdateWorker", "up-to-date")
                }
                is UpdateCheckResult.Error -> {
                    NetworkDiag.logInfo("UpdateWorker", "decision: ${decision.message}")
                }
            }
            Result.success()
        } catch (e: Exception) {
            // Network / parse errors: do not crash, do not hammer retries.
            NetworkDiag.logError("UpdateWorker", e)
            Result.success()
        }
    }

    private fun installedVersionCode(context: Context): Long {
        return try {
            val pm = context.packageManager
            val pi = if (Build.VERSION.SDK_INT >= 33) {
                pm.getPackageInfo(
                    context.packageName,
                    android.content.pm.PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, 0)
            }
            if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode
            else @Suppress("DEPRECATION") pi.versionCode.toLong()
        } catch (_: Exception) {
            0L
        }
    }
}
