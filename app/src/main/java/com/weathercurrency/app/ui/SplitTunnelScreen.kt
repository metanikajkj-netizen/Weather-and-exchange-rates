package com.weathercurrency.app.ui

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.weathercurrency.app.proxy.SplitTunnelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Theme colors (preserved from original)
private val Bg = Color(0xFF0C1728)
private val Card = Color(0xFF15283E)
private val Accent = Color(0xFF38BDF8)
private val TextPrimary = Color(0xFFF0F7FF)
private val TextSecondary = Color(0xFF94AFCB)

// Subtle highlight color for selected apps (very faint blue tint)
private val SelectedBg = Color(0x1438BDF8)  // Accent with ~8% opacity

data class InstalledApp(
    val packageName: String,
    val name: String,
    val isSystem: Boolean,
    val icon: Drawable? = null
)

// Icon cache to avoid repeated PackageManager calls
private val iconCache = java.util.concurrent.ConcurrentHashMap<String, Drawable?>()

private fun loadAppIcon(context: Context, packageName: String): Drawable? {
    iconCache[packageName]?.let { return it }
    return try {
        val drawable = context.packageManager.getApplicationIcon(packageName)
        iconCache[packageName] = drawable
        drawable
    } catch (_: Exception) {
        iconCache[packageName] = null
        null
    }
}

@Composable
fun SplitTunnelScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { SplitTunnelStore(context) }

    var mode by remember { mutableStateOf(SplitTunnelStore.MODE_ALL) }
    var selectedPackages by remember { mutableStateOf(setOf<String>()) }
    var showSystemApps by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var apps by remember { mutableStateOf(listOf<InstalledApp>()) }
    var loaded by remember { mutableStateOf(false) }

    // Auto-restart VPN when settings change (existing behavior)
    fun restartVpnIfConnected() {
        try {
            val status = com.weathercurrency.app.proxy.ProxyVpnService.lastStatus
            if (status == com.weathercurrency.app.proxy.ProxyVpnService.STATUS_CONNECTED) {
                com.weathercurrency.app.proxy.ProxyVpnService.stop(context)
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    com.weathercurrency.app.proxy.ProxyVpnService.start(context)
                }, 500)
            }
        } catch (_: Exception) { /* VPN not connected */ }
    }

    // Load settings and app list with icons
    LaunchedEffect(Unit) {
        mode = store.getMode()
        selectedPackages = store.getSelectedPackages()
        showSystemApps = store.getShowSystemApps()
        apps = withContext(Dispatchers.IO) {
            loadInstalledApps(context)
        }
        loaded = true
    }

    // Filter and sort: selected first, then alphabetical
    val filteredApps = apps.filter { app ->
        app.packageName != context.packageName &&
            (showSystemApps || !app.isSystem) &&
            (searchQuery.isBlank() || app.name.contains(searchQuery, ignoreCase = true) ||
                app.packageName.contains(searchQuery, ignoreCase = true))
    }.sortedWith(
        compareByDescending<InstalledApp> { it.packageName in selectedPackages }
            .thenBy { it.name.lowercase() }
    )

    val selectedCount = selectedPackages.size

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        // Header — "Tunnel mode"
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Назад", tint = TextSecondary)
            }
            Text(
                "Tunnel mode",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                maxLines = 1
            )
        }

        if (!loaded) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Accent)
            }
            return
        }

        // Mode selector
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Card),
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Режим туннелирования",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Accent
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = mode == SplitTunnelStore.MODE_ALL,
                        onClick = {
                            mode = SplitTunnelStore.MODE_ALL
                            scope.launch {
                                store.setMode(mode)
                                store.mirrorToPrefs()
                                restartVpnIfConnected()
                            }
                        },
                        colors = RadioButtonDefaults.colors(selectedColor = Accent)
                    )
                    Column(Modifier.weight(1f)) {
                        Text("Все приложения через VPN", color = TextPrimary, fontSize = 14.sp)
                        Text("Стандартный режим", color = TextSecondary, fontSize = 12.sp)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = mode == SplitTunnelStore.MODE_SELECTED,
                        onClick = {
                            mode = SplitTunnelStore.MODE_SELECTED
                            scope.launch {
                                store.setMode(mode)
                                store.mirrorToPrefs()
                                restartVpnIfConnected()
                            }
                        },
                        colors = RadioButtonDefaults.colors(selectedColor = Accent)
                    )
                    Column(Modifier.weight(1f)) {
                        Text("Только выбранные через VPN", color = TextPrimary, fontSize = 14.sp)
                        Text("Остальные напрямую", color = TextSecondary, fontSize = 12.sp)
                    }
                }

                if (mode == SplitTunnelStore.MODE_SELECTED && selectedCount == 0) {
                    Text(
                        "⚠ Список пуст — VPN не будет работать корректно. Выберите хотя бы одно приложение.",
                        color = Color(0xFFF87171),
                        fontSize = 12.sp
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // Search
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text("Поиск") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                modifier = Modifier.weight(1f),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedBorderColor = Accent,
                    unfocusedBorderColor = TextSecondary
                )
            )
        }

        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Выбрано: $selectedCount",
                color = TextSecondary,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f)
            )
            OutlinedButton(
                onClick = {
                    selectedPackages = apps.filter { it.packageName != context.packageName && (!it.isSystem || showSystemApps) }.map { it.packageName }.toSet()
                    scope.launch {
                        store.setSelectedPackages(selectedPackages)
                        store.mirrorToPrefs()
                    }
                },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Accent),
                border = ButtonDefaults.outlinedButtonBorder.copy(width = 1.dp, brush = androidx.compose.ui.graphics.SolidColor(Accent))
            ) {
                Text("Выбрать все", fontSize = 12.sp)
            }
            OutlinedButton(
                onClick = {
                    selectedPackages = emptySet()
                    scope.launch {
                        store.setSelectedPackages(selectedPackages)
                        store.mirrorToPrefs()
                    }
                },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
                border = ButtonDefaults.outlinedButtonBorder.copy(width = 1.dp, brush = androidx.compose.ui.graphics.SolidColor(TextSecondary))
            ) {
                Text("Снять все", fontSize = 12.sp)
            }
        }

        // System apps toggle
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = showSystemApps,
                onCheckedChange = {
                    showSystemApps = it
                    scope.launch {
                        store.setShowSystemApps(it)
                    }
                },
                colors = CheckboxDefaults.colors(checkedColor = Accent)
            )
            Text("Показывать системные приложения", color = TextSecondary, fontSize = 13.sp)
        }

        Spacer(Modifier.height(4.dp))

        // App list — compact spacing
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp)
        ) {
            items(filteredApps, key = { it.packageName }) { app ->
                AppRow(
                    app = app,
                    checked = app.packageName in selectedPackages,
                    onCheckedChange = { checked ->
                        selectedPackages = if (checked) {
                            selectedPackages + app.packageName
                        } else {
                            selectedPackages - app.packageName
                        }
                        scope.launch {
                            store.setSelectedPackages(selectedPackages)
                            store.mirrorToPrefs()
                            restartVpnIfConnected()
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun AppRow(
    app: InstalledApp,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (checked) SelectedBg else Color.Transparent)
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 3.dp, horizontal = 8.dp)
    ) {
        // App icon — real icon or fallback
        val context = LocalContext.current
        val icon by produceState<Drawable?>(initialValue = app.icon) {
            if (value == null) {
                value = withContext(Dispatchers.IO) {
                    loadAppIcon(context, app.packageName)
                }
            }
        }

        if (icon != null) {
            val bitmap = remember(icon) {
                val bmp = android.graphics.Bitmap.createBitmap(
                    40.dp.value.toInt() * 3,
                    40.dp.value.toInt() * 3,
                    android.graphics.Bitmap.Config.ARGB_8888
                )
                val canvas = android.graphics.Canvas(bmp)
                icon!!.setBounds(0, 0, canvas.width, canvas.height)
                icon!!.draw(canvas)
                bmp.asImageBitmap()
            }
            Image(
                bitmap = bitmap,
                contentDescription = app.name,
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(8.dp))
            )
        } else {
            // Fallback: letter placeholder
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Accent.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    app.name.take(1).uppercase(),
                    color = Accent,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                app.name,
                color = TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                app.packageName,
                color = TextSecondary,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = CheckboxDefaults.colors(checkedColor = Accent)
        )
    }
}

private fun loadInstalledApps(context: Context): List<InstalledApp> {
    val pm = context.packageManager
    val apps = mutableMapOf<String, InstalledApp>()

    // Primary: query launcher activities
    try {
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        val resolveInfos = pm.queryIntentActivities(intent, 0)
        for (ri in resolveInfos) {
            val pkg = ri.activityInfo?.packageName ?: continue
            if (pkg in apps) continue
            val info = ri.activityInfo?.applicationInfo ?: continue
            val isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
            val icon = try { pm.getApplicationIcon(pkg) } catch (_: Exception) { null }
            apps[pkg] = InstalledApp(
                packageName = pkg,
                name = try { pm.getApplicationLabel(info).toString() } catch (_: Exception) { pkg },
                isSystem = isSystem,
                icon = icon
            )
        }
    } catch (_: Exception) { /* fallback below */ }

    // Fallback: getInstalledApplications
    try {
        val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        for (info in installed) {
            val pkg = info.packageName
            if (pkg in apps) continue
            val isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
            val icon = try { pm.getApplicationIcon(pkg) } catch (_: Exception) { null }
            apps[pkg] = InstalledApp(
                packageName = pkg,
                name = try { pm.getApplicationLabel(info).toString() } catch (_: Exception) { pkg },
                isSystem = isSystem,
                icon = icon
            )
        }
    } catch (_: Exception) { /* already have data */ }

    return apps.values.sortedBy { it.name.lowercase() }
}
