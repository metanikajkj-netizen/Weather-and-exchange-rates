package com.weathercurrency.app.data

/**
 * Typed API failures so the UI can show a precise reason instead of a
 * single generic «Ошибка сети» message. Technical details go to Logcat.
 */
sealed class ApiError(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause) {

    /** No connectivity / DNS / connect / timeout / TLS transport failure. */
    class Network(message: String, cause: Throwable? = null) : ApiError(message, cause)

    /** Server returned a non-2xx HTTP status. */
    class Http(val code: Int, message: String = "HTTP $code") : ApiError(message)

    /** Payload received but could not be parsed / required fields missing. */
    class Parse(message: String, cause: Throwable? = null) : ApiError(message, cause)

    /** Empty body or missing structure. */
    class Empty(message: String) : ApiError(message)

    companion object {
        fun fromIOException(e: IOExceptionLike, prefix: String): ApiError {
            val msg = e.message ?: e.javaClass.simpleName
            val lower = msg.lowercase()
            return when {
                e is java.net.SocketTimeoutException ||
                    lower.contains("timeout") || lower.contains("timed out") ->
                    Network("$prefix: истёк таймаут подключения", e)

                e is java.net.UnknownHostException ||
                    lower.contains("unable to resolve host") ||
                    lower.contains("unknownhost") ->
                    Network("$prefix: сервер не найден (DNS)", e)

                e is javax.net.ssl.SSLException ||
                    lower.contains("ssl") || lower.contains("certificate") ->
                    Network("$prefix: ошибка TLS/SSL", e)

                e is java.net.ConnectException ||
                    lower.contains("failed to connect") ||
                    lower.contains("connection refused") ||
                    lower.contains("connection reset") ||
                    lower.contains("econnreset") ||
                    lower.contains("enetworkunreachable") ||
                    lower.contains("network is unreachable") ->
                    Network("$prefix: сервер недоступен", e)

                else -> Network("$prefix: ошибка сети (${msg.take(120)})", e)
            }
        }
    }
}

/** Marker so companion can accept any IOException without importing cycles. */
typealias IOExceptionLike = java.io.IOException
