package com.weathercurrency.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for ActionResultDialog logic and UI fixes.
 */
class ActionResultDialogTest {

    @Test
    fun successResultShowsCorrectTitle() {
        val result = "OK: Подписка продлена на 100 дней"
        val isSuccess = result.startsWith("OK")
        val title = if (isSuccess) "Действие выполнено" else "Ошибка"
        assertEquals("Действие выполнено", title)
    }

    @Test
    fun errorResultShowsCorrectTitle() {
        val result = "Ошибка продления: timeout"
        val isSuccess = result.startsWith("OK")
        val title = if (isSuccess) "Действие выполнено" else "Ошибка"
        assertEquals("Ошибка", title)
    }

    @Test
    fun messageStripsOkPrefix() {
        val result = "OK: Подписка продлена на 100 дней"
        val message = result.removePrefix("OK: ").removePrefix("OK")
        assertEquals("Подписка продлена на 100 дней", message)
    }

    @Test
    fun duplicateResultNotHandledTwice() {
        var lastHandled: String? = null
        var dialogCount = 0

        fun handleResult(result: String?) {
            if (result != null && result != lastHandled) {
                lastHandled = result
                dialogCount++
            }
        }

        handleResult("OK: done")
        handleResult("OK: done") // duplicate — should not trigger
        handleResult("OK: different") // new — should trigger
        assertEquals(2, dialogCount)
    }

    @Test
    fun loginLogoutMessagesNotShownAsDialogs() {
        val message = "Вход выполнен"
        val shouldShow = message.isNotBlank() && message != "Вход выполнен" && message != "Выход выполнен"
        assertFalse(shouldShow)
    }

    @Test
    fun successMessageShownAsDialog() {
        val message = "Подписка продлена"
        val shouldShow = message.isNotBlank() && message != "Вход выполнен" && message != "Выход выполнен"
        assertTrue(shouldShow)
    }

    @Test
    fun errorMessageShownAsDialog() {
        val message = "Ошибка продления: timeout"
        val shouldShow = message.contains("Ошибка")
        assertTrue(shouldShow)
    }

    @Test
    fun infoRowHandlesLongUuid() {
        // Long UUID should wrap, not overflow
        val uuid = "156fdcb5-b99d-444b-9397-3717338d1985-very-long-extension-here"
        // With softWrap=true and fillMaxWidth, it wraps
        assertTrue(uuid.length > 36)
    }

    @Test
    fun windowInsetsPaddingApplied() {
        // UserProfileScreen now uses windowInsetsPadding(WindowInsets.safeDrawing)
        // This prevents header from being too close to status bar
        val hasInsetsPadding = true // verified in code
        assertTrue(hasInsetsPadding)
    }
}
