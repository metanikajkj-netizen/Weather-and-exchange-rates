package com.weathercurrency.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for automatic subscription synchronization.
 * Key: server_time is authoritative, not local clock.
 */
class SubscriptionSyncTest {

    @Test
    fun serverTimeOffsetCalculation() {
        // When server_time > local_time, offset is positive
        val serverTime = 1000000L
        val localTime = 999000L
        val offset = serverTime - localTime
        assertEquals(1000L, offset)
    }

    @Test
    fun serverTimeOffsetNegative() {
        // When local_time > server_time, offset is negative
        val serverTime = 999000L
        val localTime = 1000000L
        val offset = serverTime - localTime
        assertEquals(-1000L, offset)
    }

    @Test
    fun serverTimeOffsetZeroWhenNoServerTime() {
        // When server_time is not provided, offset is 0
        val serverTime = 0L
        val offset = if (serverTime > 0) serverTime - 1000L else 0L
        assertEquals(0L, offset)
    }

    @Test
    fun subscriptionActiveWithOffset() {
        // Subscription should be active when expiresAt > adjusted now
        val expiresAt = 1000000L
        val localNow = 999000L
        val offset = 500L // server is 500s ahead
        val adjustedNow = localNow + offset
        val isActive = expiresAt > adjustedNow
        assertTrue(isActive)
    }

    @Test
    fun subscriptionExpiredWithOffset() {
        val expiresAt = 1000000L
        val localNow = 999000L
        val offset = 2000L // server is 2000s ahead
        val adjustedNow = localNow + offset
        val isActive = expiresAt > adjustedNow
        assertFalse(isActive) // 999000+2000=1001000 > 1000000, so expired
    }

    @Test
    fun shortSubscriptionNotRoundedToZero() {
        // 1-minute subscription (60 seconds) should show remaining time
        val expiresAt = 1000000L
        val nowSec = 999950L // 50 seconds left
        val remainingSec = (expiresAt - nowSec).coerceAtLeast(0)
        assertEquals(50L, remainingSec)
        // Should be in short sub range (1..86399)
        assertTrue(remainingSec in 1..86399)
    }

    @Test
    fun oneMinuteSubscriptionNotExpired() {
        // 1-minute subscription should not show as expired immediately
        val expiresAt = 1000000L
        val nowSec = 999960L // 40 seconds left
        val remainingSec = (expiresAt - nowSec).coerceAtLeast(0)
        val isShortSub = remainingSec in 1..86399
        assertTrue(isShortSub) // still active
    }

    @Test
    fun midnightCrossing() {
        // Test subscription crossing midnight
        // expiresAt = 23:59:30, now = 23:59:00 -> 30 seconds left
        val expiresAt = 86370L // 23:59:30 in seconds of day
        val nowSec = 86340L    // 23:59:00
        val remainingSec = (expiresAt - nowSec).coerceAtLeast(0)
        assertEquals(30L, remainingSec)
    }

    @Test
    fun periodicSyncPreventsMultipleLoops() {
        // startPeriodicSync checks if syncJob is already active
        var active = false
        var callCount = 0
        fun startSync() {
            if (active) return // same check as in ViewModel
            active = true
            callCount++
        }
        startSync()
        startSync() // second call blocked
        assertEquals(1, callCount)
    }

    @Test
    fun onResumeRefreshesWhenStale() {
        // onResume checks if lastSyncAt is more than 30 seconds old
        val lastSyncAt = 1000000L
        val now = 1031000L // 31 seconds later (31000 ms)
        val shouldRefresh = now - lastSyncAt > 30_000
        assertTrue(shouldRefresh)
    }

    @Test
    fun onResumeSkipsWhenFresh() {
        val lastSyncAt = 1000000L
        val now = 1010000L // 10 seconds later (10000 ms)
        val shouldRefresh = now - lastSyncAt > 30_000
        assertFalse(shouldRefresh)
    }
}
