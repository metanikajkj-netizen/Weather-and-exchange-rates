package com.weathercurrency.app.update

/**
 * Pure decision helpers for background OTA checks, notifications and dialog.
 * Unit-testable without Android framework.
 */
object UpdateAutoLogic {

    /** Periodic WorkManager interval (Android may delay actual runs). */
    const val PERIOD_MINUTES = 15L
    /** Min gap between startup (in-app) automatic checks. */
    const val STARTUP_CHECK_INTERVAL_MS = 15L * 60L * 1000L
    /** User tapped «Позже» — suppress dialog for this long. */
    const val DIALOG_POSTPONE_MS = 24L * 60L * 60L * 1000L

    data class UpdateSettings(
        /** Automatic periodic + startup checks. Manual check always allowed. */
        val autoCheckEnabled: Boolean = true,
        /** System notifications about new versions. */
        val notifyEnabled: Boolean = true,
        val lastCheckMillis: Long = 0L,
        /** Highest versionCode already notified (dedup). */
        val lastNotifiedVersionCode: Long = 0L,
        /** Dialog suppressed until this timestamp. */
        val dialogPostponeUntilMillis: Long = 0L,
        /** versionCode already offered via dialog (don't re-open same offer). */
        val lastDialogVersionCode: Long = 0L
    )

    /** Whether periodic work / startup network check may run. */
    fun autoCheckAllowed(settings: UpdateSettings): Boolean = settings.autoCheckEnabled

    /**
     * Startup in-app check: allowed if auto-check on and last check is older
     * than [STARTUP_CHECK_INTERVAL_MS]. First run (lastCheck=0) is allowed.
     */
    fun shouldStartupCheck(settings: UpdateSettings, nowMillis: Long): Boolean {
        if (!settings.autoCheckEnabled) return false
        if (settings.lastCheckMillis <= 0L) return true
        return nowMillis - settings.lastCheckMillis >= STARTUP_CHECK_INTERVAL_MS
    }

    /**
     * System notification only when:
     * - notifications enabled,
     * - remote versionCode strictly greater than installed,
     * - not already notified for this (or higher) versionCode.
     * Returns true if a new notification should be shown.
     */
    fun shouldNotify(
        settings: UpdateSettings,
        installedVersionCode: Long,
        availableVersionCode: Long
    ): Boolean {
        if (!settings.notifyEnabled) return false
        if (availableVersionCode <= installedVersionCode) return false
        if (availableVersionCode <= settings.lastNotifiedVersionCode) return false
        return true
    }

    /**
     * In-app dialog when update found:
     * - auto-check setting must allow startup checks (manual path can force),
     * - not postponed,
     * - not already shown for this exact versionCode (unless user never postponed
     *   and version increased again — lastDialogVersionCode blocks same offer).
     */
    fun shouldShowDialog(
        settings: UpdateSettings,
        installedVersionCode: Long,
        availableVersionCode: Long,
        nowMillis: Long,
        force: Boolean = false
    ): Boolean {
        if (availableVersionCode <= installedVersionCode) return false
        if (nowMillis < settings.dialogPostponeUntilMillis) return false
        if (!force && availableVersionCode == settings.lastDialogVersionCode) return false
        return true
    }

    /** After «Позже». */
    fun postponeDialog(nowMillis: Long): Long = nowMillis + DIALOG_POSTPONE_MS

    /** After successful check (manual or auto) — persist timestamp. */
    fun afterCheck(nowMillis: Long): Long = nowMillis

    /** After showing notification — bump dedup watermark. */
    fun afterNotify(nowMillis: Long, availableVersionCode: Long, previous: Long): Long =
        maxOf(previous, availableVersionCode)

    /**
     * Worker skip conditions: auto-check off → no network.
     * Returns false when the worker must not call GitHub.
     */
    fun workerShouldSkip(settings: UpdateSettings): Boolean = !settings.autoCheckEnabled
}
