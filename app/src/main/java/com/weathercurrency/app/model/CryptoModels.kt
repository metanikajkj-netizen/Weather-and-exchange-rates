package com.weathercurrency.app.model

/** Display currency for crypto quotes. */
enum class CryptoCurrency(val code: String, val label: String, val symbol: String) {
    RUB("RUB", "в рублях", "₽"),
    USD("USD", "в долларах", "$");

    companion object {
        fun fromCode(code: String?): CryptoCurrency =
            entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: RUB
    }
}

/**
 * One crypto quote. Prices are independent — never derived from each other.
 * A missing side stays null and must not be shown as a fake number.
 */
data class CryptoRate(
    /** CoinGecko id: bitcoin | ethereum | solana */
    val id: String,
    /** Ticker: BTC | ETH | SOL */
    val symbol: String,
    val name: String,
    /** Price in RUB or null if API did not return it. */
    val priceRub: Double? = null,
    /** Price in USD or null if API did not return it. */
    val priceUsd: Double? = null,
    /** Emoji/icon glyph for UI. */
    val icon: String
) {
    fun priceFor(currency: CryptoCurrency): Double? = when (currency) {
        CryptoCurrency.RUB -> priceRub
        CryptoCurrency.USD -> priceUsd
    }
}

data class CryptoSnapshot(
    val rates: List<CryptoRate>,
    /** Epoch millis of last successful fetch. */
    val fetchedAtMillis: Long,
    /** Source label shown in UI. */
    val source: String = "CoinGecko"
) {
    fun rate(id: String): CryptoRate? = rates.find { it.id == id }
}

sealed class CryptoResult {
    data class Success(val snapshot: CryptoSnapshot, val fromCache: Boolean) : CryptoResult()
    data class Failure(val message: String, val cached: CryptoSnapshot?) : CryptoResult()
}
