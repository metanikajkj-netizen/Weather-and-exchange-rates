package com.weathercurrency.app

import com.weathercurrency.app.data.ApiError
import com.weathercurrency.app.data.CbrCurrencyClient
import com.weathercurrency.app.model.CurrencySnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CurrencyParsingTest {

    private val client = CbrCurrencyClient()

    private val sampleXml = """
        <?xml version="1.0" encoding="windows-1251"?>
        <ValCurs Date="04.11.2026" name="Foreign Currency Market">
          <Valute ID="R01235">
            <NumCode>840</NumCode>
            <CharCode>USD</CharCode>
            <Nominal>1</Nominal>
            <Name>Доллар США</Name>
            <Value>92,1234</Value>
          </Valute>
          <Valute ID="R01239">
            <NumCode>978</NumCode>
            <CharCode>EUR</CharCode>
            <Nominal>1</Nominal>
            <Name>Евро</Name>
            <Value>99,5678</Value>
          </Valute>
          <Valute ID="R01375">
            <NumCode>156</NumCode>
            <CharCode>CNY</CharCode>
            <Nominal>10</Nominal>
            <Name>Юань</Name>
            <Value>128,3456</Value>
          </Valute>
          <Valute ID="R01010">
            <NumCode>036</NumCode>
            <CharCode>AUD</CharCode>
            <Nominal>1</Nominal>
            <Name>Австралийский доллар</Name>
            <Value>60,0000</Value>
          </Valute>
        </ValCurs>
    """.trimIndent()

    @Test
    fun parseUsdEurCny() {
        val snap = client.parse(sampleXml, fetchedAtMillis = 1000L)
        assertEquals("04.11.2026", snap.rateDate)
        assertEquals(1000L, snap.fetchedAtMillis)
        assertEquals(3, snap.rates.size)

        val usd = snap.rate("USD")
        assertNotNull(usd)
        assertEquals(92.1234, usd!!.ratePerUnit, 0.0001)
        assertEquals(1, usd.nominal)

        val eur = snap.rate("EUR")
        assertNotNull(eur)
        assertEquals(99.5678, eur!!.ratePerUnit, 0.0001)
    }

    @Test
    fun nominalIsAppliedForCny() {
        val snap = client.parse(sampleXml)
        val cny = snap.rate("CNY")
        assertNotNull(cny)
        assertEquals(12.83456, cny!!.ratePerUnit, 0.0001)
        assertEquals(10, cny.nominal)
    }

    @Test
    fun requiredOrderIsUsdEurCny() {
        val snap = client.parse(sampleXml)
        assertEquals(listOf("USD", "EUR", "CNY"), snap.rates.map { it.charCode })
    }

    @Test
    fun missingDateThrowsParseError() {
        assertThrows(ApiError.Parse::class.java) {
            client.parse("<ValCurs><Valute><CharCode>USD</CharCode><Nominal>1</Nominal><Value>1,0</Value></Valute></ValCurs>")
        }
    }

    @Test
    fun missingRequiredCurrenciesThrowParseError() {
        assertThrows(ApiError.Parse::class.java) {
            client.parse(
                """
                <ValCurs Date="01.01.2026">
                  <Valute><CharCode>GBP</CharCode><Nominal>1</Nominal><Value>100,0</Value></Valute>
                </ValCurs>
                """.trimIndent()
            )
        }
    }

    @Test
    fun missingOneCurrencyStillReturnsOthers() {
        val xml = """
            <ValCurs Date="01.01.2026">
              <Valute><CharCode>USD</CharCode><Nominal>1</Nominal><Value>90,0000</Value></Valute>
              <Valute><CharCode>CNY</CharCode><Nominal>1</Nominal><Value>12,0000</Value></Valute>
            </ValCurs>
        """.trimIndent()
        val snap = client.parse(xml)
        assertEquals(listOf("USD", "CNY"), snap.rates.map { it.charCode })
        assertNotNull(snap.rate("USD"))
        assertEquals(null, snap.rate("EUR"))
    }

    @Test
    fun brokenXmlThrowsParseError() {
        assertThrows(ApiError.Parse::class.java) {
            client.parse("<ValCurs Date=\"01.01.2026\"><Valute>unclosed")
        }
    }

    @Test
    fun decimalCommaIsHandled() {
        val snap = client.parse(sampleXml)
        assertEquals(92.1234, snap.rate("USD")!!.ratePerUnit, 0.0001)
    }

    @Test
    fun decodeCbrUsesWindows1251() {
        val bytes = byteArrayOf(
            0xC4.toByte(), 0xEE.toByte(), 0xEB.toByte(),
            0xEB.toByte(), 0xE0.toByte(), 0xF0.toByte()
        )
        val decoded = client.decodeCbr(bytes, contentType = null)
        assertEquals("Доллар", decoded)
    }

    @Test
    fun decodeCbrRespectsContentTypeCharset() {
        // UTF-8 bytes for "Доллар"
        val utf8 = "Доллар".toByteArray(Charsets.UTF_8)
        val decoded = client.decodeCbr(utf8, contentType = "application/xml; charset=utf-8")
        assertEquals("Доллар", decoded)
    }

    @Test
    fun snapshotHoldsRateDateAndSourceData() {
        val snap: CurrencySnapshot = client.parse(sampleXml, 42L)
        assertEquals("04.11.2026", snap.rateDate)
        assertTrue(snap.rates.all { it.ratePerUnit > 0.0 })
    }

    @Test
    fun parseHandlesNbspThousandsSeparator() {
        // CBR may use NBSP as thousands separator: "1 234,5678" → 1234.5678
        val nbsp = "\u00A0"
        val xml = (
            "<ValCurs Date=\"01.01.2026\">" +
            "<Valute><CharCode>USD</CharCode><Nominal>1</Nominal>" +
            "<Value>1" + nbsp + "234,5678</Value></Valute>" +
            "<Valute><CharCode>EUR</CharCode><Nominal>1</Nominal><Value>99,0000</Value></Valute>" +
            "<Valute><CharCode>CNY</CharCode><Nominal>1</Nominal><Value>12,0000</Value></Valute>" +
            "</ValCurs>"
        )
        val snap = client.parse(xml)
        assertEquals(1234.5678, snap.rate("USD")!!.ratePerUnit, 0.0001)
    }

    @Test
    fun cbrUrlIsOfficialHttps() {
        assertEquals("https://www.cbr.ru/scripts/XML_daily.asp", CbrCurrencyClient.CBR_URL)
        assertTrue(CbrCurrencyClient.CBR_URL.startsWith("https://"))
    }
}
