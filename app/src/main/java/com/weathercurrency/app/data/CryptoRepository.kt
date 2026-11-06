package com.weathercurrency.app.data

import android.content.Context
import com.weathercurrency.app.model.CryptoResult
import com.weathercurrency.app.model.CryptoSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Fetches crypto quotes with DataStore cache and simple client-side throttle
 * (CoinGecko free tier is rate-limited). No continuous background polling.
 */
class CryptoRepository(
    private val context: Context,
    private val api: CoinGeckoClient = CoinGeckoClient(),
    private val cache: CacheStore = CacheStore(context)
) {
    @Volatile
    private var lastFetchMillis: Long = 0L

    suspend fun refresh(): CryptoResult = withContext(Dispatchers.IO) {
        val cached = cache.loadCrypto()

        if (!NetworkDiag.isOnline(context)) {
            return@withContext CryptoResult.Failure(
                "Нет подключения к интернету",
                cached
            )
        }

        // Throttle automatic repeats (user taps still call refresh(); keep a short gap).
        val now = System.currentTimeMillis()
        if (now - lastFetchMillis < MIN_INTERVAL_MS && cached != null) {
            return@withContext CryptoResult.Success(cached, fromCache = true)
        }

        try {
            val snapshot = api.fetch()
            lastFetchMillis = System.currentTimeMillis()
            cache.saveCrypto(snapshot)
            NetworkDiag.logInfo("Crypto", "refresh ok n=${snapshot.rates.size}")
            CryptoResult.Success(snapshot, fromCache = false)
        } catch (e: ApiError) {
            NetworkDiag.logError("Crypto.refresh", e)
            CryptoResult.Failure(uiMessage(e), cached)
        } catch (e: Exception) {
            NetworkDiag.logError("Crypto.refresh", e)
            CryptoResult.Failure("Ошибка обработки ответа CoinGecko", cached)
        }
    }

    suspend fun cached(): CryptoSnapshot? = cache.loadCrypto()

    internal fun uiMessage(e: ApiError): String = when (e) {
        is ApiError.Network -> e.message ?: "Нет подключения к интернету"
        is ApiError.Http -> when (e.code) {
            429 -> "Слишком много запросов к CoinGecko (HTTP 429), попробуйте позже"
            401, 403 -> "Доступ к CoinGecko запрещён (HTTP ${e.code})"
            in 500..599 -> "Сервис CoinGecko недоступен (HTTP ${e.code})"
            else -> "Ошибка HTTP ${e.code} при загрузке криптовалют"
        }
        is ApiError.Parse -> "Ошибка обработки ответа CoinGecko"
        is ApiError.Empty -> "Ошибка обработки ответа CoinGecko"
    }

    companion object {
        /** Min gap between automatic fetches. */
        const val MIN_INTERVAL_MS = 20_000L
    }
}
