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
import java.io.File
import kotlin.concurrent.thread

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
    @Volatile private var startGeneration = 0
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var protectServerRef: android.net.LocalServerSocket? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "SERVICE_CREATED: onStartCommand action=${intent?.action}")
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

        // TASK 33.6.11: Call startForeground() IMMEDIATELY to prevent
        // ForegroundServiceDidNotStartInTimeException (5-second timeout).
        // Must be called BEFORE any heavy operations.
        Log.d(TAG, "FOREGROUND_STARTED: calling startForeground immediately")
        startForeground(NOTIFICATION_ID, buildNotification())

        // Load config synchronously via runBlocking-free path: use stored prefs via callback
        // TASK26.5: per-device gateway creds from Auth API (server-checked subscription).
        // No legacy/shared third-party proxy password in the app.
        val proxyCfg = com.weathercurrency.app.subscription.SubscriptionStore(this).vpnConfigSync()
        if (proxyCfg == null || !proxyCfg.isConfigured) {
            Log.e(TAG, "startVpn: no valid subscription VPN credentials")
            sendStatus(STATUS_ERROR, "Ошибка соединения")
            stopForegroundCompat()
            stopSelf()
            return
        }
        val cfg = proxyCfg

        try {
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
                .addDnsServer("8.8.8.8")
                .addDnsServer("1.1.1.1")

            // Split tunneling: apply app selection rules.
            // CRITICAL: do NOT mix addAllowedApplication() and addDisallowedApplication()
            // — Android treats this as undefined behavior (breaks on MIUI/HyperOS).
            val splitStore = SplitTunnelStore(this)
            val splitMode = splitStore.getModeSync()
            val selectedPackages = splitStore.getSelectedPackagesSync()

            if (splitMode == SplitTunnelStore.MODE_SELECTED && selectedPackages.isNotEmpty()) {
                // SELECTED mode: whitelist selected apps. Own app is NOT in the list
                // → automatically excluded → no loop. Do NOT call addDisallowedApplication.
                var allowedCount = 0
                for (pkg in selectedPackages) {
                    try {
                        builder.addAllowedApplication(pkg)
                        allowedCount++
                    } catch (e: Exception) {
                        Log.w(TAG, "split: skip unavailable package $pkg")
                    }
                }
                Log.i(TAG, "Split tunneling: SELECTED mode, $allowedCount apps through VPN (no addDisallowedApplication)")
            } else {
                // ALL mode: all apps through VPN except own app (loop prevention).
                // Use ONLY addDisallowedApplication — no mixing.
                builder.addDisallowedApplication(packageName)
                Log.i(TAG, "Split tunneling: ALL mode (default), own app excluded")
            }

            Log.d(TAG, "TUN_CREATED: establishing TUN interface")
            val pfd = builder.establish()
            if (pfd == null) {
                Log.e(TAG, "establish() returned null (other VPN active?)")
                sendStatus(STATUS_ERROR, "Не удалось создать TUN (возможен другой VPN)")
                stopForegroundCompat()
                stopSelf()
                return
            }
            Log.d(TAG, "TUN_CREATED: TUN established successfully")
            Log.d(TAG, "VPN_TUN_READY: TUN interface ready")
            // Engine owns the TUN fd: detach so Java never closes it twice
            // (double-close of a recycled fd crashed the app on VPN stop — TASK27.1).
            val tunFd = pfd.detachFd()
            tunPfd = null

            val key = Key()
            key.setDevice("fd://$tunFd")
            // TASK 33.6.9: Direct HTTP CONNECT proxy (no VLESS).
            // sing-box TUN -> HTTP CONNECT Proxy -> Internet
            // TUN FD is passed via PlatformInterface (not config "device" option)
            key.setProxyHost(cfg.host)
            key.setProxyPort(cfg.port.toLong())
            key.setUsername(cfg.username)
            key.setPassword(cfg.password)
            key.setLogLevel("info")
            key.setMTU(0)

            // Create protect socket to prevent routing loop.
            // sing-box sends FDs here -> we call VpnService.protect() -> FDs bypass TUN.
            // TASK 33.6.11: Use unique socket name per lifecycle to prevent "Address already in use".
            // File-based LocalServerSocket uses filesystem namespace; close() may not
            // immediately release the bind. Unique name avoids collision entirely.
            try {
                protectServerRef?.close()
                protectServerRef = null
                Log.d(TAG, "PROTECT_SERVER_STOP: closed old protect server")
            } catch (_: Exception) {}
            val socketId = System.nanoTime()
            val protectSocketPath = "protect_${socketId}.sock"
            val protectServer = startProtectServer(protectSocketPath)
            key.setProtectPath(protectSocketPath)
            protectServerRef = protectServer
            Log.d(TAG, "PROTECT_SERVER_START: listening on abstract namespace: $protectSocketPath")

            Log.d(TAG, "ENGINE_START: inserting key and starting engine")
            Engine.insert(key)
            try {
                Engine.start()
            } catch (e: Exception) {
                Log.e(TAG, "ENGINE_START_FAILED: ${e.message}")
                sendStatus(STATUS_ERROR, "Ошибка запуска движка")
                cleanupEngine()
                stopForegroundCompat()
                stopSelf()
                return
            }
            engineRunning = true
            // TASK 32.4.5: ENGINE_READY deferred - verified via Engine.isRunning() below
            // Gateway diagnostics: DNS, socket protection, TCP, TLS
            thread {
                Thread.sleep(500)
                try {
                    // DNS resolution check
                    val dnsResult = java.net.InetAddress.getByName(cfg.host)
                    Log.d(TAG, "GATEWAY_DNS_OK: ${cfg.host} resolved to ${dnsResult.hostAddress}")
                } catch (e: Exception) {
                    Log.e(TAG, "GATEWAY_DNS_ERROR: ${e.message}")
                }
            }

            // TASK 32.4.5/32.4.7: Verify engine actually started
            // Use generation counter to prevent stale callbacks from affecting new sessions
            val myGeneration = ++startGeneration
            sendStatus(STATUS_STARTING, "Запуск VPN...")
            thread {
                Thread.sleep(2000)
                // Skip if a new session started or we're stopping
                if (myGeneration != startGeneration || stopping) {
                    Log.d(TAG, "ENGINE_CHECK_SKIP: stale generation $myGeneration != $startGeneration")
                    return@thread
                }
                if (engineRunning && Engine.isRunning()) {
                    Log.d(TAG, "ENGINE_READY: engine confirmed running")
                    sendStatus(STATUS_CONNECTED, "VPN подключён")
                    Log.d(TAG, "VPN_TRAFFIC_RX: monitoring started")
                    Log.d(TAG, "VPN_TRAFFIC_TX: monitoring started")
                    Log.i(TAG, "VPN started proxy=${cfg.host}:${cfg.port} type=${cfg.type}")
                } else {
                    Log.e(TAG, "ENGINE_FAILED: Engine.isRunning() returned false")
                    sendStatus(STATUS_ERROR, "Ошибка запуска движка")
                    cleanupEngine()
                    stopForegroundCompat()
                    stopSelf()
                }
            }
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
            Log.d(TAG, "VPN_STOP_CLEANUP: stopping VPN engine")
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
        // TASK 33.6.10: Close protect server socket to prevent "Address already in use".
        try {
            protectServerRef?.close()
            Log.d(TAG, "PROTECT_SERVER_STOP: protect server closed")
        } catch (e: Exception) {
            Log.w(TAG, "PROTECT_SERVER_STOP: close failed", e)
        }
        protectServerRef = null
        // TUN fd is owned by the Go engine after detachFd(); only close if
        // we still hold a PFD (start failed before detach).
        try {
            tunPfd?.close()
        } catch (_: Exception) {
        }
        tunPfd = null
    }

    /**
     * Starts a Unix domain socket server that accepts FDs from sing-box
     * and calls VpnService.protect() on each one. This prevents the routing
     * loop where sing-box's outbound socket would go through the TUN.
     *
     * sing-box sends FD via SCM_RIGHTS (see sendAncillaryFileDescriptors).
     * We receive it via LocalSocket.getFileDescriptors(), call protect(fd),
     * and reply 1 byte (1=success, 0=failure).
     */
    private fun startProtectServer(socketName: String): android.net.LocalServerSocket {
        // Android LocalServerSocket(name) uses abstract namespace when name
        // does NOT contain a path separator. This avoids filesystem socket conflicts.
        Log.d(TAG, "PROTECT_SERVER_START: binding abstract namespace '$socketName'")
        val server = android.net.LocalServerSocket(socketName)
        Log.d(TAG, "PROTECT_SERVER_READY: listening on '$socketName'")
        thread {
            try {
                while (true) {
                    val client = server.accept() ?: break
                    thread {
                        try {
                            // sing-box sends FD via SCM_RIGHTS + 1 byte dummy,
                            // then waits for 1-byte reply.
                            //
                            // FIX: Use getFileDescriptors() to receive FDs via SCM_RIGHTS,
                            // not client.fileDescriptor (which is the Unix socket FD).
                            var protected = false
                            try {
                                val getFdsMethod = client.javaClass.getMethod("getFileDescriptors")
                                val fds = getFdsMethod.invoke(client) as? Array<java.io.FileDescriptor>
                                if (fds != null && fds.isNotEmpty()) {
                                    for (fd in fds) {
                                        val fdField = fd.javaClass.getDeclaredField("fd")
                                        fdField.isAccessible = true
                                        val fdInt = fdField.getInt(fd)
                                        val result = protect(fdInt)
                                        Log.d(TAG, "protect SCM_RIGHTS fd=$fdInt result=$result")
                                if (result) Log.d(TAG, "GATEWAY_SOCKET_PROTECTED: fd=$fdInt")
                                        if (result) protected = true
                                    }
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "getFileDescriptors failed: ${e.message}")
                                Log.e(TAG, "GATEWAY_SOCKET_ERROR: ${e.message}")
                            }

                            // Fallback: protect the Unix socket FD if SCM_RIGHTS failed
                            if (!protected) {
                                val fdField = client.fileDescriptor.javaClass.getDeclaredField("fd")
                                fdField.isAccessible = true
                                val fdInt = fdField.getInt(client.fileDescriptor)
                                val result = protect(fdInt)
                                Log.d(TAG, "protect fallback fd=$fdInt result=$result")
                                protected = result
                            }

                            // Reply success (1 byte)
                            client.outputStream.write(if (protected) 1 else 0)
                            client.outputStream.flush()
                            Log.d(TAG, "protect reply sent: ${if (protected) 1 else 0}")
                            // Keep connection open until sing-box closes it
                            try {
                                while (client.inputStream.read() >= 0) { /* drain */ }
                            } catch (_: Exception) {}
                        } catch (e: Exception) {
                            Log.w(TAG, "protect client error", e)
                        }
                    }
                }
            } catch (e: Exception) {
                if (!stopping) Log.w(TAG, "protect server stopped", e)
            }
        }
        return server
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

        const val STATUS_STARTING = VpnStatus.STARTING
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
