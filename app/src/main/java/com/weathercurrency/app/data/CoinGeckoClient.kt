package com.weathercurrency.app.data

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.weathercurrency.app.model.CryptoRate
import com.weathercurrency.app.model.CryptoSnapshot
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/**
 * CoinGecko public Simple Price API (no key).
 * Docs: https://docs.coingecko.com/reference/simple-price
 * Free public tier is rate-limited (HTTP 429 possible).
 *
 * Fetches RUB and USD in one request (`vs_currencies=rub,usd`).
 * USD is NOT converted via CBR — both sides come from CoinGecko.
 */
class CoinGeckoClient(
    private val http: OkHttpClient = defaultClient(),
    private val gson: Gson = Gson()
) {

    data class Parsed(val rates: List<CryptoRate>)

    @Throws(ApiError::class)
    fun fetch(): CryptoSnapshot {
        val url = buildUrl() ?: throw ApiError.Empty("CoinGecko: некорректный URL")
        val request = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/json")
            .header("User-Agent", "WeatherCurrencyApp/1.4")
            .build()

        try {
            http.newCall(request).execute().use { response ->
                when {
                    response.code == 429 ->
                        throw ApiError.Http(429, "CoinGecko: превышен лимит запросов (HTTP 429)")
                    response.code == 401 || response.code == 403 ->
                        throw ApiError.Http(response.code, "CoinGecko: доступ запрещён (HTTP ${response.code})")
                    !response.isSuccessful ->
                        throw ApiError.Http(response.code, "CoinGecko HTTP ${response.code}")
                }
                val body = response.body?.string()
                if (body.isNullOrBlank()) throw ApiError.Empty("CoinGecko: пустой ответ")
                NetworkDiag.logInfo("CoinGecko", "HTTP ${response.code} bytes=${body.length}")
                val parsed = parse(body)
                return CryptoSnapshot(
                    rates = parsed.rates,
                    fetchedAtMillis = System.currentTimeMillis(),
                    source = SOURCE
                )
            }
        } catch (e: ApiError) {
            NetworkDiag.logError("CoinGecko.fetch", e)
            throw e
        } catch (e: SocketTimeoutException) {
            NetworkDiag.logError("CoinGecko.fetch", e)
            throw ApiError.Network("Криптовалюты: истёк таймаут подключения", e)
        } catch (e: UnknownHostException) {
            NetworkDiag.logError("CoinGecko.fetch", e)
            throw ApiError.Network("Криптовалюты: сервер не найден (DNS)", e)
        } catch (e: SSLException) {
            NetworkDiag.logError("CoinGecko.fetch", e)
            throw ApiError.Network("Криптовалюты: ошибка TLS/SSL", e)
        } catch (e: IOException) {
            NetworkDiag.logError("CoinGecko.fetch", e)
            throw ApiError.Network("Криптовалюты: сервер недоступен", e)
        } catch (e: Exception) {
            NetworkDiag.logError("CoinGecko.fetch", e)
            throw ApiError.Parse("Криптовалюты: ошибка обработки ответа", e)
        }
    }

    fun buildUrl(): okhttp3.HttpUrl? =
        "$BASE_URL/api/v3/simple/price".toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("ids", "bitcoin,ethereum,solana")
            ?.addQueryParameter("vs_currencies", "rub,usd")
            ?.build()

    /**
     * Parses CoinGecko simple/price JSON for rub and usd.
     * Each currency is independent — missing side stays null (never derived).
     * Empty overall → Parse error (no invented/zero prices).
     */
    @Throws(ApiError::class)
    fun parse(json: String): Parsed {
        val root = try {
            gson.fromJson(json, JsonObject::class.java)
        } catch (e: Exception) {
            throw ApiError.Parse("Криптовалюты: некорректный JSON", e)
        } ?: throw ApiError.Empty("Криптовалюты: пустой JSON")

        if (root.has("error") && !root.get("error").isJsonNull) {
            val msg = if (root.has("error_message")) root.get("error_message").asString else "ошибка API"
            throw ApiError.Parse("Криптовалюты: $msg")
        }

        val rates = mutableListOf<CryptoRate>()
        for (meta in COINS) {
            if (!root.has(meta.id) || !root.get(meta.id).isJsonObject) continue
            val obj = root.getAsJsonObject(meta.id)
            val rub = positiveOrNull(obj, "rub")
            val usd = positiveOrNull(obj, "usd")
            if (rub == null && usd == null) continue
            rates += CryptoRate(
                id = meta.id,
                symbol = meta.symbol,
                name = meta.name,
                priceRub = rub,
                priceUsd = usd,
                icon = meta.icon
            )
        }

        if (rates.isEmpty()) {
            throw ApiError.Parse("Криптовалюты: нет цен BTC/ETH/SOL в ответе")
        }
        val ordered = COINS.mapNotNull { m -> rates.find { it.id == m.id } }
        return Parsed(ordered)
    }

    private fun positiveOrNull(obj: JsonObject, key: String): Double? {
        if (!obj.has(key) || obj.get(key).isJsonNull) return null
        val v = try {
            obj.get(key).asDouble
        } catch (_: Exception) {
            return null
        }
        return if (v.isNaN() || v.isInfinite() || v <= 0.0) null else v
    }

    companion object {
        const val BASE_URL = "https://api.coingecko.com"
        const val SOURCE = "CoinGecko"

        data class CoinMeta(val id: String, val symbol: String, val name: String, val icon: String)

        val COINS: List<CoinMeta> = listOf(
            CoinMeta("bitcoin", "BTC", "Bitcoin", "₿"),
            CoinMeta("ethereum", "ETH", "Ethereum", "Ξ"),
            CoinMeta("solana", "SOL", "Solana", "◎")
        )

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(18, TimeUnit.SECONDS)
            .writeTimeout(12, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
