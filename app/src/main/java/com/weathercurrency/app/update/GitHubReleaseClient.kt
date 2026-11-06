package com.weathercurrency.app.update

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.weathercurrency.app.data.ApiError
import com.weathercurrency.app.data.NetworkDiag
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/**
 * GitHub REST client for the latest stable release.
 * Docs: https://docs.github.com/rest/releases/releases#get-the-latest-release
 */
class GitHubReleaseClient(
    private val http: OkHttpClient = defaultClient(),
    private val gson: Gson = Gson()
) {

    /**
     * Fetches latest non-draft, non-prerelease release.
     * `/releases/latest` returns 404 when none exist → clear error.
     */
    @Throws(ApiError::class)
    fun fetchLatest(repo: String = DEFAULT_REPO): GitHubRelease {
        val url = "https://api.github.com/repos/$repo/releases/latest"
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
                    else -> if (!response.isSuccessful) {
                        throw ApiError.Http(response.code, "GitHub HTTP ${response.code}")
                    }
                }
                val body = response.body?.string()
                    ?: throw ApiError.Empty("GitHub: пустой ответ")
                NetworkDiag.logInfo("GitHub", "HTTP ${response.code} bytes=${body.length}")
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
            // message present with errors usually means API error body
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
                    } catch (_: Exception) {
                        null
                    }
                    // Prefer first APK; metadata may refine later
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
                        try {
                            it.asLong
                        } catch (_: Exception) {
                            null
                        }
                    } ?: assetSize
                }
            }
        }

        val metadata = UpdateLogic.parseMetadata(body)

        if (assetName == null && !draft && !prerelease) {
            // Still return release so decide() can report missing APK clearly.
        }

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

    companion object {
        const val DEFAULT_REPO = "metanikajkj-netizen/Weather-and-exchange-rates"
        const val RELEASES_URL =
            "https://api.github.com/repos/$DEFAULT_REPO/releases/latest"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(18, TimeUnit.SECONDS)
            .writeTimeout(12, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
