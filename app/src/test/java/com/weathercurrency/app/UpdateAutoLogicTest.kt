package com.weathercurrency.app

import com.weathercurrency.app.update.UpdateAutoLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateAutoLogicTest {

    private val installed = 7L
    private val newer = 8L

    private fun settings(
        auto: Boolean = true,
        notify: Boolean = true,
        lastCheck: Long = 0L,
        lastNotified: Long = 0L,
        postponeUntil: Long = 0L,
        lastDialogVc: Long = 0L
    ) = UpdateAutoLogic.UpdateSettings(
        autoCheckEnabled = auto,
        notifyEnabled = notify,
        lastCheckMillis = lastCheck,
        lastNotifiedVersionCode = lastNotified,
        dialogPostponeUntilMillis = postponeUntil,
        lastDialogVersionCode = lastDialogVc
    )

    // ---- defaults ----

    @Test
    fun defaultsAreEnabled() {
        val s = UpdateAutoLogic.UpdateSettings()
        assertTrue(s.autoCheckEnabled)
        assertTrue(s.notifyEnabled)
        assertEquals(0L, s.lastCheckMillis)
        assertEquals(0L, s.lastNotifiedVersionCode)
    }

    // ---- auto check gating ----

    @Test
    fun autoCheckDisabledSkipsWorkerAndStartup() {
        val s = settings(auto = false)
        assertTrue(UpdateAutoLogic.workerShouldSkip(s))
        assertFalse(UpdateAutoLogic.autoCheckAllowed(s))
        assertFalse(UpdateAutoLogic.shouldStartupCheck(s, System.currentTimeMillis()))
    }

    @Test
    fun autoCheckEnabledAllowsWorker() {
        assertFalse(UpdateAutoLogic.workerShouldSkip(settings(auto = true)))
        assertTrue(UpdateAutoLogic.autoCheckAllowed(settings(auto = true)))
    }

    @Test
    fun startupCheckFirstRunAllowed() {
        assertTrue(
            UpdateAutoLogic.shouldStartupCheck(settings(lastCheck = 0L), nowMillis = 1_000L)
        )
    }

    @Test
    fun startupCheckThrottledWithinFifteenMinutes() {
        val last = 1_000_000L
        val now = last + UpdateAutoLogic.STARTUP_CHECK_INTERVAL_MS - 1
        assertFalse(UpdateAutoLogic.shouldStartupCheck(settings(lastCheck = last), nowMillis = now))
    }

    @Test
    fun startupCheckAllowedAfterFifteenMinutes() {
        val last = 1_000_000L
        val now = last + UpdateAutoLogic.STARTUP_CHECK_INTERVAL_MS
        assertTrue(UpdateAutoLogic.shouldStartupCheck(settings(lastCheck = last), nowMillis = now))
    }

    // ---- notification dedup ----

    @Test
    fun notifyWhenNewerAndNotYetNotified() {
        assertTrue(UpdateAutoLogic.shouldNotify(settings(), installed, newer))
    }

    @Test
    fun noNotifyWhenNotificationsDisabled() {
        assertFalse(UpdateAutoLogic.shouldNotify(settings(notify = false), installed, newer))
    }

    @Test
    fun noNotifyWhenSameOrOlderVersion() {
        assertFalse(UpdateAutoLogic.shouldNotify(settings(), installed, installed))
        assertFalse(UpdateAutoLogic.shouldNotify(settings(), installed, installed - 1))
    }

    @Test
    fun noNotifyDuplicateForSameVersion() {
        val s = settings(lastNotified = newer)
        assertFalse(UpdateAutoLogic.shouldNotify(s, installed, newer))
    }

    @Test
    fun notifyAgainOnlyForHigherVersion() {
        val s = settings(lastNotified = newer)
        assertTrue(UpdateAutoLogic.shouldNotify(s, installed, newer + 1))
    }

    @Test
    fun afterNotifyBumpsWatermarkMonotonic() {
        assertEquals(8L, UpdateAutoLogic.afterNotify(0L, 8L, 0L))
        assertEquals(8L, UpdateAutoLogic.afterNotify(0L, 7L, 8L))
        assertEquals(9L, UpdateAutoLogic.afterNotify(0L, 9L, 8L))
    }

    // ---- dialog ----

    @Test
    fun dialogShownOncePerVersion() {
        val s = settings(lastDialogVc = newer)
        assertFalse(
            UpdateAutoLogic.shouldShowDialog(s, installed, newer, nowMillis = 10_000L)
        )
        assertTrue(
            UpdateAutoLogic.shouldShowDialog(settings(), installed, newer, nowMillis = 10_000L)
        )
    }

    @Test
    fun dialogBlockedWhilePostponed() {
        val s = settings(postponeUntil = 50_000L)
        assertFalse(UpdateAutoLogic.shouldShowDialog(s, installed, newer, nowMillis = 49_999L))
        assertTrue(UpdateAutoLogic.shouldShowDialog(s, installed, newer, nowMillis = 50_000L))
    }

    @Test
    fun dialogNotShownForOlderOrEqual() {
        assertFalse(UpdateAutoLogic.shouldShowDialog(settings(), installed, installed, 1L))
        assertFalse(UpdateAutoLogic.shouldShowDialog(settings(), installed, 6L, 1L))
    }

    @Test
    fun postponeAdds24Hours() {
        val now = 1_000L
        assertEquals(now + UpdateAutoLogic.DIALOG_POSTPONE_MS, UpdateAutoLogic.postponeDialog(now))
    }

    @Test
    fun forceCanReshowAfterSameVersionSeen() {
        val s = settings(lastDialogVc = newer)
        assertTrue(
            UpdateAutoLogic.shouldShowDialog(s, installed, newer, nowMillis = 10_000L, force = true)
        )
    }

    // ---- no APK download in background: worker only checks settings flag ----

    @Test
    fun periodIsFifteenMinutes() {
        assertEquals(15L, UpdateAutoLogic.PERIOD_MINUTES)
        assertEquals(15L * 60L * 1000L, UpdateAutoLogic.STARTUP_CHECK_INTERVAL_MS)
    }
}
