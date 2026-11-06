package com.weathercurrency.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.weathercurrency.app.proxy.ProxyVpnViewModel
import com.weathercurrency.app.ui.MainScreen
import com.weathercurrency.app.ui.ProxyVpnScreen
import com.weathercurrency.app.ui.UpdateScreen
import com.weathercurrency.app.ui.theme.WeatherCurrencyTheme
import com.weathercurrency.app.update.UpdateNotifier
import com.weathercurrency.app.update.UpdateScheduler
import com.weathercurrency.app.update.UpdateViewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /** Set when opened from update notification. */
    private val openUpdateFromIntent = mutableStateOf(false)
    /** Set when opened from Proxy VPN notification. */
    private val openProxyFromIntent = mutableStateOf(false)

    private var proxyVmRef: ProxyVpnViewModel? = null

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val vm = proxyVmRef ?: return@registerForActivityResult
        if (result.resultCode == RESULT_OK) {
            vm.onVpnPermissionGranted()
        } else {
            vm.onVpnPermissionDenied()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleOpenUpdateIntent(intent)
        handleOpenProxyIntent(intent)

        val viewModel: MainViewModel by viewModels { MainViewModel.factory(this) }
        val updateViewModel: UpdateViewModel by viewModels { UpdateViewModel.factory(this) }
        val proxyViewModel: ProxyVpnViewModel by viewModels { ProxyVpnViewModel.factory(this) }
        proxyVmRef = proxyViewModel

        // Ensure notification channel exists; request POST_NOTIFICATIONS on 13+ once.
        UpdateNotifier.ensureChannel(this)
        maybeRequestNotificationPermission()

        // Schedule periodic check if auto enabled (idempotent KEEP).
        viewModelScopeLaunchSchedule()

        setContent {
            WeatherCurrencyTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var permissionRequested by remember { mutableStateOf(false) }
                    var currentScreen by rememberSaveable { mutableStateOf(SCREEN_HOME) }

                    val state by viewModel.uiState.collectAsStateWithLifecycle()
                    val updateState by updateViewModel.ui.collectAsStateWithLifecycle()
                    val proxyState by proxyViewModel.ui.collectAsStateWithLifecycle()

                    val drawerState = rememberDrawerState(DrawerValue.Closed)
                    val scope = rememberCoroutineScope()

                    val launcher = rememberActivityResultLauncher(
                        onResult = { viewModel.refreshWeather() }
                    )

                    // Notification → open update screen (once per intent).
                    LaunchedEffect(openUpdateFromIntent.value) {
                        if (openUpdateFromIntent.value) {
                            currentScreen = SCREEN_UPDATE
                            openUpdateFromIntent.value = false
                        }
                    }
                    LaunchedEffect(openProxyFromIntent.value) {
                        if (openProxyFromIntent.value) {
                            currentScreen = SCREEN_PROXY
                            openProxyFromIntent.value = false
                        }
                    }

                    fun openDrawer() {
                        scope.launch { drawerState.open() }
                    }

                    fun closeDrawerTo(screen: String) {
                        scope.launch {
                            drawerState.close()
                            currentScreen = screen
                        }
                    }

                    fun requestVpnAndConnect() {
                        val prepare = VpnService.prepare(this@MainActivity)
                        if (prepare != null) {
                            proxyViewModel.onVpnPermissionRequired()
                            vpnPermissionLauncher.launch(prepare)
                        } else {
                            proxyViewModel.connect()
                        }
                    }

                    ModalNavigationDrawer(
                        drawerState = drawerState,
                        drawerContent = {
                            ModalDrawerSheet {
                                Text(
                                    text = "Погода и Валюта",
                                    style = MaterialTheme.typography.titleLarge,
                                    modifier = Modifier.padding(20.dp, 24.dp, 20.dp, 12.dp)
                                )
                                NavigationDrawerItem(
                                    label = { Text("Главная") },
                                    selected = currentScreen == SCREEN_HOME,
                                    onClick = { closeDrawerTo(SCREEN_HOME) },
                                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                                )
                                NavigationDrawerItem(
                                    label = { Text("Обновление приложения") },
                                    selected = currentScreen == SCREEN_UPDATE,
                                    onClick = { closeDrawerTo(SCREEN_UPDATE) },
                                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                                )
                                NavigationDrawerItem(
                                    label = { Text("Proxy VPN") },
                                    selected = currentScreen == SCREEN_PROXY,
                                    onClick = { closeDrawerTo(SCREEN_PROXY) },
                                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                                )
                            }
                        }
                    ) {
                        when (currentScreen) {
                            SCREEN_UPDATE -> UpdateScreen(
                                state = updateState,
                                onMenuClick = { openDrawer() },
                                onCheck = { updateViewModel.checkForUpdates() },
                                onDownload = { updateViewModel.downloadAndInstall() },
                                onInstallAgain = { updateViewModel.installVerified() },
                                onAutoCheckChange = { updateViewModel.setAutoCheckEnabled(it) },
                                onNotifyChange = { updateViewModel.setNotifyEnabled(it) }
                            )

                            SCREEN_PROXY -> ProxyVpnScreen(
                                state = proxyState,
                                onMenuClick = { openDrawer() },
                                onTest = { proxyViewModel.runTest() },
                                onConnect = { requestVpnAndConnect() },
                                onDisconnect = { proxyViewModel.disconnect() },
                            )

                            else -> MainScreen(
                                state = state,
                                onMenuClick = { openDrawer() },
                                onRefreshAll = { viewModel.refreshAll() },
                                onRefreshWeather = {
                                    if (!permissionRequested) {
                                        permissionRequested = true
                                        launcher.launch(
                                            arrayOf(
                                                Manifest.permission.ACCESS_FINE_LOCATION,
                                                Manifest.permission.ACCESS_COARSE_LOCATION
                                            )
                                        )
                                    } else {
                                        viewModel.refreshWeather()
                                    }
                                },
                                onRefreshCurrency = { viewModel.refreshCurrency() },
                                onRefreshCrypto = { viewModel.refreshCrypto() },
                                onCryptoCurrencyChange = { viewModel.setCryptoCurrency(it) },
                                onCryptoPageVisible = { viewModel.onCryptoPageSelected() },
                                onRequestPermission = {
                                    permissionRequested = true
                                    launcher.launch(
                                        arrayOf(
                                            Manifest.permission.ACCESS_FINE_LOCATION,
                                            Manifest.permission.ACCESS_COARSE_LOCATION
                                        )
                                    )
                                }
                            )
                        }
                    }

                    // In-app update dialog (startup check only — not on drawer navigation).
                    state.updateDialog?.let { dialog ->
                        AlertDialog(
                            onDismissRequest = { viewModel.dismissUpdateDialog(postpone = true) },
                            title = { Text("Доступно обновление ${dialog.versionName}") },
                            text = {
                                Text("Вышла новая версия приложения. Хотите обновиться сейчас?")
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    viewModel.consumeUpdateDialog()
                                    currentScreen = SCREEN_UPDATE
                                }) {
                                    Text("Обновить")
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = {
                                    viewModel.dismissUpdateDialog(postpone = true)
                                }) {
                                    Text("Позже")
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleOpenUpdateIntent(intent)
        handleOpenProxyIntent(intent)
    }

    private fun handleOpenUpdateIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(UpdateNotifier.EXTRA_OPEN_UPDATE, false) == true) {
            openUpdateFromIntent.value = true
        }
    }

    private fun handleOpenProxyIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_PROXY_VPN, false) == true) {
            openProxyFromIntent.value = true
        }
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) return
        val launcher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { }
        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun viewModelScopeLaunchSchedule() {
        window.decorView.post {
            try {
                UpdateScheduler.schedule(this)
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        private const val SCREEN_HOME = "home"
        private const val SCREEN_UPDATE = "update"
        private const val SCREEN_PROXY = "proxy"
        const val EXTRA_OPEN_PROXY_VPN = "open_proxy_vpn"
    }
}

@androidx.compose.runtime.Composable
private fun rememberActivityResultLauncher(
    onResult: (Boolean) -> Unit
): androidx.activity.result.ActivityResultLauncher<Array<String>> {
    val callback = remember { androidx.compose.runtime.mutableStateOf(onResult) }
    callback.value = onResult
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val any = grants.values.any { it }
        callback.value?.invoke(any)
    }
    return launcher
}
