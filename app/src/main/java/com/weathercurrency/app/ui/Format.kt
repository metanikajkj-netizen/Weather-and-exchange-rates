package com.weathercurrency.app.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pure formatting helpers (unit-testable, no Android deps).
 * Temperature: round first, then normalize −0 → 0; always explicit sign for ±N.
 */
object Format {

    /**
     * Formats a Celsius value for UI.
     * - −0.4 / 0.0 / +0.4 / −0.0 → «0°»
     * - +1.0 → «+1°», −1.0 → «-1°»
     */
    fun temp(celsius: Double): String {
        if (celsius.isNaN() || celsius.isInfinite()) return "—"
        // Round half away from zero via Kotlin roundToInt-style on double.
        val rounded = when {
            celsius >= 0 -> Math.floor(celsius + 0.5).toLong()
            else -> Math.ceil(celsius - 0.5).toLong()
        }
        return when {
            rounded > 0L -> "+$rounded°"
            rounded < 0L -> "$rounded°"
            else -> "0°"
        }
    }

    fun wind(kmh: Double): String {
        if (kmh.isNaN() || kmh.isInfinite()) return "—"
        val rounded = Math.floor(kmh + 0.5).toLong()
        return rounded.toString()
    }

    /**
     * Formats a RUB price for crypto/fiat display.
     * Large: thousands separators, no decimals (e.g. 6 590 470 ₽).
     * Medium: 2 decimals when fractional part matters (e.g. 7 870.26 ₽).
     * Tiny: more decimals so non-zero prices never collapse to «0».
     */
    fun priceRub(price: Double): String {
        if (price.isNaN() || price.isInfinite() || price <= 0.0) return "—"
        return when {
            price >= 100_000 -> groupThousands(Math.floor(price).toLong()) + " ₽"
            price >= 1 -> {
                val whole = Math.floor(price).toLong()
                val frac = price - whole
                if (frac < 0.005) {
                    groupThousands(whole) + " ₽"
                } else {
                    groupThousands(whole) + String.format(Locale.US, ".%02d", Math.round(frac * 100)) + " ₽"
                }
            }
            else -> String.format(Locale.US, "%.6f", price) + " ₽"
        }
    }

    /**
     * Formats a USD price with localized separators:
     * thousands — space, decimal — comma; always 2 fraction digits when ≥ 1.
     * Examples: «80 999,25 $», «2 439,50 $», «96,62 $».
     */
    fun priceUsd(price: Double): String {
        if (price.isNaN() || price.isInfinite() || price <= 0.0) return "—"
        if (price < 1.0) {
            return String.format(Locale.US, "%.6f", price).replace('.', ',') + " $"
        }
        var whole = Math.floor(price).toLong()
        var cents = Math.round((price - whole) * 100.0).toLong()
        if (cents >= 100L) {
            whole += 1L
            cents -= 100L
        }
        return groupThousands(whole) + String.format(Locale.US, ",%02d", cents) + " $"
    }

    /** Price in the selected crypto currency, or null if that side is missing. */
    fun priceCrypto(
        price: Double?,
        currency: com.weathercurrency.app.model.CryptoCurrency
    ): String? {
        if (price == null || price.isNaN() || price.isInfinite() || price <= 0.0) return null
        return when (currency) {
            com.weathercurrency.app.model.CryptoCurrency.RUB -> priceRub(price)
            com.weathercurrency.app.model.CryptoCurrency.USD -> priceUsd(price)
        }
    }

    private fun groupThousands(v: Long): String {
        val negative = v < 0
        val digits = Math.abs(v).toString()
        val sb = StringBuilder()
        for ((i, ch) in digits.withIndex()) {
            if (i > 0 && (digits.length - i) % 3 == 0) sb.append(' ')
            sb.append(ch)
        }
        return if (negative) "-$sb" else sb.toString()
    }

    fun time(millis: Long): String =
        SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("ru")).format(Date(millis))

    fun dayName(isoDate: String): String {
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val date = sdf.parse(isoDate) ?: return isoDate
            SimpleDateFormat("EEE", Locale("ru"))
                .format(date)
                .replaceFirstChar { it.uppercase() }
        } catch (_: Exception) {
            isoDate
        }
    }
}
