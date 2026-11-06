package com.weathercurrency.app.proxy

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.proxyStore by preferencesDataStore(name = "proxy_vpn_settings")

/**
 * Persists proxy settings. Password is stored only as Keystore-encrypted ciphertext.
 * Never log password or decrypted values.
 */
class ProxySettingsStore(private val context: Context) {

    private object Keys {
        val TYPE = stringPreferencesKey("proxy_type")
        val HOST = stringPreferencesKey("proxy_host")
        val PORT = stringPreferencesKey("proxy_port")
        val USER = stringPreferencesKey("proxy_user")
        val PASS_ENC = stringPreferencesKey("proxy_pass_enc")
        val HAS_CONFIG = stringPreferencesKey("proxy_has_config")
    }

    suspend fun save(config: ProxyConfig) {
        context.proxyStore.edit { p ->
            p[Keys.TYPE] = config.type.name
            p[Keys.HOST] = config.host
            p[Keys.PORT] = config.port.toString()
            p[Keys.USER] = config.username
            p[Keys.PASS_ENC] = ProxyCrypto.encrypt(config.password)
            p[Keys.HAS_CONFIG] = "1"
        }
        // Mirror for ProxyVpnService (needs sync read before establish()).
        context.getSharedPreferences("proxy_vpn_prefs", Context.MODE_PRIVATE).edit()
            .putString("type", config.type.name)
            .putString("host", config.host)
            .putInt("port", config.port)
            .putString("user", config.username)
            .putString("pass_enc", ProxyCrypto.encrypt(config.password))
            .apply()
    }

    suspend fun load(): ProxyConfig? {
        val p = context.proxyStore.data.first()
        if (p[Keys.HAS_CONFIG] != "1") return null
        val host = p[Keys.HOST] ?: return null
        val port = p[Keys.PORT]?.toIntOrNull() ?: return null
        val type = if (p[Keys.TYPE] == "HTTPS") ProxyConfig.Type.HTTPS else ProxyConfig.Type.HTTP
        return ProxyConfig(
            type = type,
            host = host,
            port = port,
            username = p[Keys.USER] ?: "",
            password = ProxyCrypto.decrypt(p[Keys.PASS_ENC] ?: "")
        )
    }

    suspend fun clear() {
        context.proxyStore.edit { it.clear() }
    }

    suspend fun hasConfig(): Boolean {
        val p = context.proxyStore.data.first()
        return p[Keys.HAS_CONFIG] == "1"
    }
}
