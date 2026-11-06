package com.weathercurrency.app.proxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.weathercurrency.app.MainActivity
import engine.Engine
import engine.Key
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Real VPN service: establishes TUN and runs tun2socks engine in-process
 * with fd:// device + http(s):// proxy outbound.
 *
 * Loop prevention: addDisallowedApplication(own package) — our app's sockets
 * (including engine outbound) bypass the VPN, so CONNECT cannot loop back
 * into the TUN. Other apps' traffic is tunneled.
 *
 * UDP/QUIC: HTTP CONNECT proxy does not support UDP (engine returns
 * ErrUnsupported) — UDP is dropped (fail-closed). Documented limitation.
 */
class ProxyVpnService : VpnService() {

    private var tunPfd: ParcelFileDescriptor? = null
    private var engineRunning = false
    @Volatile private var stopping = false
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopVpn()
                return START_NOT_STICKY
            }
            else -> startVpn()
        }
        return START_STICKY
    }

    private fun startVpn() {
        if (engineRunning) return

        val config = serviceScope.let { /* loaded below */ null }
        // Load config synchronously via runBlocking-free path: use stored prefs via callback
        val prefs = getSharedPreferences("proxy_vpn_prefs", Context.MODE_PRIVATE)
        // TASK25: always use embedded proxy — no user configuration required.
        // Fall back to legacy SharedPreferences only if embedded is somehow empty.
        val proxyCfg = if (ProxyEmbedded.config.isConfigured) {
            ProxyEmbedded.config
        } else {
            val type = prefs.getString("type", "HTTP") ?: "HTTP"
            val host = prefs.getString("host", "") ?: ""
            val port = prefs.getInt("port", 0)
            val user = prefs.getString("user", "") ?: ""
            val passEnc = prefs.getString("pass_enc", "") ?: ""
            val pass = try { ProxyCrypto.decrypt(passEnc) } catch (_: Exception) { "" }
            ProxyConfig(
                type = if (type == "HTTPS") ProxyConfig.Type.HTTPS else ProxyConfig.Type.HTTP,
                host = host,
                port = port,
                username = user,
                password = pass
            )
        }

        if (!proxyCfg.isConfigured) {
            Log.e(TAG, "startVpn: proxy not configured")
            stopSelf()
            return
        }

        try {
            // Exclude own app from VPN → engine sockets never re-enter TUN (no loop).
            // IPv4 + IPv6 routes: TCP (incl. IPv6) goes through HTTP CONNECT;
            // UDP/53 is answered in-engine via DoH through CONNECT; other UDP is dropped
            // fail-closed (no direct IPv6 bypass — ::/0 stays claimed by the TUN).
            val builder = Builder()
                .setSession("WeatherCurrency Proxy")
                .setMtu(1500)
                .addAddress("172.19.0.1", 32)
                .addRoute("0.0.0.0", 0)
                .addAddress("fdfe:dcba:9876::1", 126)
                .addRoute("::", 0)
                // DNS servers are intercepted in the engine (UDP/53 → DoH via CONNECT).
                // Both v4/v6 targets are fine: queries never leave the proxy path.
                .addDnsServer("8.8.8.8")
                .addDnsServer("1.1.1.1")
                .addDisallowedApplication(packageName)

            val pfd = builder.establish()
            if (pfd == null) {
                Log.e(TAG, "establish() returned null (other VPN active?)")
                sendStatus(STATUS_ERROR, "Не удалось создать TUN (возможен другой VPN)")
                stopSelf()
                return
            }
            // Engine owns the TUN fd: detach so Java never closes it twice
            // (double-close of a recycled fd crashed the app on VPN stop — TASK27.1).
            val tunFd = pfd.detachFd()
            tunPfd = null

            val key = Key()
            key.setDevice("fd://$tunFd")
            key.setProxy(proxyCfg.toProxyUrl())
            key.setLogLevel("info") // DNS diagnostics (DoH/SERVFAIL) must not be hidden
            key.setMark(0)
            key.setMTU(0)
            key.setInterface("")
            key.setRestAPI("")
            key.setTCPSendBufferSize("")
            key.setTCPReceiveBufferSize("")
            key.setTCPModerateReceiveBuffer(false)
            key.setTUNPreUp("")
            key.setTUNPostUp("")

            Engine.insert(key)
            Engine.start()
            engineRunning = true

            startForeground(NOTIFICATION_ID, buildNotification())
            sendStatus(STATUS_CONNECTED, "VPN подключён")
            Log.i(TAG, "VPN started host=${proxyCfg.host}:${proxyCfg.port} type=${proxyCfg.type}")
        } catch (e: Exception) {
            Log.e(TAG, "startVpn failed", e)
            sendStatus(STATUS_ERROR, "Ошибка запуска VPN: ${e.message}")
            cleanupEngine()
            stopForegroundCompat()
            stopSelf()
        }
    }

    private fun stopVpn() {
        // Idempotent: tile + app may both send ACTION_STOP (TASK27.1).
        if (!stopping && (engineRunning || tunPfd != null)) {
            stopping = true
            try {
                cleanupEngine()
                stopForegroundCompat()
                sendStatus(STATUS_DISCONNECTED, "VPN отключён")
            } catch (e: Exception) {
                Log.e(TAG, "stopVpn", e)
                try {
                    sendStatus(STATUS_DISCONNECTED, "VPN отключён")
                } catch (_: Exception) {
                }
            } finally {
                stopping = false
            }
        } else if (!engineRunning && tunPfd == null) {
            // Already stopped — still report DISCONNECTED so UI/tile unstick.
            try {
                sendStatus(STATUS_DISCONNECTED, "VPN отключён")
            } catch (_: Exception) {
            }
        }
        stopSelf()
    }

    private fun cleanupEngine() {
        if (engineRunning) {
            try {
                Engine.stop()
            } catch (e: Exception) {
                Log.e(TAG, "Engine.stop", e)
            }
            engineRunning = false
        }
        // TUN fd is owned by the Go engine after detachFd(); only close if
        // we still hold a PFD (start failed before detach).
        try {
            tunPfd?.close()
        } catch (_: Exception) {
        }
        tunPfd = null
    }

    override fun onDestroy() {
        try {
            cleanupEngine()
        } catch (_: Exception) {
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onRevoke() {
        // System revoked VPN (another VPN or user action)
        if (!stopping) {
            stopping = true
            try {
                cleanupEngine()
                stopForegroundCompat()
                sendStatus(STATUS_ERROR, "VPN отозван системой")
            } catch (e: Exception) {
                Log.e(TAG, "onRevoke", e)
            } finally {
                stopping = false
            }
        }
        stopSelf()
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun buildNotification(): Notification {
        ensureChannel()
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_OPEN_PROXY_VPN, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            this, 2001, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_low_battery)
            .setContentTitle("Proxy VPN активен")
            .setContentText("Трафик идёт через настроенный прокси")
            .setContentIntent(pi)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Proxy VPN",
                    NotificationManager.IMPORTANCE_LOW
                ).apply { description = "Уведомление о работающем VPN" }
            )
        }
    }

    private fun sendStatus(status: String, message: String) {
        lastStatus = status
        val i = Intent(ACTION_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_STATUS, status)
            putExtra(EXTRA_MESSAGE, message)
        }
        sendBroadcast(i)
        // Keep Quick Settings tile in sync with the real service state.
        ProxyVpnTileService.requestUpdate(this)
    }

    companion object {
        private const val TAG = "ProxyVpnService"
        const val ACTION_STOP = "com.weathercurrency.app.proxy.STOP"
        const val ACTION_STATUS = "com.weathercurrency.app.proxy.STATUS"
        const val EXTRA_STATUS = "status"
        const val EXTRA_MESSAGE = "message"
        const val CHANNEL_ID = "proxy_vpn"
        const val NOTIFICATION_ID = 4220

        const val STATUS_CONNECTING = VpnStatus.CONNECTING
        const val STATUS_CONNECTED = VpnStatus.CONNECTED
        const val STATUS_DISCONNECTED = VpnStatus.DISCONNECTED
        const val STATUS_ERROR = VpnStatus.ERROR

        /** Last known VPN status for the Quick Settings tile / UI. */
        @Volatile
        var lastStatus: String = STATUS_DISCONNECTED

        fun start(context: Context) {
            lastStatus = STATUS_CONNECTING
            val i = Intent(context, ProxyVpnService::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun stop(context: Context) {
            val i = Intent(context, ProxyVpnService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(i)
        }
    }
}
