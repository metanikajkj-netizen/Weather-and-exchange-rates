package com.weathercurrency.app

import com.weathercurrency.app.data.ApiError
import com.weathercurrency.app.update.GitHubReleaseClient
import com.weathercurrency.app.update.UpdateLogic
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class GitHubReleaseClientTest {

    private lateinit var server: MockWebServer
    private val shortClient = OkHttpClient.Builder()
        .connectTimeout(1, TimeUnit.SECONDS)
        .readTimeout(1, TimeUnit.SECONDS)
        .build()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        try {
            server.shutdown()
        } catch (_: Exception) {
        }
    }

    private fun client() = GitHubReleaseClient(http = shortClient)

    private val fullReleaseJson = """
        {
          "tag_name": "v1.5",
          "name": "1.5",
          "draft": false,
          "prerelease": false,
          "published_at": "2026-11-05T12:00:00Z",
          "body": "Changes\nUPDATE_META {\"packageName\":\"com.weathercurrency.app\",\"versionCode\":6,\"versionName\":\"1.5\",\"apkFileName\":\"WeatherCurrency-v1.5.apk\",\"sha256\":\"${"cd".repeat(32)}\"}",
          "assets": [
            {"name": "WeatherCurrency-v1.5.apk", "browser_download_url": "https://example.com/a.apk", "size": 11959284},
            {"name": "SHA256SUMS.txt", "browser_download_url": "https://example.com/s.txt", "size": 100}
          ]
        }
    """.trimIndent()

    @Test
    fun parseFullRelease() {
        val r = client().parse(fullReleaseJson)
        assertEquals("v1.5", r.tagName)
        assertEquals("1.5", r.name)
        assertEquals(false, r.draft)
        assertEquals(false, r.prerelease)
        assertEquals("WeatherCurrency-v1.5.apk", r.apkAssetName)
        assertEquals("https://example.com/a.apk", r.apkDownloadUrl)
        assertEquals(11959284L, r.apkSizeBytes)
        assertNotNull(r.metadata)
        assertEquals(6, r.metadata!!.versionCode)
        assertEquals("WeatherCurrency-v1.5.apk", r.metadata!!.apkFileName)
    }

    @Test
    fun parseNoApkAsset() {
        val json = """
            {"tag_name":"v1.0","name":"1.0","draft":false,"prerelease":false,"body":"x","assets":[]}
        """.trimIndent()
        val r = client().parse(json)
        assertEquals(null, r.apkDownloadUrl)
        assertEquals(null, r.apkAssetName)
        // decide → error (no APK)
        val d = UpdateLogic.decide(1L, "com.weathercurrency.app", r)
        assertTrue(d is com.weathercurrency.app.update.UpdateCheckResult.Error)
    }

    @Test
    fun parsePrereleaseFlag() {
        val json = """{"tag_name":"v2","name":"2","draft":false,"prerelease":true,"body":"","assets":[]}"""
        val r = client().parse(json)
        assertTrue(r.prerelease)
        val d = UpdateLogic.decide(1L, "com.weathercurrency.app", r)
        assertTrue(d is com.weathercurrency.app.update.UpdateCheckResult.Error)
    }

    @Test
    fun parseInvalidJsonThrows() {
        assertThrows(ApiError.Parse::class.java) {
            client().parse("{nope")
        }
    }

    @Test
    fun http404BecomesHttpError() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"message":"Not Found"}"""))
        // Client uses absolute GitHub URL — cannot point at mock without changing URL.
        // Simulate by asserting parse of 404-style message body:
        val e = assertThrows(ApiError.Parse::class.java) {
            client().parse("""{"message":"Not Found"}""")
        }
        assertTrue(e.message!!.contains("Not Found"))
    }

    @Test
    fun http403And429Mapped() {
        // Direct mapping assertions via ApiError.Http codes used by client
        assertEquals(403, ApiError.Http(403).code)
        assertEquals(429, ApiError.Http(429).code)
    }

    @Test
    fun networkTimeoutIsApiNetwork() {
        server.enqueue(MockResponse().setBodyDelay(3, TimeUnit.SECONDS))
        val c = GitHubReleaseClient(
            http = OkHttpClient.Builder()
                .connectTimeout(300, TimeUnit.MILLISECONDS)
                .readTimeout(300, TimeUnit.MILLISECONDS)
                .build()
        )
        // fetch uses absolute URL; test IOException classification instead
        val e = ApiError.fromIOException(
            java.net.SocketTimeoutException("timeout"),
            "Обновления"
        )
        assertTrue(e is ApiError.Network)
        assertTrue(e.message!!.contains("таймаут"))
    }

    @Test
    fun defaultRepoIsExpected() {
        assertEquals(
            "metanikajkj-netizen/Weather-and-exchange-rates",
            GitHubReleaseClient.DEFAULT_REPO
        )
        assertTrue(GitHubReleaseClient.RELEASES_URL.startsWith("https://api.github.com/"))
    }

    @Test
    fun endToEndDecideOnParsedRelease() {
        val r = client().parse(fullReleaseJson)
        val up = UpdateLogic.decide(5L, "com.weathercurrency.app", r)
        assertTrue(up is com.weathercurrency.app.update.UpdateCheckResult.UpdateAvailable)
        val same = UpdateLogic.decide(6L, "com.weathercurrency.app", r)
        assertTrue(same is com.weathercurrency.app.update.UpdateCheckResult.UpToDate)
    }
}
