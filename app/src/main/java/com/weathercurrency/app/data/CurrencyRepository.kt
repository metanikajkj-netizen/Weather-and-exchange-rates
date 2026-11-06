package com.weathercurrency.app.data

import android.content.Context
import com.weathercurrency.app.model.CurrencyResult
import com.weathercurrency.app.model.CurrencySnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Fetches official CBR rates and maintains last-success cache. */
class CurrencyRepository(
    private val context: Context,
    private val api: CbrCurrencyClient = CbrCurrencyClient(),
    private val cache: CacheStore = CacheStore(context)
) {

    suspend fun refresh(): CurrencyResult = withContext(Dispatchers.IO) {
        val cached = cache.loadCurrency()

        if (!NetworkDiag.isOnline(context)) {
            return@withContext CurrencyResult.Failure(
                "Нет подключения к интернету",
                cached
            )
        }

        try {
            val snapshot = api.fetch()
            cache.saveCurrency(snapshot)
            NetworkDiag.logInfo("Currency", "refresh ok date=${snapshot.rateDate}")
            CurrencyResult.Success(snapshot, fromCache = false)
        } catch (e: ApiError) {
            NetworkDiag.logError("Currency.refresh", e)
            CurrencyResult.Failure(uiMessage(e), cached)
        } catch (e: Exception) {
            NetworkDiag.logError("Currency.refresh", e)
            CurrencyResult.Failure("Ошибка обработки ответа ЦБ РФ", cached)
        }
    }

    suspend fun cached(): CurrencySnapshot? = cache.loadCurrency()

    internal fun uiMessage(e: ApiError): String = when (e) {
        is ApiError.Network -> e.message ?: "Нет подключения к интернету"
        is ApiError.Http -> when {
            e.code >= 500 -> "Сервис ЦБ РФ недоступен (HTTP ${e.code})"
            else -> "Ошибка HTTP ${e.code} при загрузке курсов"
        }
        is ApiError.Parse -> "Ошибка обработки ответа ЦБ РФ"
        is ApiError.Empty -> "Ошибка обработки ответа ЦБ РФ"
    }
}
