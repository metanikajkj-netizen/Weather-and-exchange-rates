package com.weathercurrency.app

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.weathercurrency.app.data.CacheStore
import com.weathercurrency.app.data.CryptoRepository
import com.weathercurrency.app.data.CurrencyRepository
import com.weathercurrency.app.data.NetworkDiag
import com.weathercurrency.app.data.WeatherRepository
import com.weathercurrency.app.model.CryptoCurrency
import com.weathercurrency.app.model.CryptoResult
import com.weathercurrency.app.model.CryptoSnapshot
import com.weathercurrency.app.model.CurrencyResult
import com.weathercurrency.app.model.CurrencySnapshot
import com.weathercurrency.app.model.WeatherResult
import com.weathercurrency.app.model.WeatherSnapshot
import com.weathercurrency.app.update.UpdateAutoLogic
import com.weathercurrency.app.update.UpdateCheckResult
import com.weathercurrency.app.update.UpdateLogic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class WeatherUiState(
    val loading: Boolean = false,
    val snapshot: WeatherSnapshot? = null,
    val error: String? = null,
    val fromCache: Boolean = false,
    val lastRefreshMillis: Long = 0L
)

data class CurrencyUiState(
    val loading: Boolean = false,
    val snapshot: CurrencySnapshot? = null,
    val error: String? = null,
    val fromCache: Boolean = false,
    val lastRefreshMillis: Long = 0L
)

data class CryptoUiState(
    val loading: Boolean = false,
    val snapshot: CryptoSnapshot? = null,
    val error: String? = null,
    val fromCache: Boolean = false,
    val lastRefreshMillis: Long = 0L,
    /** Selected display currency (RUB/USD); persisted, no API on toggle. */
    val currency: CryptoCurrency = CryptoCurrency.RUB
)

data class UpdateDialogState(
    val versionName: String,
    val versionCode: Long
)

data class MainUiState(
    val weather: WeatherUiState = WeatherUiState(),
    val currency: CurrencyUiState = CurrencyUiState(),
    val crypto: CryptoUiState = CryptoUiState(),
    val permissionDenied: Boolean = false,
    /** Non-null → show in-app update dialog (once, not on every navigation). */
    val updateDialog: UpdateDialogState? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val weatherRepository = WeatherRepository(application)
    private val currencyRepository = CurrencyRepository(application)
    private val cryptoRepository = CryptoRepository(application)
    private val cache = CacheStore(application)

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        bootstrap()
    }

    /** Paint from cache immediately, then refresh sources once. */
    private fun bootstrap() {
        viewModelScope.launch {
            val w = cache.loadWeather()
            val c = cache.loadCurrency()
            val k = cache.loadCrypto()
            val ccy = CryptoCurrency.fromCode(cache.loadCryptoCurrency())
            _uiState.update {
                it.copy(
                    weather = WeatherUiState(snapshot = w, fromCache = w != null),
                    currency = CurrencyUiState(snapshot = c, fromCache = c != null),
                    crypto = CryptoUiState(
                        snapshot = k,
                        fromCache = k != null,
                        currency = ccy
                    )
                )
            }
            refreshAll()
            maybeStartupUpdateCheck()
        }
    }

    /**
     * Automatic in-app update check on app open (throttled, settings-aware).
     * Shares GitHubReleaseClient + UpdateLogic with manual/worker paths.
     * Never downloads APK; may set [MainUiState.updateDialog].
     */
    private fun maybeStartupUpdateCheck() {
        viewModelScope.launch {
            try {
                val settings = cache.loadUpdateSettings()
                val now = System.currentTimeMillis()
                if (!UpdateAutoLogic.shouldStartupCheck(settings, now)) return@launch
                if (!NetworkDiag.isOnline(getApplication())) return@launch

                val installed = installedVersionCode()
                val release = withContext(Dispatchers.IO) {
                    com.weathercurrency.app.update.GitHubReleaseClient().fetchLatest()
                }
                val decision = UpdateLogic.decide(
                    installedVersionCode = installed,
                    installedPackage = getApplication<Application>().packageName,
                    release = release
                )
                val after = cache.loadUpdateSettings()
                    .copy(lastCheckMillis = UpdateAutoLogic.afterCheck(System.currentTimeMillis()))
                cache.saveUpdateSettings(after)

                if (decision is UpdateCheckResult.UpdateAvailable) {
                    val showDialog = UpdateAutoLogic.shouldShowDialog(
                        settings = after,
                        installedVersionCode = installed,
                        availableVersionCode = decision.metadata.versionCode.toLong(),
                        nowMillis = System.currentTimeMillis()
                    )
                    if (showDialog) {
                        _uiState.update {
                            it.copy(
                                updateDialog = UpdateDialogState(
                                    versionName = decision.metadata.versionName,
                                    versionCode = decision.metadata.versionCode.toLong()
                                )
                            )
                        }
                    }
                }
            } catch (_: Exception) {
                // Startup check must never crash the app.
            }
        }
    }

    fun dismissUpdateDialog(postpone: Boolean) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val settings = cache.loadUpdateSettings()
            val vc = _uiState.value.updateDialog?.versionCode ?: settings.lastDialogVersionCode
            val updated = settings.copy(
                dialogPostponeUntilMillis = if (postpone) {
                    UpdateAutoLogic.postponeDialog(now)
                } else {
                    settings.dialogPostponeUntilMillis
                },
                lastDialogVersionCode = if (!postpone) vc else settings.lastDialogVersionCode
            )
            // «Позже» also records version so same offer isn't re-shown every open
            val finalSettings = if (postpone) {
                updated.copy(lastDialogVersionCode = maxOf(updated.lastDialogVersionCode, vc))
            } else updated
            cache.saveUpdateSettings(finalSettings)
            _uiState.update { it.copy(updateDialog = null) }
        }
    }

    /** «Обновить» in dialog — clear dialog; navigation handled by Activity. */
    fun consumeUpdateDialog() {
        viewModelScope.launch {
            val vc = _uiState.value.updateDialog?.versionCode
            if (vc != null) {
                val s = cache.loadUpdateSettings()
                cache.saveUpdateSettings(s.copy(lastDialogVersionCode = vc))
            }
            _uiState.update { it.copy(updateDialog = null) }
        }
    }

    private fun installedVersionCode(): Long {
        return try {
            val pm = getApplication<Application>().packageManager
            val pi = if (android.os.Build.VERSION.SDK_INT >= 33) {
                pm.getPackageInfo(
                    getApplication<Application>().packageName,
                    android.content.pm.PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(getApplication<Application>().packageName, 0)
            }
            if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode
            else @Suppress("DEPRECATION") pi.versionCode.toLong()
        } catch (_: Exception) {
            0L
        }
    }

    /**
     * Switch RUB/USD instantly from already-loaded quotes.
     * Persists choice; does NOT hit the network.
     */
    fun setCryptoCurrency(currency: CryptoCurrency) {
        if (_uiState.value.crypto.currency == currency) return
        _uiState.update {
            it.copy(crypto = it.crypto.copy(currency = currency))
        }
        viewModelScope.launch {
            try {
                cache.saveCryptoCurrency(currency.code)
            } catch (_: Exception) {
                // Preference is best-effort; UI already updated.
            }
        }
    }

    fun refreshAll() {
        refreshWeather()
        refreshCurrency()
        refreshCrypto()
    }

    fun refreshWeather() {
        if (_uiState.value.weather.loading) return
        _uiState.update {
            it.copy(
                weather = it.weather.copy(loading = true, error = null),
                permissionDenied = false
            )
        }
        viewModelScope.launch {
            when (val result = weatherRepository.refresh(forceNewLocation = true)) {
                is WeatherResult.Success -> _uiState.update {
                    it.copy(
                        weather = WeatherUiState(
                            loading = false,
                            snapshot = result.snapshot,
                            fromCache = result.fromCache,
                            lastRefreshMillis = System.currentTimeMillis()
                        )
                    )
                }
                is WeatherResult.Failure -> {
                    val denied = result.message.contains("разрешения")
                    _uiState.update {
                        it.copy(
                            weather = WeatherUiState(
                                loading = false,
                                snapshot = result.cached ?: it.weather.snapshot,
                                error = result.message,
                                fromCache = result.cached != null,
                                lastRefreshMillis = it.weather.lastRefreshMillis
                            ),
                            permissionDenied = denied || it.permissionDenied
                        )
                    }
                }
            }
        }
    }

    fun refreshCurrency() {
        if (_uiState.value.currency.loading) return
        _uiState.update {
            it.copy(currency = it.currency.copy(loading = true, error = null))
        }
        viewModelScope.launch {
            when (val result = currencyRepository.refresh()) {
                is CurrencyResult.Success -> _uiState.update {
                    it.copy(
                        currency = CurrencyUiState(
                            loading = false,
                            snapshot = result.snapshot,
                            fromCache = result.fromCache,
                            lastRefreshMillis = System.currentTimeMillis()
                        )
                    )
                }
                is CurrencyResult.Failure -> _uiState.update {
                    it.copy(
                        currency = CurrencyUiState(
                            loading = false,
                            snapshot = result.cached ?: it.currency.snapshot,
                            error = result.message,
                            fromCache = result.cached != null,
                            lastRefreshMillis = it.currency.lastRefreshMillis
                        )
                    )
                }
            }
        }
    }

    fun refreshCrypto() {
        if (_uiState.value.crypto.loading) return
        _uiState.update {
            it.copy(crypto = it.crypto.copy(loading = true, error = null))
        }
        viewModelScope.launch {
            when (val result = cryptoRepository.refresh()) {
                is CryptoResult.Success -> _uiState.update {
                    it.copy(
                        crypto = CryptoUiState(
                            loading = false,
                            snapshot = result.snapshot,
                            fromCache = result.fromCache,
                            lastRefreshMillis = System.currentTimeMillis()
                        )
                    )
                }
                is CryptoResult.Failure -> _uiState.update {
                    it.copy(
                        crypto = CryptoUiState(
                            loading = false,
                            snapshot = result.cached ?: it.crypto.snapshot,
                            error = result.message,
                            fromCache = result.cached != null,
                            lastRefreshMillis = it.crypto.lastRefreshMillis
                        )
                    )
                }
            }
        }
    }

    /**
     * Called when the user opens the crypto page: fetch only if no cache
     * or cache is stale. Never called merely for swiping back and forth
     * once data is fresh (repository also throttles).
     */
    fun onCryptoPageSelected() {
        val state = _uiState.value.crypto
        if (state.loading) return
        val snap = state.snapshot
        val stale = snap == null ||
            System.currentTimeMillis() - snap.fetchedAtMillis > CRYPTO_STALE_MS
        if (stale) {
            refreshCrypto()
        }
    }

    companion object {
        /** Re-fetch crypto when older than 10 minutes on page open. */
        const val CRYPTO_STALE_MS = 10 * 60 * 1000L

        fun factory(context: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return MainViewModel(context.applicationContext as Application) as T
                }
            }
    }
}
