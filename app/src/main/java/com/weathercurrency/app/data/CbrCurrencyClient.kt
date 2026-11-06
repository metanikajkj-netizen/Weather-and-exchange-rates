package com.weathercurrency.app.data

import com.weathercurrency.app.model.CurrencyRate
import com.weathercurrency.app.model.CurrencySnapshot
import okhttp3.OkHttpClient
import okhttp3.Request
import org.w3c.dom.Element
import java.io.IOException
import java.io.StringReader
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Official CBR daily currency XML API: https://www.cbr.ru/scripts/XML_daily.asp
 * Encoding: windows-1251 (from Content-Type or XML declaration).
 *
 * Android's built-in DocumentBuilderFactory does not support several
 * Apache Xerces security features — they are applied best-effort so a
 * successful HTTP response is never turned into a parse crash.
 */
class CbrCurrencyClient(
    private val http: OkHttpClient = defaultClient()
) {

    private val required = listOf("USD", "EUR", "CNY")

    @Throws(ApiError::class)
    fun fetch(): CurrencySnapshot {
        val request = Request.Builder()
            .url(CBR_URL)
            .get()
            .header("User-Agent", "WeatherCurrencyApp/1.1")
            .header("Accept", "application/xml,text/xml,*/*")
            .build()

        try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw ApiError.Http(response.code, "ЦБ РФ HTTP ${response.code}")
                }
                val body = response.body
                val bytes = body?.bytes()
                    ?: throw ApiError.Empty("ЦБ РФ: пустой ответ")
                if (bytes.isEmpty()) throw ApiError.Empty("ЦБ РФ: пустой ответ")

                val contentType = response.header("Content-Type")
                val xml = decodeCbr(bytes, contentType)
                NetworkDiag.logInfo(
                    "CBR",
                    "HTTP ${response.code} bytes=${bytes.size} charset=${charsetOf(contentType)}"
                )
                return parse(xml, System.currentTimeMillis())
            }
        } catch (e: ApiError) {
            NetworkDiag.logError("CBR.fetch", e)
            throw e
        } catch (e: SocketTimeoutException) {
            NetworkDiag.logError("CBR.fetch", e)
            throw ApiError.Network("ЦБ РФ: истёк таймаут подключения", e)
        } catch (e: UnknownHostException) {
            NetworkDiag.logError("CBR.fetch", e)
            throw ApiError.Network("ЦБ РФ: сервер не найден (DNS)", e)
        } catch (e: SSLException) {
            NetworkDiag.logError("CBR.fetch", e)
            throw ApiError.Network("ЦБ РФ: ошибка TLS/SSL", e)
        } catch (e: IOException) {
            NetworkDiag.logError("CBR.fetch", e)
            throw ApiError.Network("ЦБ РФ: сервер недоступен", e)
        } catch (e: Exception) {
            NetworkDiag.logError("CBR.fetch", e)
            throw ApiError.Parse("ЦБ РФ: ошибка обработки ответа", e)
        }
    }

    private fun charsetOf(contentType: String?): String {
        val fromHeader = contentType
            ?.substringAfter("charset=", "")
            ?.substringBefore(";")
            ?.trim()
            ?.lowercase()
        return if (!fromHeader.isNullOrEmpty()) fromHeader else "windows-1251"
    }

    /**
     * CBR serves windows-1251. Prefer charset from Content-Type; otherwise
     * fall back to windows-1251 for this endpoint.
     */
    fun decodeCbr(bytes: ByteArray, contentType: String? = null): String {
        val charsetName = charsetOf(contentType)
        return try {
            String(bytes, charset(charsetName))
        } catch (_: Exception) {
            try {
                String(bytes, Charset.forName("windows-1251"))
            } catch (_: Exception) {
                String(bytes, Charsets.UTF_8)
            }
        }
    }

    /**
     * Parses ValCurs XML. Extracts USD/EUR/CNY, divides Value by Nominal
     * so the rate is always per 1 unit. Number format uses comma decimal.
     */
    @Throws(ApiError::class)
    fun parse(xml: String, fetchedAtMillis: Long = System.currentTimeMillis()): CurrencySnapshot {
        if (xml.isBlank()) throw ApiError.Empty("ЦБ РФ: пустой XML")

        val rateDate = Regex("""Date\s*=\s*"([^"]+)"""").find(xml)?.groupValues?.get(1)
            ?: throw ApiError.Parse("ЦБ РФ: нет даты курса")

        val doc = try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = false
            factory.isCoalescing = true
            // Best-effort XXE hardening — Android XML parsers may not know these features.
            quietlySetFeature(factory, "http://apache.org/xml/features/disallow-doctype-decl", true)
            quietlySetFeature(factory, "http://xml.org/sax/features/external-general-entities", false)
            quietlySetFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false)
            quietlySetFeature(factory, "http://javax.xml.XMLConstants/feature/secure-processing", true)
            factory.newDocumentBuilder().parse(org.xml.sax.InputSource(StringReader(xml)))
        } catch (e: ApiError) {
            throw e
        } catch (e: IOException) {
            throw ApiError.Parse("ЦБ РФ: ошибка чтения XML", e)
        } catch (e: Exception) {
            throw ApiError.Parse("ЦБ РФ: ошибка разбора XML", e)
        }

        val found = mutableMapOf<String, CurrencyRate>()
        val nodes = doc.getElementsByTagName("Valute")
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as? Element ?: continue
            val code = childText(el, "CharCode")?.trim() ?: continue
            if (code !in required) continue
            val nominal = childText(el, "Nominal")?.trim()?.toIntOrNull() ?: continue
            // CBR uses comma as decimal separator: "92,1234"
            val value = childText(el, "Value")?.trim()
                ?.replace("\u00A0", "")
                ?.replace(" ", "")
                ?.replace(",", ".")
                ?.toDoubleOrNull()
                ?: continue
            if (nominal <= 0) continue
            found[code] = CurrencyRate(
                charCode = code,
                ratePerUnit = value / nominal,
                nominal = nominal
            )
        }

        val ordered = required.mapNotNull { found[it] }
        if (ordered.isEmpty()) {
            throw ApiError.Parse("ЦБ РФ: нет нужных валют в ответе (USD/EUR/CNY)")
        }

        return CurrencySnapshot(
            rates = ordered,
            rateDate = rateDate,
            fetchedAtMillis = fetchedAtMillis
        )
    }

    private fun quietlySetFeature(
        factory: DocumentBuilderFactory,
        name: String,
        value: Boolean
    ) {
        try {
            factory.setFeature(name, value)
        } catch (_: Exception) {
            // Feature unsupported on this platform (common on Android) — skip.
        }
    }

    private fun childText(parent: Element, tag: String): String? {
        val list = parent.getElementsByTagName(tag)
        if (list.length == 0) return null
        return list.item(0).textContent
    }

    companion object {
        const val CBR_URL = "https://www.cbr.ru/scripts/XML_daily.asp"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(18, TimeUnit.SECONDS)
            .writeTimeout(12, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
