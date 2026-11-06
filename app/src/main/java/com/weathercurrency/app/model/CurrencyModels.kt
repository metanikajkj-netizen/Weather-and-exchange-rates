package com.weathercurrency.app.model

/** One currency rate expressed per 1 RUB unit of foreign currency. */
data class CurrencyRate(
    val charCode: String,
    /** Official value divided by Nominal — cost of 1 unit in RUB. */
    val ratePerUnit: Double,
    val nominal: Int
)

data class CurrencySnapshot(
    val rates: List<CurrencyRate>,
    /** Official rate date dd.MM.yyyy from CBR. */
    val rateDate: String,
    /** Epoch millis of last successful fetch. */
    val fetchedAtMillis: Long
) {
    fun rate(charCode: String): CurrencyRate? = rates.find { it.charCode == charCode }
}

sealed class CurrencyResult {
    data class Success(val snapshot: CurrencySnapshot, val fromCache: Boolean) : CurrencyResult()
    data class Failure(val message: String, val cached: CurrencySnapshot?) : CurrencyResult()
}
