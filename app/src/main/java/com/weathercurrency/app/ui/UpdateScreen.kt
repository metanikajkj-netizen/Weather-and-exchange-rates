package com.weathercurrency.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.weathercurrency.app.update.UpdatePhase
import com.weathercurrency.app.update.UpdateUiState
import java.util.Locale

/** Update screen: current version, check button, release info, download/install. */
@Composable
fun UpdateScreen(
    state: UpdateUiState,
    onMenuClick: () -> Unit,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onInstallAgain: () -> Unit,
    onAutoCheckChange: (Boolean) -> Unit = {},
    onNotifyChange: (Boolean) -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onMenuClick) {
                Icon(Icons.Default.Menu, contentDescription = "Меню")
            }
            Text(
                text = "Обновление приложения",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        Card(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Текущая версия",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                )
                Text(
                    text = "${state.installedVersionName} (versionCode ${state.installedVersionCode})",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                Spacer(Modifier.height(4.dp))

                when (state.phase) {
                    UpdatePhase.Checking -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp))
                            Text("Проверка обновлений…")
                        }
                    }

                    UpdatePhase.NoUpdate -> {
                        Text(
                            text = state.message ?: "Установлена последняя версия",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    UpdatePhase.Available -> {
                        Text(
                            text = state.message ?: "Доступна новая версия",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        state.availableVersionName?.let {
                            Text("Версия: $it", style = MaterialTheme.typography.bodyMedium)
                        }
                        if (state.tag.isNotBlank()) {
                            Text("Релиз: ${state.tag}", style = MaterialTheme.typography.bodySmall)
                        }
                        state.apkSizeBytes?.let {
                            Text("APK: ${formatSize(it)}", style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    UpdatePhase.Downloading -> {
                        Text("Загрузка обновления…", style = MaterialTheme.typography.bodyMedium)
                        LinearProgressIndicator(
                            progress = {
                                if (state.downloadProgress >= 0) state.downloadProgress / 100f else 0f
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (state.downloadProgress >= 0) {
                            Text("${state.downloadProgress}%", style = MaterialTheme.typography.labelMedium)
                        }
                    }

                    UpdatePhase.Verifying -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp))
                            Text(state.message ?: "Проверка файла…")
                        }
                    }

                    UpdatePhase.ReadyToInstall -> {
                        Text(
                            text = state.message ?: "Файл проверен",
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }

                    UpdatePhase.Error -> {
                        Text(
                            text = state.error ?: "Ошибка",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    UpdatePhase.Idle -> {
                        Text(
                            text = "Нажмите «Проверить обновления»",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                state.error?.takeIf { state.phase != UpdatePhase.Error }?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }

                Spacer(Modifier.height(4.dp))

                when (state.phase) {
                    UpdatePhase.Available, UpdatePhase.Error, UpdatePhase.NoUpdate, UpdatePhase.Idle ->
                        Button(
                            onClick = onCheck,
                            enabled = state.phase != UpdatePhase.Checking,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Проверить обновления")
                        }
                    else -> Unit
                }

                when (state.phase) {
                    UpdatePhase.Available ->
                        Button(
                            onClick = onDownload,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                        ) {
                            Text("Скачать и установить")
                        }
                    UpdatePhase.ReadyToInstall ->
                        Button(
                            onClick = onInstallAgain,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                        ) {
                            Text("Установить")
                        }
                    else -> Unit
                }
            }
        }

        // Release notes
        if (state.phase == UpdatePhase.Available && state.releaseNotes.isNotBlank()) {
            Card(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Список изменений",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = state.releaseNotes.take(4000),
                        style = MaterialTheme.typography.bodyMedium,
                        overflow = TextOverflow.Clip
                    )
                }
            }
        }

        // Settings
        Card(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Настройки обновлений",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Автоматически проверять обновления",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    androidx.compose.material3.Switch(
                        checked = state.autoCheckEnabled,
                        onCheckedChange = onAutoCheckChange
                    )
                }
                Text(
                    text = "Фоновая проверка примерно каждые 15 минут при наличии интернета. " +
                        "Android может задерживать выполнение",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Уведомлять о новых версиях",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    androidx.compose.material3.Switch(
                        checked = state.notifyEnabled,
                        onCheckedChange = onNotifyChange
                    )
                }
                Text(
                    text = "Системные уведомления при выходе новой версии",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Text(
            text = "Источник обновлений: GitHub Releases · проверка подписи и SHA-256 перед установкой",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun formatSize(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return String.format(Locale.US, "%.2f МБ", mb)
}
