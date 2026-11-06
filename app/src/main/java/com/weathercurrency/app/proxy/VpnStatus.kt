package com.weathercurrency.app.proxy

/**
 * VPN status strings shared by Service, UI and Quick Settings tile.
 * No Android imports — safe for unit tests.
 */
object VpnStatus {
    const val CONNECTING = "connecting"
    const val CONNECTED = "connected"
    const val DISCONNECTED = "disconnected"
    const val ERROR = "error"
}
