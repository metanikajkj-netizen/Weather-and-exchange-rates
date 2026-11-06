package com.weathercurrency.app

import com.weathercurrency.app.ui.Format
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TemperatureFormatTest {

    @Test
    fun negativeZeroPointFourIsZero() {
        assertEquals("0°", Format.temp(-0.4))
    }

    @Test
    fun exactZeroIsZero() {
        assertEquals("0°", Format.temp(0.0))
    }

    @Test
    fun negativeZeroDoubleIsZero() {
        assertEquals("0°", Format.temp(-0.0))
    }

    @Test
    fun positiveZeroPointFourIsZero() {
        assertEquals("0°", Format.temp(0.4))
    }

    @Test
    fun negativeOneKeepsMinus() {
        assertEquals("-1°", Format.temp(-1.0))
    }

    @Test
    fun positiveOneKeepsPlus() {
        assertEquals("+1°", Format.temp(1.0))
    }

    @Test
    fun negativeZeroPointFiveRoundsAwayFromZero() {
        // −0.5 → −1 (no «−0°»)
        assertEquals("-1°", Format.temp(-0.5))
    }

    @Test
    fun largeNegative() {
        assertEquals("-12°", Format.temp(-12.4))
    }

    @Test
    fun largePositive() {
        assertEquals("+23°", Format.temp(22.6))
    }

    @Test
    fun nanAndInfiniteBecomeDash() {
        assertEquals("—", Format.temp(Double.NaN))
        assertEquals("—", Format.temp(Double.POSITIVE_INFINITY))
    }

    @Test
    fun windRounds() {
        assertEquals("12", Format.wind(12.4))
        assertEquals("0", Format.wind(0.0))
        assertEquals("7", Format.wind(6.6))
    }

    @Test
    fun noNegativeZeroAnywhereInSampleRange() {
        var v = -0.49
        while (v <= 0.49) {
            val s = Format.temp(v)
            assertTrue("unexpected form for $v: $s", s == "0°" || !s.contains("-0"))
            v += 0.01
        }
    }
}
