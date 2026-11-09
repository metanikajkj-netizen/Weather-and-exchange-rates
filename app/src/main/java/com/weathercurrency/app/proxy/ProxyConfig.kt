package com.weathercurrency.app.proxy

/**
 * Immutable proxy configuration.
 * Validation is pure — unit-testable without Android.
 */
data class ProxyConfig(
    /** "HTTP" or "HTTPS" (TLS to proxy endpoint). */
    val type: Type,
    val host: String,
    val port: Int,
    val username: String = "",
    val password: String = ""
) {
    enum class Type { HTTP, HTTPS, RELAY }

    val isConfigured: Boolean
        get() = host.isNotBlank() && port in 1..65535

    /** Builds proxy URL for tun2socks engine: http(s):// or relay://[user:pass@]host:port */
    fun toProxyUrl(): String {
        val scheme = when (type) {
            Type.HTTPS -> "https"
            Type.RELAY -> "relay"
            else -> "http"
        }
        val auth = if (username.isNotBlank()) {
            val u = java.net.URLEncoder.encode(username, "UTF-8")
            val p = java.net.URLEncoder.encode(password, "UTF-8")
            "$u:$p@"
        } else ""
        return "$scheme://$auth$host:$port"
    }

    companion object {
        const val TEST_URL_HOST = "www.gstatic.com"
        const val TEST_URL_PORT = 443

        /**
         * Validates user input. Returns null if OK, otherwise a Russian error message.
         */
        fun validate(type: String?, host: String?, port: String?, username: String?, password: String?): String? {
            val t = type?.trim()?.uppercase()
            if (t != "HTTP" && t != "HTTPS" && t != "RELAY") return "Тип прокси: выберите HTTP, HTTPS или RELAY"
            val h = host?.trim().orEmpty()
            if (h.isEmpty()) return "Укажите IP-адрес или домен прокси"
            if (h.any { it.isWhitespace() }) return "Хост не должен содержать пробелов"
            // Basic host shape: hostname or IPv4
            val hostOk = h.matches(Regex("""^[A-Za-z0-9._-]+$""")) ||
                h.matches(Regex("""^\d{1,3}(\.\d{1,3}){3}$"""))
            if (!hostOk) return "Некорректный адрес прокси"
            val p = port?.trim().orEmpty()
            val portNum = p.toIntOrNull()
                ?: return "Порт должен быть числом от 1 до 65535"
            if (portNum !in 1..65535) return "Порт должен быть от 1 до 65535"
            val user = username.orEmpty()
            val pass = password.orEmpty()
            if (user.isNotBlank() && pass.isEmpty()) return "Укажите пароль для логина"
            if (pass.isNotEmpty() && user.isBlank()) return "Укажите логин для пароля"
            if (user.length > 256 || pass.length > 256) return "Слишком длинные учётные данные"
            return null
        }

        fun parseType(raw: String?): Type = when (raw?.trim()?.uppercase()) {
            "HTTPS" -> Type.HTTPS
            "RELAY" -> Type.RELAY
            else -> Type.HTTP
        }
    }
}
