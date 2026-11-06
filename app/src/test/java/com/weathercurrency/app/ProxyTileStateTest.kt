package com.weathercurrency.app

import com.weathercurrency.app.proxy.ProxyTileUi
import com.weathercurrency.app.proxy.VpnStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Quick Settings tile state mapping (TASK27) — no Android framework. */
class ProxyTileStateTest {

    @Test
    fun disconnectedIsInactive() {
        val v = ProxyTileUi.map(VpnStatus.DISCONNECTED, systemVpn = false)
        assertFalse(v.active)
        assertFalse(v.busy)
        assertTrue(v.label.contains("Proxy VPN"))
    }

    @Test
    fun connectingIsBusy() {
        val v = ProxyTileUi.map(VpnStatus.CONNECTING, systemVpn = false)
        assertTrue(v.busy)
        assertFalse(v.active)
        assertEquals("Подключение…", v.label)
    }

    @Test
    fun connectedIsActive() {
        val v = ProxyTileUi.map(VpnStatus.CONNECTED, systemVpn = true)
        assertTrue(v.active)
        assertFalse(v.busy)
        assertTrue(v.label.contains("VPN"))
    }

    @Test
    fun errorIsInactiveWithRetryHint() {
        val v = ProxyTileUi.map(VpnStatus.ERROR, systemVpn = false)
        assertFalse(v.active)
        assertFalse(v.busy)
        assertTrue(v.label.contains("Ошибка"))
    }

    @Test
    fun unknownStatusFallsBackToInactive() {
        val v = ProxyTileUi.map("", systemVpn = true)
        assertFalse(v.active)
        assertFalse(v.busy)
    }
}
