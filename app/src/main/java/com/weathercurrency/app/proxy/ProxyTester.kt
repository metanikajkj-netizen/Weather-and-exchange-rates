package com.weathercurrency.app.proxy

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.weathercurrency.app.data.NetworkDiag
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Tests proxy connectivity without enabling VPN.
 * Supports HTTP CONNECT (http/https) and go-gost Relay v1 (relay) protocols.
 * Certificates are verified (default SSLContext). Timeouts are enforced.
 *
 * For RELAY type, performs real HTTPS through the relay tunnel:
 * 1. TLS to gateway
 * 2. Relay CONNECT handshake
 * 3. TLS to target (www.gstatic.com:443) through relay tunnel
 * 4. HTTPS GET /generate_204
 * 5. Verify HTTP 2xx response
 */
class ProxyTester(private val context: Context) {

    data class Result(
        val success: Boolean,
        val message: String,
        val latencyMs: Long? = null,
        val stage: String = "",
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

            // 2) TLS for HTTPS and RELAY types
            val stream: java.io.InputStream
            val out: java.io.OutputStream
            if (config.type == ProxyConfig.Type.HTTPS || config.type == ProxyConfig.Type.RELAY) {
                val factory: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
                val base = socket ?: return@withContext Result(false, "Сокет закрыт")
                val tls = factory.createSocket(
                    base, config.host, config.port, true
                ) as SSLSocket
                tls.startHandshake()
                stream = tls.inputStream
                out = tls.outputStream
                socket = tls
            } else {
                stream = socket.inputStream
                out = socket.outputStream
            }

            if (config.type == ProxyConfig.Type.RELAY) {
                return@withContext testRelay(stream, out, socket!!, config, start)
            }

            // HTTP CONNECT test
            return@withContext testHttpConnect(stream, out, config, start, socket ?: return@withContext Result(false, "Сокет закрыт", elapsed(start)))

        } catch (e: UnknownHostException) {
            Result(false, "Ошибка DNS: не удалось разрешить адрес", elapsed(start), "CONNECT")
        } catch (e: java.net.SocketTimeoutException) {
            Result(false, "Таймаут подключения к прокси", elapsed(start), "CONNECT")
        } catch (e: javax.net.ssl.SSLException) {
            Result(false, "Ошибка TLS: ${e.message ?: "сертификат"}", elapsed(start), "GATEWAY_TLS")
        } catch (e: java.net.ConnectException) {
            val msg = e.message ?: ""
            if (msg.contains("refused", true)) {
                Result(false, "Прокси недоступен: соединение отклонено", elapsed(start), "CONNECT")
            } else {
                Result(false, "Прокси недоступен", elapsed(start), "CONNECT")
            }
        } catch (e: java.io.IOException) {
            val msg = e.message ?: e.javaClass.simpleName
            val stage = if (msg.contains("auth", true) || msg.contains("407", true)) "AUTH" else "IO"
            when {
                msg.contains("auth", true) || msg.contains("407", true) ->
                    Result(false, "Неверный логин или пароль", elapsed(start), stage)
                msg.contains("CONNECT", true) ->
                    Result(false, "Прокси отклонил CONNECT", elapsed(start), stage)
                msg.contains("timeout", true) || msg.contains("timed out", true) ->
                    Result(false, "Таймаут подключения", elapsed(start), stage)
                else ->
                    Result(false, "Ошибка соединения: ${msg.take(120)}", elapsed(start), stage)
            }
        } catch (e: Exception) {
            NetworkDiag.logError("ProxyTester", e)
            Result(false, "Ошибка проверки: ${e.javaClass.simpleName}", elapsed(start), "UNKNOWN")
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    /**
     * Relay protocol test: performs full HTTPS through relay tunnel.
     *
     * After relay CONNECT handshake, the connection is a raw TCP tunnel to the target.
     * We then do TLS to the target through this tunnel and send an HTTPS request.
     *
     * Stages: RELAY_HANDSHAKE → TARGET_TLS → HTTPS_REQUEST → HTTPS_RESPONSE
     */
    private fun testRelay(
        stream: java.io.InputStream,
        out: java.io.OutputStream,
        gatewaySocket: Socket,
        config: ProxyConfig,
        start: Long
    ): Result {
        val target = ProxyConfig.TEST_URL_HOST
        val port = ProxyConfig.TEST_URL_PORT

        // --- Stage 1: RELAY_HANDSHAKE ---
        val userBytes = config.username.toByteArray(Charsets.UTF_8)
        val passBytes = config.password.toByteArray(Charsets.UTF_8)
        val authFeature = byteArrayOf((userBytes.size).toByte()) + userBytes +
            byteArrayOf((passBytes.size).toByte()) + passBytes

        val hostBytes = target.toByteArray(Charsets.UTF_8)
        val addrFeature = byteArrayOf(0x03, (hostBytes.size).toByte()) + hostBytes +
            byteArrayOf((port shr 8).toByte(), (port and 0xFF).toByte())

        val networkFeature = byteArrayOf(0x00, 0x00)

        fun encodeFeature(type: Byte, data: ByteArray): ByteArray {
            val len = data.size
            return byteArrayOf(type, (len shr 8).toByte(), (len and 0xFF).toByte()) + data
        }

        val features = encodeFeature(0x01, authFeature) +
            encodeFeature(0x02, addrFeature) +
            encodeFeature(0x04, networkFeature)

        val fealen = features.size
        val header = byteArrayOf(0x01, 0x01, (fealen shr 8).toByte(), (fealen and 0xFF).toByte())

        out.write(header + features)
        out.flush()

        // Read relay response
        val respHeader = ByteArray(4)
        var read = 0
        while (read < 4) {
            val n = stream.read(respHeader, read, 4 - read)
            if (n < 0) return Result(false, "Relay: сервер не ответил на handshake", elapsed(start), "RELAY_HANDSHAKE")
            read += n
        }
        val status = respHeader[1].toInt() and 0xFF
        val respFealen = ((respHeader[2].toInt() and 0xFF) shl 8) or (respHeader[3].toInt() and 0xFF)

        // Consume features
        if (respFealen > 0) {
            val featBuf = ByteArray(respFealen)
            read = 0
            while (read < respFealen) {
                val n = stream.read(featBuf, read, respFealen - read)
                if (n < 0) break
                read += n
            }
        }

        if (status != 0x00) {
            val errMsg = when (status) {
                0x01 -> "Bad Request"
                0x02 -> "Unauthorized"
                0x03 -> "Forbidden"
                0x05 -> "Service Unavailable"
                0x06 -> "Host Unreachable"
                else -> "Status $status"
            }
            return Result(false, "Relay handshake: $errMsg", elapsed(start), "RELAY_HANDSHAKE")
        }

        // --- Stage 2: TARGET_TLS ---
        // After relay handshake, gatewaySocket streams are a raw TCP tunnel to the target.
        // Wrap them in TLS for the target (HTTPS).
        val targetTls = try {
            val sf = SSLSocketFactory.getDefault() as SSLSocketFactory
            // gatewaySocket is the TLS socket to gateway; its streams carry relay tunnel data.
            // Wrapping it in TLS creates: TLS(target) over relay tunnel over TLS(gateway) over TCP
            val t = sf.createSocket(gatewaySocket, target, port, true) as SSLSocket
            t.startHandshake()
            t
        } catch (e: Exception) {
            return Result(false, "Target TLS: ${e.message?.take(80)}", elapsed(start), "TARGET_TLS")
        }

        // --- Stage 3: HTTPS_REQUEST ---
        try {
            val req = "GET /generate_204 HTTP/1.1\r\nHost: $target\r\nConnection: close\r\n\r\n"
            targetTls.outputStream.write(req.toByteArray(Charsets.US_ASCII))
            targetTls.outputStream.flush()
        } catch (e: Exception) {
            targetTls.close()
            return Result(false, "HTTPS request: ${e.message?.take(80)}", elapsed(start), "HTTPS_REQUEST")
        }

        // --- Stage 4: HTTPS_RESPONSE ---
        try {
            val respBuf = ByteArray(4096)
            val n = targetTls.inputStream.read(respBuf)
            targetTls.close()
            if (n > 0) {
                val respStr = String(respBuf, 0, n, Charsets.US_ASCII)
                if (respStr.startsWith("HTTP/")) {
                    val code = respStr.split(" ").getOrNull(1)?.toIntOrNull()
                    if (code != null && code in 200..299) {
                        return Result(true, "HTTPS OK (HTTP $code)", elapsed(start), "HTTPS_RESPONSE")
                    } else {
                        return Result(false, "HTTPS: HTTP $code", elapsed(start), "HTTPS_RESPONSE")
                    }
                } else {
                    return Result(false, "HTTPS: некорректный ответ", elapsed(start), "HTTPS_RESPONSE")
                }
            } else {
                return Result(false, "HTTPS: сервер не ответил", elapsed(start), "HTTPS_RESPONSE")
            }
        } catch (e: Exception) {
            targetTls.close()
            return Result(false, "HTTPS response: ${e.message?.take(80)}", elapsed(start), "HTTPS_RESPONSE")
        }
    }

    private fun testHttpConnect(stream: java.io.InputStream, out: java.io.OutputStream, config: ProxyConfig, start: Long, tunnel: Socket): Result {
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
            ?: return Result(false, "Прокси не ответил на CONNECT", elapsed(start), "RELAY_HANDSHAKE")
        val code = statusLine.split(" ").getOrNull(1)?.toIntOrNull()
        when (code) {
            200 -> { /* ok */ }
            407 -> return Result(false, "Неверный логин или пароль (HTTP 407)", elapsed(start), "AUTH")
            405, 502, 503 -> return Result(false, "Прокси отклонил CONNECT (HTTP $code)", elapsed(start), "RELAY_HANDSHAKE")
            null -> return Result(false, "Некорректный ответ прокси", elapsed(start), "RELAY_HANDSHAKE")
            else -> return Result(false, "Прокси отклонил CONNECT (HTTP $code)", elapsed(start), "RELAY_HANDSHAKE")
        }
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
        }

        // TLS to target through tunnel
        val sf = SSLSocketFactory.getDefault() as SSLSocketFactory
        val targetSocket = sf.createSocket(
            tunnel,
            ProxyConfig.TEST_URL_HOST,
            ProxyConfig.TEST_URL_PORT,
            true
        ) as SSLSocket
        targetSocket.startHandshake()

        val req = "GET /generate_204 HTTP/1.1\r\nHost: ${ProxyConfig.TEST_URL_HOST}\r\nConnection: close\r\n\r\n"
        targetSocket.outputStream.write(req.toByteArray(Charsets.US_ASCII))
        targetSocket.outputStream.flush()
        val respReader = BufferedReader(InputStreamReader(targetSocket.inputStream, Charsets.US_ASCII))
        val respStatus = respReader.readLine() ?: ""
        targetSocket.close()

        val respCode = respStatus.split(" ").getOrNull(1)?.toIntOrNull()
        return if (respCode != null && respCode in 200..299) {
            Result(true, "Прокси доступен. Подключение успешно", elapsed(start), "HTTPS_RESPONSE")
        } else {
            Result(false, "Неожиданный ответ целевого сервера", elapsed(start), "HTTPS_RESPONSE")
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
