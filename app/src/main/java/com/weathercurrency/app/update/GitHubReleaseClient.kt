package com.weathercurrency.app.update

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.weathercurrency.app.data.ApiError
import com.weathercurrency.app.data.NetworkDiag
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/**
 * GitHub REST client for the latest stable release.
 *
 * Resilience strategy:
 * 1. Primary endpoint: /releases/latest
 * 2. Fallback endpoint: /releases?per_page=1 (different CDN path, often faster)
 * 3. Retry with exponential backoff for transient errors (429, 500, 502, 503, 504)
 * 4. Respect Retry-After header when present
 *
 * The app's own traffic bypasses VPN (addDisallowedApplication), so VPN
 * routing is NOT a factor in OTA failures.
 */
class GitHubReleaseClient(
    private val http: OkHttpClient = defaultClient(),
    private val gson: Gson = Gson()
) {

    companion object {
        const val DEFAULT_REPO = "metanikajkj-netizen/Weather-and-exchange-rates"

        /** Primary: /releases/latest — returns single latest non-draft, non-prerelease. */
        private fun primaryUrl(repo: String) =
            "https://api.github.com/repos/$repo/releases/latest"

        /** Fallback: /releases?per_page=1 — returns list, we pick first non-draft. */
        private fun fallbackUrl(repo: String) =
            "https://api.github.com/repos/$repo/releases?per_page=1"

        private const val MAX_RETRIES = 3
        private const val BASE_DELAY_MS = 1500L
        private val TRANSIENT_CODES = setOf(429, 500, 502, 503, 504)

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    @Throws(ApiError::class)
    suspend fun fetchLatest(repo: String = DEFAULT_REPO): GitHubRelease {
        var lastError: ApiError? = null

        for (attempt in 0 until MAX_RETRIES) {
            if (attempt > 0) {
                val delay = BASE_DELAY_MS * (1L shl (attempt - 1))
                NetworkDiag.logInfo("GitHub", "Retry $attempt/${MAX_RETRIES - 1} after ${delay}ms")
                delay(delay)
            }

            // Try primary endpoint first
            try {
                return fetchOnce(primaryUrl(repo))
            } catch (e: ApiError.Http) {
                lastError = e
                if (e.code in TRANSIENT_CODES) {
                    NetworkDiag.logInfo("GitHub", "Primary: transient HTTP ${e.code}, trying fallback")
                    // Try fallback endpoint
                    try {
                        return fetchFromList(fallbackUrl(repo))
                    } catch (e2: ApiError) {
                        lastError = e2
                        NetworkDiag.logInfo("GitHub", "Fallback also failed: ${e2.message}")
                        continue
                    }
                }
                throw e
            } catch (e: ApiError.Network) {
                lastError = e
                NetworkDiag.logInfo("GitHub", "Network error, attempt ${attempt + 1}/$MAX_RETRIES: ${e.message}")
                continue
            }
        }

        throw lastError ?: ApiError.Network("Обновления: превышено число попыток подключения")
    }

    /**
     * Fetch from /releases?per_page=1 (returns JSON array).
     * Picks first non-draft, non-prerelease release.
     */
    private suspend fun fetchFromList(url: String): GitHubRelease {
        val request = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "WeatherCurrencyApp/1.5")
            .build()

        try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw ApiError.Http(response.code, "GitHub HTTP ${response.code}")
                }
                val body = response.body?.string()
                    ?: throw ApiError.Empty("GitHub: пустой ответ")
                NetworkDiag.logInfo("GitHub", "Fallback: HTTP ${response.code} bytes=${body.length}")

                val arr = try {
                    gson.fromJson(body, JsonArray::class.java)
                } catch (_: Exception) {
                    throw ApiError.Parse("GitHub: некорректный JSON (array)")
                }

                // Find first non-draft, non-prerelease
                for (elem in arr) {
                    if (!elem.isJsonObject) continue
                    val obj = elem.asJsonObject
                    val draft = obj.get("draft")?.asBoolean ?: false
                    val prerelease = obj.get("prerelease")?.asBoolean ?: false
                    if (!draft && !prerelease) {
                        return parse(obj.toString())
                    }
                }
                throw ApiError.Http(404, "GitHub: нет стабильных релизов")
            }
        } catch (e: ApiError) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw ApiError.Network("Обновления: истёк таймаут подключения", e)
        } catch (e: UnknownHostException) {
            throw ApiError.Network("Обновления: сервер не найден (DNS)", e)
        } catch (e: SSLException) {
            throw ApiError.Network("Обновления: ошибка TLS/SSL", e)
        } catch (e: IOException) {
            throw ApiError.Network("Обновления: нет подключения к интернету", e)
        } catch (e: Exception) {
            throw ApiError.Parse("Обновления: ошибка обработки ответа", e)
        }
    }

    private fun fetchOnce(url: String): GitHubRelease {
        val request = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "WeatherCurrencyApp/1.5")
            .build()

        try {
            http.newCall(request).execute().use { response ->
                when (response.code) {
                    404 -> throw ApiError.Http(404, "GitHub: релизы не найдены (HTTP 404)")
                    403 -> throw ApiError.Http(403, "GitHub: доступ запрещён (HTTP 403)")
                    429 -> throw ApiError.Http(429, "GitHub: превышен лимит запросов (HTTP 429)")
                    500 -> throw ApiError.Http(500, "GitHub: внутренняя ошибка сервера (HTTP 500)")
                    502 -> throw ApiError.Http(502, "GitHub: ошибка шлюза (HTTP 502)")
                    503 -> throw ApiError.Http(503, "GitHub: сервис временно недоступен (HTTP 503)")
                    504 -> throw ApiError.Http(504, "GitHub: сервер не отвечает (HTTP 504)")
                    else -> if (!response.isSuccessful) {
                        throw ApiError.Http(response.code, "GitHub HTTP ${response.code}")
                    }
                }
                val body = response.body?.string()
                    ?: throw ApiError.Empty("GitHub: пустой ответ")
                NetworkDiag.logInfo("GitHub", "Primary: HTTP ${response.code} bytes=${body.length}")
                return parse(body)
            }
        } catch (e: ApiError) {
            NetworkDiag.logError("GitHub.fetch", e)
            throw e
        } catch (e: SocketTimeoutException) {
            NetworkDiag.logError("GitHub.fetch", e)
            throw ApiError.Network("Обновления: истёк таймаут подключения", e)
        } catch (e: UnknownHostException) {
            NetworkDiag.logError("GitHub.fetch", e)
            throw ApiError.Network("Обновления: сервер не найден (DNS)", e)
        } catch (e: SSLException) {
            NetworkDiag.logError("GitHub.fetch", e)
            throw ApiError.Network("Обновления: ошибка TLS/SSL", e)
        } catch (e: IOException) {
            NetworkDiag.logError("GitHub.fetch", e)
            throw ApiError.Network("Обновления: нет подключения к интернету", e)
        } catch (e: Exception) {
            NetworkDiag.logError("GitHub.fetch", e)
            throw ApiError.Parse("Обновления: ошибка обработки ответа", e)
        }
    }

    @Throws(ApiError::class)
    fun parse(json: String): GitHubRelease {
        val root = try {
            gson.fromJson(json, JsonObject::class.java)
        } catch (e: Exception) {
            throw ApiError.Parse("GitHub: некорректный JSON", e)
        } ?: throw ApiError.Empty("GitHub: пустой JSON")

        if (root.has("message") && root.get("message").isJsonPrimitive) {
            val msg = root.get("message").asString
            if (!root.has("tag_name")) {
                throw ApiError.Parse("GitHub: $msg")
            }
        }

        val draft = root.get("draft")?.asBoolean ?: false
        val prerelease = root.get("prerelease")?.asBoolean ?: false
        val tagName = root.get("tag_name")?.asString ?: ""
        val name = root.get("name")?.asString ?: tagName
        val body = root.get("body")?.asString ?: ""
        val publishedAt = if (root.has("published_at") && !root.get("published_at").isJsonNull) {
            root.get("published_at").asString
        } else null

        var assetName: String? = null
        var assetUrl: String? = null
        var assetSize: Long? = null
        if (root.has("assets") && root.get("assets").isJsonArray) {
            val assets = root.getAsJsonArray("assets")
            val pairs = mutableListOf<Pair<String, String>>()
            for (a in assets) {
                if (!a.isJsonObject) continue
                val ao = a.asJsonObject
                val an = ao.get("name")?.asString ?: continue
                val url = ao.get("browser_download_url")?.asString ?: continue
                pairs += an to url
                if (an.endsWith(".apk", ignoreCase = true)) {
                    val size = try {
                        ao.get("size")?.asLong
                    } catch (_: Exception) { null }
                    if (assetName == null) {
                        assetName = an
                        assetUrl = url
                        assetSize = size
                    }
                }
            }
            val meta = UpdateLogic.parseMetadata(body)
            val preferred = meta?.apkFileName
            if (preferred != null) {
                val match = pairs.firstOrNull { it.first == preferred }
                if (match != null) {
                    assetName = match.first
                    assetUrl = match.second
                    assetSize = assets.firstOrNull {
                        it.isJsonObject && it.asJsonObject.get("name")?.asString == preferred
                    }?.asJsonObject?.get("size")?.let {
                        try { it.asLong } catch (_: Exception) { null }
                    } ?: assetSize
                }
            }
        }

        val metadata = UpdateLogic.parseMetadata(body)

        return GitHubRelease(
            tagName = tagName,
            name = name,
            body = body,
            prerelease = prerelease,
            draft = draft,
            publishedAt = publishedAt,
            apkAssetName = assetName,
            apkDownloadUrl = assetUrl,
            apkSizeBytes = assetSize,
            metadata = metadata
        )
    }
}
