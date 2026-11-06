package com.weathercurrency.app.proxy

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.weathercurrency.app.data.NetworkDiag
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Tests HTTP/HTTPS proxy connectivity without enabling VPN.
 *
 * Steps: TCP (optional TLS to proxy) → HTTP CONNECT → TLS to target → HTTP request.
 * Certificates are verified (default SSLContext). Timeouts are enforced.
 */
class ProxyTester(private val context: Context) {

    data class Result(
        val success: Boolean,
        val message: String,
        val latencyMs: Long? = null
    )

    suspend fun test(config: ProxyConfig): Result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        if (!isOnline()) {
            return@withContext Result(false, "Нет интернет-соединения")
        }
        if (!config.isConfigured) {
            return@withContext Result(false, "Прокси не настроен")
        }

        val start = System.currentTimeMillis()
        var socket: Socket? = null
        try {
            // 1) Connect to proxy
            socket = Socket()
            socket.tcpNoDelay = true
            val addr = InetSocketAddress(config.host, config.port)
            socket.connect(addr, CONNECT_TIMEOUT_MS)

            // 2) TLS to proxy endpoint if HTTPS type
            val stream: java.io.InputStream
            val out: java.io.OutputStream
            if (config.type == ProxyConfig.Type.HTTPS) {
                val factory: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
                val base = socket ?: return@withContext Result(false, "Сокет закрыт")
                val tls = factory.createSocket(
                    base, config.host, config.port, true
                ) as SSLSocket
                // Default handshake verifies certificates against system CA pool.
                tls.startHandshake()
                stream = tls.inputStream
                out = tls.outputStream
                // From here on, close both sockets together
                socket = tls
            } else {
                stream = socket.inputStream
                out = socket.outputStream
            }

            // 3) HTTP CONNECT
            val target = "${ProxyConfig.TEST_URL_HOST}:${ProxyConfig.TEST_URL_PORT}"
            val connectReq = buildString {
                append("CONNECT $target HTTP/1.1\r\n")
                append("Host: $target\r\n")
                append("Proxy-Connection: Keep-Alive\r\n")
                if (config.username.isNotBlank()) {
                    val token = android.util.Base64.encodeToString(
                        "${config.username}:${config.password}".toByteArray(Charsets.UTF_8),
                        android.util.Base64.NO_WRAP
                    )
                    append("Proxy-Authorization: Basic $token\r\n")
                }
                append("\r\n")
            }
            out.write(connectReq.toByteArray(Charsets.US_ASCII))
            out.flush()

            val reader = BufferedReader(InputStreamReader(stream, Charsets.US_ASCII))
            val statusLine = reader.readLine()
                ?: return@withContext Result(false, "Прокси не ответил на CONNECT", elapsed(start))
            val code = statusLine.split(" ").getOrNull(1)?.toIntOrNull()
            when (code) {
                200 -> { /* ok */ }
                407 -> return@withContext Result(
                    false, "Неверный логин или пароль (HTTP 407)", elapsed(start)
                )
                405, 502, 503 -> return@withContext Result(
                    false, "Прокси отклонил CONNECT (HTTP $code)", elapsed(start)
                )
                null -> return@withContext Result(
                    false, "Некорректный ответ прокси", elapsed(start)
                )
                else -> return@withContext Result(
                    false, "Прокси отклонил CONNECT (HTTP $code)", elapsed(start)
                )
            }
            // Consume headers until blank line
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }

            // 4) TLS to target through tunnel (verifies target certificate)
            val tunnel = socket ?: return@withContext Result(false, "Туннель закрыт")
            val sf = SSLSocketFactory.getDefault() as SSLSocketFactory
            val targetSocket = sf.createSocket(
                tunnel,
                ProxyConfig.TEST_URL_HOST,
                ProxyConfig.TEST_URL_PORT,
                true
            ) as SSLSocket
            targetSocket.startHandshake()

            // 5) Real HTTPS request
            val req = "GET /generate_204 HTTP/1.1\r\nHost: ${ProxyConfig.TEST_URL_HOST}\r\nConnection: close\r\n\r\n"
            targetSocket.outputStream.write(req.toByteArray(Charsets.US_ASCII))
            targetSocket.outputStream.flush()
            val respReader = BufferedReader(InputStreamReader(targetSocket.inputStream, Charsets.US_ASCII))
            val respStatus = respReader.readLine() ?: ""
            targetSocket.close()

            val respCode = respStatus.split(" ").getOrNull(1)?.toIntOrNull()
            if (respCode != null && respCode in 200..499) {
                Result(
                    true,
                    "Прокси доступен. Подключение успешно",
                    elapsed(start)
                )
            } else {
                Result(false, "Неожиданный ответ целевого сервера", elapsed(start))
            }
        } catch (e: UnknownHostException) {
            Result(false, "Ошибка DNS: не удалось разрешить адрес", elapsed(start))
        } catch (e: java.net.SocketTimeoutException) {
            Result(false, "Таймаут подключения к прокси", elapsed(start))
        } catch (e: javax.net.ssl.SSLException) {
            Result(false, "Ошибка TLS: ${e.message ?: "сертификат"}", elapsed(start))
        } catch (e: java.net.ConnectException) {
            val msg = e.message ?: ""
            if (msg.contains("refused", true)) {
                Result(false, "Прокси недоступен: соединение отклонено", elapsed(start))
            } else {
                Result(false, "Прокси недоступен", elapsed(start))
            }
        } catch (e: java.io.IOException) {
            val msg = e.message ?: e.javaClass.simpleName
            when {
                msg.contains("auth", true) || msg.contains("407", true) ->
                    Result(false, "Неверный логин или пароль", elapsed(start))
                msg.contains("CONNECT", true) ->
                    Result(false, "Прокси отклонил CONNECT", elapsed(start))
                msg.contains("timeout", true) || msg.contains("timed out", true) ->
                    Result(false, "Таймаут подключения", elapsed(start))
                else ->
                    Result(false, "Ошибка соединения: ${msg.take(120)}", elapsed(start))
            }
        } catch (e: Exception) {
            NetworkDiag.logError("ProxyTester", e)
            Result(false, "Ошибка проверки: ${e.javaClass.simpleName}", elapsed(start))
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    private fun elapsed(start: Long): Long = System.currentTimeMillis() - start

    private fun isOnline(): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
            true
        }
    }

    companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
    }
}
