package com.weathercurrency.app

import com.weathercurrency.app.data.ApiError
import com.weathercurrency.app.data.CoinGeckoClient
import com.weathercurrency.app.model.CryptoCurrency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CryptoParsingTest {

    private val client = CoinGeckoClient()

    private val sample = """
        {"bitcoin":{"usd":80598,"rub":6609312},"ethereum":{"rub":198977,"usd":2426.46},"solana":{"rub":7881.06,"usd":96.11}}
    """.trimIndent()

    @Test
    fun parseBtcEthSolBothCurrencies() {
        val parsed = client.parse(sample)
        assertEquals(3, parsed.rates.size)

        val btc = parsed.rates[0]
        assertEquals("bitcoin", btc.id)
        assertEquals("BTC", btc.symbol)
        assertEquals(6609312.0, btc.priceRub!!, 0.01)
        assertEquals(80598.0, btc.priceUsd!!, 0.01)

        val eth = parsed.rates[1]
        assertEquals(198977.0, eth.priceRub!!, 0.01)
        assertEquals(2426.46, eth.priceUsd!!, 0.01)

        val sol = parsed.rates[2]
        assertEquals(7881.06, sol.priceRub!!, 0.001)
        assertEquals(96.11, sol.priceUsd!!, 0.001)
    }

    @Test
    fun orderIsAlwaysBtcEthSol() {
        val shuffled = """
            {"solana":{"rub":100,"usd":1},"bitcoin":{"rub":200,"usd":2},"ethereum":{"rub":300,"usd":3}}
        """.trimIndent()
        val parsed = client.parse(shuffled)
        assertEquals(listOf("bitcoin", "ethereum", "solana"), parsed.rates.map { it.id })
    }

    @Test
    fun missingUsdStaysNullNotDerivedFromRub() {
        val json = """{"bitcoin":{"rub":6600000},"ethereum":{"usd":2400},"solana":{"rub":7800,"usd":96}}"""
        val parsed = client.parse(json)
        val btc = parsed.rates.find { it.id == "bitcoin" }!!!!
        assertEquals(6600000.0, btc.priceRub!!, 0.01)
        assertNull("USD must not be invented from RUB", btc.priceUsd)
        // priceFor USD → null → UI shows «—»
        assertNull(btc.priceFor(CryptoCurrency.USD))
        assertEquals(6600000.0, btc.priceFor(CryptoCurrency.RUB)!!, 0.01)
    }

    @Test
    fun missingRubStaysNull() {
        val json = """{"bitcoin":{"usd":80000},"ethereum":{"usd":2400},"solana":{"usd":96}}"""
        val parsed = client.parse(json)
        val btc = parsed.rates.find { it.id == "bitcoin" }!!!!
        assertNull(btc.priceRub)
        assertEquals(80000.0, btc.priceUsd!!, 0.01)
    }

    @Test
    fun zeroPriceIsSkippedPerCurrency() {
        val json = """{"bitcoin":{"rub":0,"usd":80000},"ethereum":{"rub":10,"usd":0},"solana":{"rub":20,"usd":30}}"""
        val parsed = client.parse(json)
        assertNull(parsed.rates.find { it.id == "bitcoin" }!!!!.priceRub)
        assertEquals(80000.0, parsed.rates.find { it.id == "bitcoin" }!!!!.priceUsd!!, 0.01)
        assertEquals(10.0, parsed.rates.find { it.id == "ethereum" }!!!!.priceRub!!, 0.01)
        assertNull(parsed.rates.find { it.id == "ethereum" }!!!!.priceUsd)
    }

    @Test
    fun allEmptyThrowsParseError() {
        assertThrows(ApiError.Parse::class.java) {
            client.parse("""{"bitcoin":{"rub":0},"ethereum":{},"solana":{"usd":-1}}""")
        }
    }

    @Test
    fun invalidJsonThrowsParseError() {
        assertThrows(ApiError.Parse::class.java) {
            client.parse("{not json")
        }
    }

    @Test
    fun errorMessageFieldSurfaces() {
        val e = assertThrows(ApiError.Parse::class.java) {
            client.parse("""{"error":"rate_limit_exceeded","error_message":"please slow down"}""")
        }
        assertTrue(e.message!!.contains("slow down"))
    }

    @Test
    fun buildUrlRequestsBothCurrencies() {
        val url = client.buildUrl()!!.toString()
        assertTrue(url.startsWith("https://api.coingecko.com/api/v3/simple/price"))
        assertTrue(url.contains("vs_currencies=rub%2Cusd") || url.contains("vs_currencies=rub,usd"))
        assertTrue(url.contains("ids=bitcoin"))
    }

    @Test
    fun sourceLabelIsCoinGecko() {
        val snap = com.weathercurrency.app.model.CryptoSnapshot(
            rates = client.parse(sample).rates,
            fetchedAtMillis = 1L
        )
        assertEquals("CoinGecko", snap.source)
        assertTrue(snap.rate("bitcoin") != null)
        assertTrue(snap.rate("ethereum") != null)
        assertTrue(snap.rate("solana") != null)
    }

    @Test
    fun toggleDoesNotChangeDataModel() {
        // RUB→USD→RUB is a pure display concern on the same snapshot.
        val rates = client.parse(sample).rates
        val snap = com.weathercurrency.app.model.CryptoSnapshot(rates, 1L)
        val btc = snap.rate("bitcoin")!!
        val asRub = btc.priceFor(CryptoCurrency.RUB)
        val asUsd = btc.priceFor(CryptoCurrency.USD)
        assertEquals(6609312.0, asRub!!, 0.01)
        assertEquals(80598.0, asUsd!!, 0.01)
        // same objects after "toggle"
        assertEquals(asRub, btc.priceFor(CryptoCurrency.RUB))
        assertEquals(asUsd, btc.priceFor(CryptoCurrency.USD))
    }
}
