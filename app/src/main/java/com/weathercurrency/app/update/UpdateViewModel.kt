package com.weathercurrency.app.update

import android.app.Application
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.weathercurrency.app.data.ApiError
import com.weathercurrency.app.data.CacheStore
import com.weathercurrency.app.data.NetworkDiag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class UpdatePhase {
    Idle, Checking, NoUpdate, Available, Downloading, Verifying, ReadyToInstall, Error
}

data class UpdateUiState(
    val phase: UpdatePhase = UpdatePhase.Idle,
    /** Installed versionName, e.g. "1.5". */
    val installedVersionName: String = "",
    val installedVersionCode: Long = 0L,
    /** Available versionName when UpdateAvailable. */
    val availableVersionName: String? = null,
    val releaseNotes: String = "",
    val releaseName: String = "",
    val tag: String = "",
    val apkSizeBytes: Long? = null,
    val downloadProgress: Int = 0, // 0..100, -1 indeterminate
    val message: String? = null,
    val error: String? = null,
    val verifiedFile: File? = null,
    /** Auto-check periodic setting (default on). */
    val autoCheckEnabled: Boolean = true,
    /** Notify about new versions (default on). */
    val notifyEnabled: Boolean = true
)

/**
 * Check → download (DownloadManager) → verify → PackageInstaller handoff.
 * No automatic install; user always confirms in the system dialog.
 */
class UpdateViewModel(application: Application) : AndroidViewModel(application) {

    private val client = GitHubReleaseClient()
    private val app: Application get() = getApplication()

    private val _ui = MutableStateFlow(UpdateUiState())
    val ui: StateFlow<UpdateUiState> = _ui.asStateFlow()

    private var downloadId: Long = -1L
    private var lastMetadata: ReleaseMetadata? = null
    private var lastDownloadUrl: String? = null
    private var receiverRegistered = false

    init {
        loadInstalledVersion()
        viewModelScope.launch {
            try {
                val s = CacheStore(app).loadUpdateSettings()
                _ui.update {
                    it.copy(
                        autoCheckEnabled = s.autoCheckEnabled,
                        notifyEnabled = s.notifyEnabled
                    )
                }
                UpdateScheduler.applyAutoCheckEnabled(app, s.autoCheckEnabled)
            } catch (_: Exception) {
            }
        }
    }

    fun setAutoCheckEnabled(enabled: Boolean) {
        _ui.update { it.copy(autoCheckEnabled = enabled) }
        viewModelScope.launch {
            try {
                val cache = CacheStore(app)
                val s = cache.loadUpdateSettings().copy(autoCheckEnabled = enabled)
                cache.saveUpdateSettings(s)
                UpdateScheduler.applyAutoCheckEnabled(app, enabled)
            } catch (_: Exception) {
            }
        }
    }

    fun setNotifyEnabled(enabled: Boolean) {
        _ui.update { it.copy(notifyEnabled = enabled) }
        viewModelScope.launch {
            try {
                val cache = CacheStore(app)
                val s = cache.loadUpdateSettings().copy(notifyEnabled = enabled)
                cache.saveUpdateSettings(s)
                if (enabled) {
                    UpdateNotifier.ensureChannel(app)
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun loadInstalledVersion() {
        try {
            val pm = app.packageManager
            val pi = if (Build.VERSION.SDK_INT >= 33) {
                pm.getPackageInfo(app.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(app.packageName, 0)
            }
            val code = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode
            else @Suppress("DEPRECATION") pi.versionCode.toLong()
            _ui.update {
                it.copy(installedVersionName = pi.versionName ?: "", installedVersionCode = code)
            }
        } catch (_: Exception) {
        }
    }

    fun checkForUpdates() {
        if (_ui.value.phase == UpdatePhase.Checking) return
        _ui.update {
            it.copy(phase = UpdatePhase.Checking, error = null, message = null)
        }
        viewModelScope.launch {
            try {
                val release = withContext(Dispatchers.IO) { client.fetchLatest() }
                lastMetadata = release.metadata
                val installedPkg = app.packageName
                when (val result = UpdateLogic.decide(
                    installedVersionCode = _ui.value.installedVersionCode,
                    installedPackage = installedPkg,
                    release = release
                )) {
                    is UpdateCheckResult.UpToDate -> {
                        markChecked()
                        _ui.update {
                            it.copy(
                                phase = UpdatePhase.NoUpdate,
                                message = "Установлена последняя версия",
                                releaseNotes = "",
                                availableVersionName = null
                            )
                        }
                    }
                    is UpdateCheckResult.UpdateAvailable -> {
                        markChecked()
                        lastDownloadUrl = release.apkDownloadUrl
                        _ui.update {
                            it.copy(
                                phase = UpdatePhase.Available,
                                message = "Доступна версия ${result.metadata.versionName}",
                                availableVersionName = result.metadata.versionName,
                                releaseName = release.name,
                                tag = release.tagName,
                                releaseNotes = release.body,
                                apkSizeBytes = release.apkSizeBytes,
                                error = null
                            )
                        }
                    }
                    is UpdateCheckResult.Error -> {
                        _ui.update {
                            it.copy(
                                phase = UpdatePhase.Error,
                                error = result.message,
                                message = null
                            )
                        }
                    }
                }
            } catch (e: ApiError) {
                _ui.update {
                    it.copy(phase = UpdatePhase.Error, error = e.message ?: "Ошибка проверки")
                }
            } catch (e: Exception) {
                _ui.update {
                    it.copy(phase = UpdatePhase.Error, error = "Ошибка проверки обновления")
                }
            }
        }
    }

    private suspend fun markChecked() {
        try {
            val cache = CacheStore(app)
            val s = cache.loadUpdateSettings()
            cache.saveUpdateSettings(
                s.copy(lastCheckMillis = UpdateAutoLogic.afterCheck(System.currentTimeMillis()))
            )
        } catch (_: Exception) {
        }
    }

    fun downloadAndInstall() {
        val st = _ui.value
        val meta = lastMetadata ?: run {
            _ui.update { it.copy(error = "Нет метаданных релиза") }
            return
        }
        val url = lastDownloadUrl ?: run {
            _ui.update { it.copy(error = "Нет ссылки на APK в релизе") }
            return
        }

        // Reuse verified file if present
        val existing = File(app.getExternalFilesDir(null), meta.apkFileName)
        if (existing.exists() && existing.length() > 0) {
            viewModelScope.launch {
                _ui.update { it.copy(phase = UpdatePhase.Verifying, error = null, downloadProgress = 100) }
                val reason = withContext(Dispatchers.IO) {
                    ApkVerifier.verifyBeforeInstall(app, existing, meta, st.installedVersionCode)
                }
                if (reason == null) {
                    _ui.update { it.copy(phase = UpdatePhase.ReadyToInstall, verifiedFile = existing, message = "Файл проверен — можно устанавливать") }
                } else {
                    existing.delete()
                    startDownload(url, meta)
                }
            }
            return
        }
        startDownload(url, meta)
    }

    private fun startDownload(url: String, meta: ReleaseMetadata) {
        try {
            val dm = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val dest = File(app.getExternalFilesDir(null), meta.apkFileName)
            if (dest.exists()) dest.delete()

            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle("Обновление ${meta.versionName}")
                .setDescription("WeatherCurrency ${meta.versionName}")
                .setMimeType("application/vnd.android.package-archive")
                .setDestinationUri(Uri.fromFile(dest))
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            downloadId = dm.enqueue(request)

            _ui.update {
                it.copy(
                    phase = UpdatePhase.Downloading,
                    downloadProgress = 0,
                    error = null,
                    message = "Загрузка обновления…",
                    verifiedFile = null
                )
            }
            registerReceiver(dest, meta)
        } catch (e: Exception) {
            NetworkDiag.logError("UpdateVM.download", e)
            _ui.update { it.copy(phase = UpdatePhase.Error, error = "Не удалось начать загрузку") }
        }
    }

    private fun registerReceiver(dest: File, meta: ReleaseMetadata) {
        if (receiverRegistered) {
            app.unregisterReceiver(receiver)
        }
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            app.registerReceiver(receiver, filter)
        }
        pendingDest = dest
        pendingMeta = meta
        receiverRegistered = true
        // Progress polling
        viewModelScope.launch {
            while (_ui.value.phase == UpdatePhase.Downloading) {
                pollProgress()
                kotlinx.coroutines.delay(500)
            }
        }
    }

    private var pendingDest: File? = null
    private var pendingMeta: ReleaseMetadata? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (id != downloadId) return
            onDownloadFinished()
        }
    }

    private fun pollProgress() {
        try {
            val dm = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val q = DownloadManager.Query().setFilterById(downloadId)
            val c: Cursor = dm.query(q) ?: return
            c.use {
                if (!it.moveToFirst()) return
                val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val total = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                val soFar = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val progress = if (total > 0) ((soFar * 100) / total).toInt() else -1
                when (status) {
                    DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PENDING ->
                        _ui.update { it.copy(downloadProgress = progress) }
                    DownloadManager.STATUS_SUCCESSFUL -> onDownloadFinished()
                    DownloadManager.STATUS_FAILED -> _ui.update {
                        it.copy(phase = UpdatePhase.Error, error = "Загрузка не удалась")
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun onDownloadFinished() {
        val dest = pendingDest ?: return
        val meta = pendingMeta ?: return
        viewModelScope.launch {
            _ui.update { it.copy(phase = UpdatePhase.Verifying, message = "Проверка файла…", downloadProgress = 100) }
            val reason = withContext(Dispatchers.IO) {
                if (!dest.exists()) "Файл не загружен"
                else ApkVerifier.verifyBeforeInstall(app, dest, meta, _ui.value.installedVersionCode)
            }
            if (reason == null) {
                val ok = ApkVerifier.launchInstall(app, dest)
                if (ok) {
                    _ui.update {
                        it.copy(
                            phase = UpdatePhase.ReadyToInstall,
                            verifiedFile = dest,
                            message = "Подтвердите установку в системном окне"
                        )
                    }
                } else {
                    _ui.update {
                        it.copy(
                            phase = UpdatePhase.ReadyToInstall,
                            verifiedFile = dest,
                            message = "Файл проверен. Откройте установку ещё раз",
                            error = "Не удалось открыть установщик"
                        )
                    }
                }
            } else {
                dest.delete()
                _ui.update {
                    it.copy(phase = UpdatePhase.Error, error = reason, verifiedFile = null)
                }
            }
        }
    }

    /** Retry install for an already verified file. */
    fun installVerified() {
        val f = _ui.value.verifiedFile ?: return
        val ok = ApkVerifier.launchInstall(app, f)
        if (!ok) {
            _ui.update { it.copy(error = "Не удалось открыть установщик") }
        } else {
            _ui.update { it.copy(error = null, message = "Подтвердите установку в системном окне") }
        }
    }

    /** Wire URL from check result into download. */
    fun startDownloadForCurrent() {
        downloadAndInstall()
    }

    override fun onCleared() {
        if (receiverRegistered) {
            try {
                app.unregisterReceiver(receiver)
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
                    return UpdateViewModel(context.applicationContext as Application) as T
                }
            }
    }
}
