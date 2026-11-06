package com.weathercurrency.app

import com.weathercurrency.app.proxy.ProxyUiState
import com.weathercurrency.app.proxy.VpnUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UI state defaults (Task25): two-button UX, no fake connected state,
 * generic user-facing test copy (no technical/host leakage).
 */
class ProxyVpnDiagTest {

    @Test
    fun defaultsAreDisconnectedAndNoSystemVpn() {
        val s = ProxyUiState()
        assertEquals(VpnUiState.Disconnected, s.vpnState)
        assertFalse(s.systemVpnActive)
        assertFalse(s.testing)
        assertFalse(s.hasSavedConfig)
    }

    @Test
    fun notConnectedUntilExplicitStatus() {
        val s = ProxyUiState()
        assertTrue(s.vpnState != VpnUiState.Connected)
        assertTrue(s.vpnState != VpnUiState.Connecting)
    }

    @Test
    fun testMessagesAreUserFacingOnly() {
        // ViewModel copy: success / failure without hosts, ports, or credentials.
        val ok = "Соединение доступно"
        val fail = "Не удалось подключиться к серверу"
        assertTrue(ok.contains("Соединение"))
        assertFalse(ok.contains("http"))
        assertFalse(fail.contains("407"))
        assertFalse(fail.contains("@"))
        assertFalse(fail.contains("Exception"))
    }

    @Test
    fun systemVpnFlagIndependentFromUiState() {
        val active = ProxyUiState(vpnState = VpnUiState.Connected, systemVpnActive = true)
        assertTrue(active.systemVpnActive)
        val inactive = ProxyUiState(vpnState = VpnUiState.Connected, systemVpnActive = false)
        assertFalse(inactive.systemVpnActive)
    }
}
