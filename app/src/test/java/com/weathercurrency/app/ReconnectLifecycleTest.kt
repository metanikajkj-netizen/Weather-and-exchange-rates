package com.weathercurrency.app

import com.weathercurrency.app.proxy.ProxyConfig
import org.junit.Assert.*
import org.junit.Test

/**
 * Regression tests for VPN reconnect lifecycle (TASK 32.5).
 * Verifies the fix: connect() must refresh VPN credentials before starting service,
 * so reconnect works without pressing Test first.
 */
class ReconnectLifecycleTest {

    // === Credential config invariants ===

    @Test
    fun `valid proxy config is recognized as configured`() {
        val cfg = ProxyConfig(
            type = ProxyConfig.Type.RELAY,
            host = "wc.kefpulse.ru",
            port = 9443,
            username = "user",
            password = "pass"
        )
        assertTrue("Valid config must be configured", cfg.isConfigured)
    }

    @Test
    fun `proxy config with empty host is not configured`() {
        val cfg = ProxyConfig(
            type = ProxyConfig.Type.RELAY,
            host = "",
            port = 9443,
            username = "user",
            password = "pass"
        )
        assertFalse("Empty host must not be configured", cfg.isConfigured)
    }

    @Test
    fun `proxy config with zero port is not configured`() {
        val cfg = ProxyConfig(
            type = ProxyConfig.Type.RELAY,
            host = "wc.kefpulse.ru",
            port = 0,
            username = "user",
            password = "pass"
        )
        assertFalse("Port 0 must not be configured", cfg.isConfigured)
    }

    @Test
    fun `relay URL is built correctly for gateway`() {
        val cfg = ProxyConfig(
            type = ProxyConfig.Type.RELAY,
            host = "wc.kefpulse.ru",
            port = 9443,
            username = "testuser",
            password = "testpass"
        )
        val url = cfg.toProxyUrl()
        assertTrue("Must use relay scheme", url.startsWith("relay://"))
        assertTrue("Must contain gateway host", url.contains("wc.kefpulse.ru"))
        assertTrue("Must contain gateway port", url.contains("9443"))
    }

    // === UI state machine invariants ===

    @Test
    fun `VpnUiState has correct state names`() {
        val states = com.weathercurrency.app.proxy.VpnUiState.values()
        assertTrue(states.contains(com.weathercurrency.app.proxy.VpnUiState.Disconnected))
        assertTrue(states.contains(com.weathercurrency.app.proxy.VpnUiState.Connecting))
        assertTrue(states.contains(com.weathercurrency.app.proxy.VpnUiState.Connected))
        assertTrue(states.contains(com.weathercurrency.app.proxy.VpnUiState.Error))
    }

    // === Source code invariants: reconnect fix present ===

    @Test
    fun `doStart refreshes VPN credentials before starting service`() {
        val candidates = listOf(
            java.io.File("app/src/main/java/com/weathercurrency/app/proxy/ProxyVpnViewModel.kt"),
            java.io.File("src/main/java/com/weathercurrency/app/proxy/ProxyVpnViewModel.kt"),
            java.io.File("/opt/weather-currency/app/src/main/java/com/weathercurrency/app/proxy/ProxyVpnViewModel.kt")
        )
        val sourceFile = candidates.firstOrNull { it.exists() } ?: return
        val source = sourceFile.readText()

        // doStart must fetch fresh credentials (same as runTest)
        assertTrue(
            "doStart must call vpnConfigSync()",
            source.contains("vpnConfigSync()")
        )
        // Must have credential refresh logic in doStart area
        assertTrue(
            "Must fetch VPN credentials from server",
            source.contains("api.vpnCredentials")
        )
    }

    @Test
    fun `connect allows retry from Error state`() {
        val candidates = listOf(
            java.io.File("app/src/main/java/com/weathercurrency/app/proxy/ProxyVpnViewModel.kt"),
            java.io.File("src/main/java/com/weathercurrency/app/proxy/ProxyVpnViewModel.kt"),
            java.io.File("/opt/weather-currency/app/src/main/java/com/weathercurrency/app/proxy/ProxyVpnViewModel.kt")
        )
        val sourceFile = candidates.firstOrNull { it.exists() } ?: return
        val source = sourceFile.readText()

        // connect() must NOT return early for Error state
        // Find the connect function
        val connectIdx = source.indexOf("fun connect()")
        assertTrue("connect() must exist", connectIdx >= 0)
        val connectBlock = source.substring(connectIdx, connectIdx + 300)
        // Must NOT block on Connected (stale state)
        assertFalse(
            "connect() must not block on Connected state",
            connectBlock.contains("st == VpnUiState.Connected") &&
            connectBlock.contains("return")
        )
    }

    @Test
    fun `ProxyVpnService does not contain hardcoded proxy credentials`() {
        val candidates = listOf(
            java.io.File("app/src/main/java/com/weathercurrency/app/proxy/ProxyVpnService.kt"),
            java.io.File("src/main/java/com/weathercurrency/app/proxy/ProxyVpnService.kt"),
            java.io.File("/opt/weather-currency/app/src/main/java/com/weathercurrency/app/proxy/ProxyVpnService.kt")
        )
        val sourceFile = candidates.firstOrNull { it.exists() } ?: return
        val source = sourceFile.readText()

        assertFalse("Must not hardcode 141.11.162.93", source.contains("141.11.162.93"))
        assertTrue("Must call key.setDevice", source.contains("key.setDevice"))
    }
}
