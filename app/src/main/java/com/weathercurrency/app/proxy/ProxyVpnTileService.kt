package com.weathercurrency.app.proxy

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.app.PendingIntent

/**
 * Quick Settings tile: one tap toggles the existing Proxy VPN
 * (same engine + [ProxyEmbedded] config — no second tunnel).
 *
 * States: inactive / active / unavailable (connecting) / label for error.
 * Consent (VpnService.prepare) opens the system dialog only — not the app UI.
 */
class ProxyVpnTileService : TileService() {

    private var statusReceiver: BroadcastReceiver? = null

    override fun onStartListening() {
        super.onStartListening()
        registerStatusReceiver()
        refreshTile()
    }

    override fun onStopListening() {
        unregisterStatusReceiver()
        super.onStopListening()
    }

    override fun onClick() {
        if (isSecure && isLocked) {
            unlockAndRun { handleClick() }
        } else {
            handleClick()
        }
    }

    private fun handleClick() {
        val status = ProxyVpnService.lastStatus
        when (status) {
            ProxyVpnService.STATUS_CONNECTING -> {
                refreshTile()
            }
            ProxyVpnService.STATUS_CONNECTED -> {
                ProxyVpnService.stop(this)
                ProxyVpnService.lastStatus = ProxyVpnService.STATUS_CONNECTING
                refreshTile()
            }
            else -> connect()
        }
        refreshTile()
    }

    private fun connect() {
        val consent = try {
            VpnService.prepare(this)
        } catch (_: Exception) {
            null
        }
        if (consent != null) {
            // System VPN permission dialog only (HyperOS / Android 15 compatible).
            launchConsent(consent)
            return
        }
        ProxyVpnService.lastStatus = ProxyVpnService.STATUS_CONNECTING
        ProxyVpnService.start(this)
    }

    private fun launchConsent(intent: Intent) {
        try {
            val pi = PendingIntent.getActivity(
                this,
                2101,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(pi)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        } catch (_: Exception) {
            // Fallback: open app on Proxy VPN screen (consent handled in Activity).
            val app = Intent(this, com.weathercurrency.app.MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(com.weathercurrency.app.MainActivity.EXTRA_OPEN_PROXY_VPN, true)
            }
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this,
                    2102,
                    app,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
        }
    }

    private fun registerStatusReceiver() {
        if (statusReceiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                refreshTile()
            }
        }
        statusReceiver = r
        val f = IntentFilter(ProxyVpnService.ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(r, f, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(r, f)
        }
    }

    private fun unregisterStatusReceiver() {
        statusReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: Exception) {
            }
        }
        statusReceiver = null
    }

    private fun systemVpnActive(): Boolean {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        } catch (_: Exception) {
            false
        }
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        val visual = ProxyTileUi.map(
            status = ProxyVpnService.lastStatus,
            systemVpn = systemVpnActive(),
        )
        tile.label = visual.label
        tile.state = when {
            visual.busy -> Tile.STATE_UNAVAILABLE
            visual.active -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = visual.subtitle
        }
        tile.updateTile()
    }

    companion object {
        /** Notify system that the tile should refresh (call after VPN status changes). */
        fun requestUpdate(context: Context) {
            try {
                val cn = ComponentName(context, ProxyVpnTileService::class.java)
                requestListeningState(context, cn)
            } catch (_: Exception) {
            }
        }
    }
}

/**
 * Pure tile label/state mapping (unit-testable, no Android dependencies).
 */
object ProxyTileUi {
    data class Visual(
        val active: Boolean,
        val busy: Boolean,
        val label: String,
        val subtitle: String,
    )

    fun map(status: String, systemVpn: Boolean): Visual = when (status) {
        VpnStatus.CONNECTING -> Visual(
            active = false,
            busy = true,
            label = "Подключение…",
            subtitle = "Proxy VPN",
        )
        VpnStatus.CONNECTED -> Visual(
            active = true,
            busy = false,
            label = "VPN включён",
            subtitle = "Подключено",
        )
        VpnStatus.ERROR -> Visual(
            active = false,
            busy = false,
            label = "Ошибка VPN",
            subtitle = "Нажмите для повтора",
        )
        else -> Visual(
            active = false,
            busy = false,
            label = "Proxy VPN",
            subtitle = if (systemVpn) "VPN активен" else "Выключен",
        )
    }
}
