package com.weathercurrency.app.proxy

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.splitTunnelStore by preferencesDataStore(name = "split_tunnel_settings")

/**
 * Persists split tunneling settings.
 * Two modes:
 * - ALL (default): all apps go through VPN (existing behavior)
 * - SELECTED: only checked apps go through VPN
 */
class SplitTunnelStore(private val context: Context) {

    companion object {
        const val MODE_ALL = "all"
        const val MODE_SELECTED = "selected"
    }

    private object Keys {
        val MODE = stringPreferencesKey("split_mode")
        val SELECTED_PACKAGES = stringSetPreferencesKey("split_selected_packages")
        val SHOW_SYSTEM_APPS = stringPreferencesKey("split_show_system")
    }

    /** Returns "all" or "selected". Default is "all". */
    suspend fun getMode(): String {
        return context.splitTunnelStore.data.first()[Keys.MODE] ?: MODE_ALL
    }

    suspend fun setMode(mode: String) {
        context.splitTunnelStore.edit { it[Keys.MODE] = mode }
    }

    /** Returns set of selected package names for SELECTED mode. */
    suspend fun getSelectedPackages(): Set<String> {
        return context.splitTunnelStore.data.first()[Keys.SELECTED_PACKAGES] ?: emptySet()
    }

    suspend fun setSelectedPackages(packages: Set<String>) {
        context.splitTunnelStore.edit { it[Keys.SELECTED_PACKAGES] = packages }
    }

    suspend fun getShowSystemApps(): Boolean {
        return context.splitTunnelStore.data.first()[Keys.SHOW_SYSTEM_APPS] == "1"
    }

    suspend fun setShowSystemApps(show: Boolean) {
        context.splitTunnelStore.edit { it[Keys.SHOW_SYSTEM_APPS] = if (show) "1" else "0" }
    }

    /** Synchronous read for ProxyVpnService (needs data before establish()). */
    fun getModeSync(): String {
        return context.getSharedPreferences("split_tunnel_prefs", Context.MODE_PRIVATE)
            .getString("mode", MODE_ALL) ?: MODE_ALL
    }

    fun getSelectedPackagesSync(): Set<String> {
        return context.getSharedPreferences("split_tunnel_prefs", Context.MODE_PRIVATE)
            .getStringSet("selected", emptySet()) ?: emptySet()
    }

    /** Mirror to SharedPreferences for sync access in VPN service. */
    suspend fun mirrorToPrefs() {
        val mode = getMode()
        val selected = getSelectedPackages()
        context.getSharedPreferences("split_tunnel_prefs", Context.MODE_PRIVATE).edit()
            .putString("mode", mode)
            .putStringSet("selected", selected)
            .apply()
    }
}
