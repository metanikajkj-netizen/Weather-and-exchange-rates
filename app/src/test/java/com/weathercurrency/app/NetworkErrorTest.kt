package com.weathercurrency.app

import com.weathercurrency.app.data.ApiError
import com.weathercurrency.app.data.CbrCurrencyClient
import com.weathercurrency.app.data.OpenMeteoClient
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Network-level error handling tests using MockWebServer. */
class NetworkErrorTest {

    private lateinit var server: MockWebServer
    private val shortTimeoutClient = OkHttpClient.Builder()
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

    @Test
    fun http500IsDetectedAsUnsuccessful() {
        server.enqueue(MockResponse().setResponseCode(500))
        val request = Request.Builder().url(server.url("/v1/forecast")).build()
        shortTimeoutClient.newCall(request).execute().use { resp ->
            assertEquals(500, resp.code)
            assertTrue(!resp.isSuccessful)
        }
    }

    @Test
    fun readTimeoutThrowsIoException() {
        server.enqueue(
            MockResponse().setBody("{}").setBodyDelay(3, TimeUnit.SECONDS)
        )
        val request = Request.Builder().url(server.url("/slow")).build()
        assertThrows(IOException::class.java) {
            shortTimeoutClient.newCall(request).execute().use { it.body?.string() }
        }
    }

    @Test
    fun connectionRefusedThrowsIoException() {
        val deadPort = server.url("/x").port
        server.shutdown()
        val request = Request.Builder().url("http://127.0.0.1:$deadPort/").build()
        assertThrows(IOException::class.java) {
            shortTimeoutClient.newCall(request).execute()
        }
    }

    @Test
    fun openMeteoMissingBlocksThrowsParseError() {
        val client = OpenMeteoClient()
        assertThrows(ApiError.Parse::class.java) {
            client.parse("""{"error":true,"reason":"invalid"}""")
        }
    }

    @Test
    fun openMeteoTruncatedJsonThrowsParseError() {
        val client = OpenMeteoClient()
        assertThrows(ApiError.Parse::class.java) {
            client.parse("""{"current":{"temperature_2m":1.0""")
        }
    }

    @Test
    fun apiErrorHttpCarriesStatusCode() {
        val e = ApiError.Http(503, "ЦБ РФ HTTP 503")
        assertEquals(503, e.code)
        assertTrue(e.message!!.contains("503"))
    }

    @Test
    fun apiErrorNetworkFromTimeout() {
        val timeout = java.net.SocketTimeoutException("connect timed out")
        val e = ApiError.fromIOException(timeout, "Погода")
        assertTrue(e is ApiError.Network)
        assertTrue(e.message!!.contains("таймаут"))
    }

    @Test
    fun apiErrorNetworkFromUnknownHost() {
        val e = ApiError.fromIOException(
            java.net.UnknownHostException("api.open-meteo.com"),
            "Погода"
        )
        assertTrue(e is ApiError.Network)
        assertTrue(e.message!!.contains("DNS"))
    }

    @Test
    fun apiErrorNetworkFromConnectException() {
        val e = ApiError.fromIOException(
            java.net.ConnectException("Failed to connect to /157.90.83.168:443"),
            "Погода"
        )
        assertTrue(e is ApiError.Network)
        assertTrue(e.message!!.contains("недоступен"))
    }

    @Test
    fun cbrGarbageBodyThrowsParseError() {
        val client = CbrCurrencyClient()
        assertThrows(ApiError.Parse::class.java) {
            client.parse("<<<not xml>>")
        }
    }

    @Test
    fun weatherUrlIsHttps() {
        val url = OpenMeteoClient().buildUrl(55.75, 37.62).toString()
        assertTrue(url.startsWith("https://"))
    }

    @Test
    fun cbrUrlIsHttps() {
        assertTrue(CbrCurrencyClient.CBR_URL.startsWith("https://"))
    }

    @Test
    fun fetchAgainstDeadEndpointYieldsNetworkError() {
        // Client pointed only at a dead local endpoint → ApiError.Network
        val client = OpenMeteoClient(
            http = OkHttpClient.Builder()
                .connectTimeout(500, TimeUnit.MILLISECONDS)
                .readTimeout(500, TimeUnit.MILLISECONDS)
                .build(),
            endpoints = listOf("http://127.0.0.1:${server.url("/").port}")
        )
        server.shutdown()
        val e = assertThrows(ApiError.Network::class.java) {
            client.fetch(55.75, 37.62)
        }
        assertTrue(e.message!!.isNotBlank())
    }

    @Test
    fun fetchHttp500YieldsHttpError() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("err"))
        val base = server.url("/").toString().trimEnd('/')
        val client = OpenMeteoClient(
            http = shortTimeoutClient,
            endpoints = listOf(base)
        )
        val e = assertThrows(ApiError.Http::class.java) {
            client.fetch(55.75, 37.62)
        }
        assertEquals(500, e.code)
    }
}
