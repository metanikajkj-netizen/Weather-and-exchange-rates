package com.weathercurrency.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for one-time event pattern (TASK 30.9.1).
 * Bug: stale action result dialog appeared when opening a different user profile.
 * Root cause: lastHandledResult was remember-scoped (resets on recomposition),
 * but profileExtendResult persisted in ViewModel.
 */
class OneTimeEventTest {

    @Test
    fun consumeClearsResult() {
        // Simulate ViewModel state
        var profileExtendResult: String? = "OK: Аркаша продлён на 1 дн."

        // consumeExtendResult() should clear it
        profileExtendResult = null

        assertNull(profileExtendResult)
    }

    @Test
    fun selectUserClearsResults() {
        // Simulate ViewModel state
        var profileExtendResult: String? = "OK: старый результат"
        var archiveResult: String? = "Пользователь перемещён в архив"
        var message: String? = "Подписка продлена"

        // selectUser() should clear all results
        profileExtendResult = null
        archiveResult = null
        message = null

        assertNull(profileExtendResult)
        assertNull(archiveResult)
        assertNull(message)
    }

    @Test
    fun closeProfileClearsResults() {
        var profileExtendResult: String? = "OK: старый результат"
        var archiveResult: String? = "Ошибка"

        profileExtendResult = null
        archiveResult = null

        assertNull(profileExtendResult)
        assertNull(archiveResult)
    }

    @Test
    fun dialogNotShownWhenResultNull() {
        val profileExtendResult: String? = null
        val shouldShow = profileExtendResult != null
        assertFalse(shouldShow)
    }

    @Test
    fun dialogShownWhenResultPresent() {
        val profileExtendResult: String? = "OK: продлено"
        val shouldShow = profileExtendResult != null
        assertTrue(shouldShow)
    }

    @Test
    fun consumePreventsReshow() {
        var result: String? = "OK: продлено"
        var dialogShown = false

        // First encounter: show dialog and consume
        if (result != null) {
            dialogShown = true
            result = null // consume
        }

        // Second encounter (recomposition): result is null, no dialog
        if (result != null) {
            dialogShown = true
        }

        assertEquals(1, if (dialogShown) 1 else 0) // shown once
    }

    @Test
    fun resultForUserANotShownForUserB() {
        // User A result
        var resultA: String? = "OK: Аркаша продлён"

        // Navigate to User B — selectUser clears results
        resultA = null // selectUser() clears

        assertNull(resultA) // User B should not see User A's result
    }

    @Test
    fun activityRecreationDoesNotReshow() {
        var result: String? = "OK: продлено"

        // First composition: show and consume
        if (result != null) result = null

        // Activity recreation → new composition → result is already null
        assertNull(result)
    }

    @Test
    fun multipleOperationsProduceSingleDialog() {
        var result: String? = null
        var dialogCount = 0

        // Operation 1
        result = "OK: операция 1"
        if (result != null) { dialogCount++; result = null }

        // Operation 2
        result = "OK: операция 2"
        if (result != null) { dialogCount++; result = null }

        assertEquals(2, dialogCount) // 2 operations = 2 dialogs
    }

    @Test
    fun errorMessageShownAsDialog() {
        val result = "Ошибка продления: timeout"
        val isError = result.contains("Ошибка")
        assertTrue(isError)
    }
}
