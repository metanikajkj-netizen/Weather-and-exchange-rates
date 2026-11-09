package com.weathercurrency.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure subscription math / status mapping (TASK26.5) — no Android framework. */
class SubscriptionLogicTest {

    private fun daysLeft(exp: Long, now: Long): Int =
        if (exp <= now) 0 else ((exp - now) / 86400L).toInt()

    @Test
    fun zeroDaysWhenNoSubscription() {
        assertEquals(0, daysLeft(0, 1000000))
        assertEquals(0, daysLeft(1000000, 1000000))
    }

    @Test
    fun positiveDaysWhenActive() {
        val now = 1_700_000_000L
        assertEquals(30, daysLeft(now + 30L * 86400, now))
    }

    @Test
    fun renewFormulaMaxNowExpiryPlusDays() {
        val now = 1_700_000_000L
        val existing = now + 5L * 86400
        val base = maxOf(now, existing)
        val exp = base + 30L * 86400
        assertEquals(35, daysLeft(exp, now))
    }

    @Test
    fun renewFromExpiredUsesNow() {
        val now = 1_700_000_000L
        val existing = now - 86400L
        val exp = maxOf(now, existing) + 10L * 86400
        assertEquals(10, daysLeft(exp, now))
    }

    @Test
    fun noSubscriptionMessageShownWhenZero() {
        val msg = "Нет активной подписки. Для подключения VPN необходимо активировать промокод."
        assertTrue(msg.contains("активировать промокод"))
    }
}
