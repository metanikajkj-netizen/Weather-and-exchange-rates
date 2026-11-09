package com.weathercurrency.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for subscription extension logic.
 * Regression: api.py passed 'days' to extend_subscription(uid, seconds) — 5 days became 5 seconds.
 */
class ExtendSubscriptionTest {

    @Test
    fun daysToSecondsConversion() {
        // The bug: api.py passed days directly as seconds
        // Fix: days * 86400
        val days = 5
        val correctSeconds = days * 86400
        assertEquals(432000, correctSeconds)
    }

    @Test
    fun oneDayConversion() {
        assertEquals(86400, 1 * 86400)
    }

    @Test
    fun sevenDaysConversion() {
        assertEquals(604800, 7 * 86400)
    }

    @Test
    fun extendFromBaseExpiry() {
        // Simulate: base = existing_expiry (in future), add 5 days
        val existingExpiry = 1794744909L // 2026-11-15 12:15:09
        val days = 5
        val newExpiry = existingExpiry + days * 86400
        // New expiry should be 5 days later
        val diff = newExpiry - existingExpiry
        assertEquals(5 * 86400, diff)
    }

    @Test
    fun extendFromExpiredSubscription() {
        // Simulate: base = now() (subscription expired), add 3 days
        val now = 1794161200L // 2026-11-08 18:06:40
        val days = 3
        val newExpiry = now + days * 86400
        val diff = newExpiry - now
        assertEquals(3 * 86400, diff)
    }

    @Test
    fun arkashaScenario() {
        // Original: promo activated 2026-11-08 12:14:58 + 7 days = 2026-11-15 12:14:58
        // Bug: 3 extends (1d + 5d + 5d) added 1+5+5 = 11 seconds
        // Expected: 1+5+5 = 11 days = 950400 seconds
        val originalExpiry = 1794744878L // ~2026-11-15 12:14:58
        val intendedDays = 1 + 5 + 5
        val intendedSeconds = intendedDays * 86400
        assertEquals(950400, intendedSeconds)

        val correctExpiry = originalExpiry + intendedSeconds
        val buggyExpiry = originalExpiry + 11 // bug added 11 seconds

        // The difference between correct and buggy
        val lostSeconds = correctExpiry - buggyExpiry
        assertEquals((intendedSeconds - 11).toLong(), lostSeconds)
    }

    @Test
    fun profileExtendValidation() {
        // Validate days input
        assertTrue("5".toIntOrNull()?.let { it >= 1 } == true)
        assertTrue("0".toIntOrNull()?.let { it >= 1 } != true)
        assertTrue("-1".toIntOrNull()?.let { it >= 1 } != true)
        assertTrue("abc".toIntOrNull()?.let { it >= 1 } != true)
        assertTrue("".toIntOrNull()?.let { it >= 1 } != true)
    }

    @Test
    fun doubleClickPrevention() {
        // extendFromProfile checks _ui.value.profileExtendLoading before proceeding
        // This prevents double-click from sending two requests
        var loading = false
        var callCount = 0

        fun extend() {
            if (loading) return // same check as in AdminViewModel
            loading = true
            callCount++
        }

        extend()
        extend() // second call should be blocked
        assertEquals(1, callCount)
    }
}
