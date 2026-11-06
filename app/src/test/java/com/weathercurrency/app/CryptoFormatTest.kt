package com.weathercurrency.app

import com.weathercurrency.app.model.CryptoCurrency
import com.weathercurrency.app.ui.Format
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CryptoFormatTest {

    // ---- RUB (existing format preserved) ----

    @Test
    fun largePriceUsesThousandsSpaces() {
        assertEquals("6 590 470 ₽", Format.priceRub(6_590_470.0))
    }

    @Test
    fun midPriceWithFraction() {
        assertEquals("7 870.26 ₽", Format.priceRub(7870.26))
    }

    @Test
    fun midPriceWholeNumber() {
        assertEquals("198 667 ₽", Format.priceRub(198_667.0))
    }

    @Test
    fun zeroOrInvalidIsDash() {
        assertEquals("—", Format.priceRub(0.0))
        assertEquals("—", Format.priceRub(-1.0))
        assertEquals("—", Format.priceRub(Double.NaN))
    }

    // ---- USD (localized: space thousands, comma decimal) ----

    @Test
    fun usdBtcExample() {
        // Spec: "80 999,25 $"
        assertEquals("80 999,25 $", Format.priceUsd(80999.25))
    }

    @Test
    fun usdEthExample() {
        // Spec: "2 439,50 $"
        assertEquals("2 439,50 $", Format.priceUsd(2439.50))
    }

    @Test
    fun usdSolExample() {
        // Spec: "96,62 $"
        assertEquals("96,62 $", Format.priceUsd(96.62))
    }

    @Test
    fun usdWholeNumberGetsCents() {
        assertEquals("80 598,00 $", Format.priceUsd(80598.0))
    }

    @Test
    fun usdCarryIntoNextThousand() {
        assertEquals("1 000,00 $", Format.priceUsd(999.995))
    }

    @Test
    fun usdZeroOrInvalidIsDash() {
        assertEquals("—", Format.priceUsd(0.0))
        assertEquals("—", Format.priceUsd(-5.0))
        assertEquals("—", Format.priceUsd(Double.NaN))
    }

    // ---- priceCrypto by selected currency ----

    @Test
    fun priceCryptoSwitchesWithoutApi() {
        assertEquals("80 999,25 $", Format.priceCrypto(80999.25, CryptoCurrency.USD))
        assertEquals("80 999,25 ₽".let { Format.priceRub(80999.25) }, Format.priceCrypto(80999.25, CryptoCurrency.RUB))
        // RUB for large uses no decimals:
        assertEquals(Format.priceRub(80999.25), Format.priceCrypto(80999.25, CryptoCurrency.RUB))
    }

    @Test
    fun priceCryptoNullSideIsNull() {
        assertNull(Format.priceCrypto(null, CryptoCurrency.USD))
        assertNull(Format.priceCrypto(null, CryptoCurrency.RUB))
        assertNull(Format.priceCrypto(0.0, CryptoCurrency.USD))
    }

    @Test
    fun currencyLabels() {
        assertEquals("в рублях", CryptoCurrency.RUB.label)
        assertEquals("в долларах", CryptoCurrency.USD.label)
        assertEquals("₽", CryptoCurrency.RUB.symbol)
        assertEquals("$", CryptoCurrency.USD.symbol)
    }

    @Test
    fun fromCodeDefaultsToRub() {
        assertEquals(CryptoCurrency.RUB, CryptoCurrency.fromCode(null))
        assertEquals(CryptoCurrency.RUB, CryptoCurrency.fromCode("eur"))
        assertEquals(CryptoCurrency.USD, CryptoCurrency.fromCode("USD"))
        assertEquals(CryptoCurrency.USD, CryptoCurrency.fromCode("usd"))
    }
}
