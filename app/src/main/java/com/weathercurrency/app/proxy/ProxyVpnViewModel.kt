package com.weathercurrency.app.proxy

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.weathercurrency.app.data.NetworkDiag
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class VpnUiState { Disconnected, Connecting, Connected, Error }

data class ProxyUiState(
    val vpnState: VpnUiState = VpnUiState.Disconnected,
    val vpnMessage: String = "",
    val hasSavedConfig: Boolean = false,
    val config: ProxyConfig? = null,
    val testing: Boolean = false,
    val testMessage: String? = null,
    val testSuccess: Boolean? = null,
    val testLatencyMs: Long? = null,
    val permissionNeeded: Boolean = false,
    /** System-level VPN presence (ConnectivityManager.TRANSPORT_VPN). */
    val systemVpnActive: Boolean = false
)

/**
 * Proxy VPN view-model for the two-button UI (TASK25).
 * Configuration always comes from [ProxyEmbedded] — no user-editable fields.
 * Never copies password into logs or user-facing messages.
 */
class ProxyVpnViewModel(application: Application) : AndroidViewModel(application) {

    private val app: Application get() = getApplication()
    private val tester = ProxyTester(app)

    private val _ui = MutableStateFlow(ProxyUiState())
    val ui: StateFlow<ProxyUiState> = _ui.asStateFlow()

    private var statusReceiver: BroadcastReceiver? = null

    init {
        val cfg = ProxyEmbedded.config
        _ui.update {
            it.copy(
                config = cfg,
                hasSavedConfig = cfg.isConfigured,
                vpnState = VpnUiState.Disconnected,
                vpnMessage = "",
            )
        }
        refreshSystemVpnStatus()
        registerStatusReceiver()
    }

    /** Polls whether the OS reports an active VPN transport (any app). */
    fun refreshSystemVpnStatus() {
        val active = try {
            val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        } catch (_: Exception) {
            false
        }
        _ui.update { it.copy(systemVpnActive = active) }
    }

    /** Runs proxy reachability check using the embedded config. Does not start VPN. */
    fun runTest() {
        val cfg = _ui.value.config
        if (cfg == null || !cfg.isConfigured) {
            _ui.update {
                it.copy(testSuccess = false, testMessage = "Не удалось подключиться к серверу")
            }
            return
        }
        if (_ui.value.testing) return
        if (_ui.value.vpnState == VpnUiState.Connecting) return
        _ui.update {
            it.copy(testing = true, testMessage = null, testSuccess = null, testLatencyMs = null)
        }
        viewModelScope.launch {
            val result = tester.test(cfg)
            // User-facing copy only — no technical errors, hosts, or credentials.
            _ui.update {
                it.copy(
                    testing = false,
                    testSuccess = result.success,
                    testMessage = if (result.success) {
                        "Соединение доступно"
                    } else {
                        "Не удалось подключиться к серверу"
                    },
                    testLatencyMs = if (result.success) result.latencyMs else null,
                )
            }
            refreshSystemVpnStatus()
        }
    }

    /**
     * Starts VPN with embedded config. VPN consent (prepare) is launched
     * by the Activity before this call when needed.
     */
    fun connect() {
        val st = _ui.value.vpnState
        if (st == VpnUiState.Connecting || st == VpnUiState.Connected) return
        val cfg = _ui.value.config
        if (cfg == null || !cfg.isConfigured) {
            _ui.update {
                it.copy(vpnState = VpnUiState.Error, vpnMessage = "Ошибка соединения")
            }
            return
        }
        doStart()
    }

    /** Prepare() returned null — already granted. */
    fun onVpnPermissionGranted() {
        _ui.update { it.copy(permissionNeeded = false) }
        doStart()
    }

    fun onVpnPermissionDenied() {
        _ui.update {
            it.copy(
                permissionNeeded = false,
                vpnMessage = "Ошибка соединения",
                vpnState = VpnUiState.Error,
            )
        }
    }

    /** Activity detected prepare() != null — show hint while system dialog opens. */
    fun onVpnPermissionRequired() {
        _ui.update {
            it.copy(permissionNeeded = true, vpnMessage = "Ожидание разрешения системы…")
        }
    }

    private fun doStart() {
        if (_ui.value.vpnState == VpnUiState.Connecting) return
        _ui.update { it.copy(vpnState = VpnUiState.Connecting, vpnMessage = "Подключение…") }
        try {
            ProxyVpnService.start(app)
        } catch (e: Exception) {
            NetworkDiag.logError("ProxyVpnVM.start", e)
            _ui.update {
                it.copy(vpnState = VpnUiState.Error, vpnMessage = "Ошибка соединения")
            }
        }
    }

    fun disconnect() {
        if (_ui.value.vpnState != VpnUiState.Connected) return
        try {
            ProxyVpnService.stop(app)
            _ui.update { it.copy(vpnState = VpnUiState.Disconnected, vpnMessage = "Отключено") }
        } catch (e: Exception) {
            NetworkDiag.logError("ProxyVpnVM.stop", e)
        }
    }

    private fun registerStatusReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val status = intent?.getStringExtra(ProxyVpnService.EXTRA_STATUS) ?: return
                val message = intent.getStringExtra(ProxyVpnService.EXTRA_MESSAGE) ?: ""
                when (status) {
                    ProxyVpnService.STATUS_CONNECTED -> {
                        _ui.update {
                            it.copy(vpnState = VpnUiState.Connected, vpnMessage = message)
                        }
                        refreshSystemVpnStatus()
                    }
                    ProxyVpnService.STATUS_DISCONNECTED -> {
                        _ui.update {
                            it.copy(vpnState = VpnUiState.Disconnected, vpnMessage = message)
                        }
                        refreshSystemVpnStatus()
                    }
                    ProxyVpnService.STATUS_ERROR -> {
                        // Keep user-facing copy generic; do not surface raw engine text.
                        _ui.update {
                            it.copy(vpnState = VpnUiState.Error, vpnMessage = "Ошибка соединения")
                        }
                        refreshSystemVpnStatus()
                    }
                }
            }
        }
        statusReceiver = receiver
        val filter = IntentFilter(ProxyVpnService.ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            app.registerReceiver(receiver, filter)
        }
    }

    override fun onCleared() {
        statusReceiver?.let {
            try {
                app.unregisterReceiver(it)
            } catch (_: Exception) {
            }
        }
        super.onCleared()
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return ProxyVpnViewModel(context.applicationContext as Application) as T
                }
            }
    }
}
