package com.weathercurrency.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.weathercurrency.app.MainUiState
import com.weathercurrency.app.data.WeatherCodes
import com.weathercurrency.app.model.CryptoSnapshot
import com.weathercurrency.app.model.CurrencySnapshot
import com.weathercurrency.app.model.WeatherSnapshot

/**
 * Single-screen UI.
 * System bars are handled once via [windowInsetsPadding] on the root —
 * no Scaffold, so insets are never applied twice.
 *
 * Lower financial block is a 2-page [HorizontalPager] (fiat | crypto).
 */
@Composable
fun MainScreen(
    state: MainUiState,
    onMenuClick: () -> Unit = {},
    onRefreshAll: () -> Unit,
    onRefreshWeather: () -> Unit,
    onRefreshCurrency: () -> Unit,
    onRefreshCrypto: () -> Unit,
    onCryptoCurrencyChange: (com.weathercurrency.app.model.CryptoCurrency) -> Unit,
    onCryptoPageVisible: () -> Unit,
    onRequestPermission: () -> Unit
) {
    // Survives configuration change (rotation) via rememberSaveable.
    var financePage by rememberSaveable { mutableIntStateOf(0) }
    val pagerState = rememberPagerState(initialPage = financePage) { 2 }

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            financePage = page
            if (page == 1) onCryptoPageVisible()
        }
    }

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
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onMenuClick) {
                Icon(Icons.Default.Menu, contentDescription = "Меню")
            }
            Text(
                text = "Погода и Валюта",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            IconButton(
                onClick = onRefreshAll,
                enabled = !state.weather.loading && !state.currency.loading && !state.crypto.loading
            ) {
                Icon(Icons.Default.Refresh, contentDescription = "Обновить всё")
            }
        }

        WeatherCard(
            state = state,
            onRefresh = onRefreshWeather,
            onRequestPermission = onRequestPermission
        )

        // Financial pager: horizontal swipe, not infinite.
        HorizontalPager(
            state = pagerState,
            pageSpacing = 0.dp,
            userScrollEnabled = true,
            modifier = Modifier.fillMaxWidth()
        ) { page ->
            when (page) {
                0 -> CurrencyCard(state = state, onRefresh = onRefreshCurrency)
                else -> CryptoCard(
                    state = state,
                    onRefresh = onRefreshCrypto,
                    onCurrencyChange = onCryptoCurrencyChange
                )
            }
        }

        PageIndicator(
            count = 2,
            selected = pagerState.currentPage,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )

        Spacer(modifier = Modifier.height(4.dp))
    }
}

@Composable
private fun PageIndicator(
    count: Int,
    selected: Int,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(count) { index ->
            val active = index == selected
            Box(
                modifier = Modifier
                    .size(if (active) 10.dp else 8.dp)
                    .background(
                        color = if (active) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        },
                        shape = CircleShape
                    )
            )
        }
    }
}

@Composable
private fun WeatherCard(
    state: MainUiState,
    onRefresh: () -> Unit,
    onRequestPermission: () -> Unit
) {
    val w = state.weather
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Погода",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                if (w.loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                }
            }

            val snapshot = w.snapshot
            when {
                snapshot == null && w.loading -> {
                    Spacer(Modifier.height(12.dp))
                    Text("Загрузка данных о погоде…", style = MaterialTheme.typography.bodyMedium)
                }

                snapshot == null -> {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = w.error ?: "Нет данных о погоде",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(12.dp))
                    if (state.permissionDenied || w.error?.contains("разрешения") == true) {
                        Button(
                            onClick = onRequestPermission,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Разрешить геолокацию")
                        }
                    } else {
                        Button(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
                            Text("Обновить")
                        }
                    }
                }

                else -> {
                    // Meta line: cache status / error + update time
                    val meta = buildString {
                        if (w.fromCache || w.error != null) {
                            append("Последние сохранённые данные")
                        } else {
                            append("Обновлено: ${Format.time(snapshot.fetchedAtMillis)}")
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (w.error != null) {
                        Text(
                            text = w.error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                    CurrentWeatherBlock(snapshot)
                    Spacer(Modifier.height(12.dp))
                    ForecastRow(snapshot)
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = onRefresh,
                        enabled = !w.loading,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Обновить")
                    }
                }
            }
        }
    }
}

@Composable
private fun CurrentWeatherBlock(snapshot: WeatherSnapshot) {
    val cur = snapshot.current
    val info = WeatherCodes.describe(cur.weatherCode)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = snapshot.cityLabel,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = info.description,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = Format.temp(cur.temperatureC),
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Bold
            )
        }
        Text(
            text = info.icon,
            fontSize = 56.sp,
            modifier = Modifier.padding(start = 8.dp)
        )
    }

    Spacer(Modifier.height(12.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Metric(label = "Ощущается", value = Format.temp(cur.apparentTemperatureC))
        Metric(label = "Влажность", value = "${cur.humidityPercent}%")
        Metric(label = "Ветер", value = "${Format.wind(cur.windSpeedKmh)} км/ч")
    }
}

@Composable
private fun ForecastRow(snapshot: WeatherSnapshot) {
    Text(
        text = "Прогноз на 3 дня",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold
    )
    Spacer(Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        snapshot.forecast.take(3).forEach { day ->
            val info = WeatherCodes.describe(day.weatherCode)
            Card(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp, horizontal = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = Format.dayName(day.dateIso),
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(text = info.icon, fontSize = 26.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "${Format.temp(day.maxC)} / ${Format.temp(day.minC)}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = info.description,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        minLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun CurrencyCard(state: MainUiState, onRefresh: () -> Unit) {
    val c = state.currency
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Курсы валют",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                if (c.loading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }

            val snapshot = c.snapshot
            when {
                snapshot == null && c.loading -> {
                    Spacer(Modifier.height(12.dp))
                    Text("Загрузка курсов ЦБ РФ…", style = MaterialTheme.typography.bodyMedium)
                }

                snapshot == null -> {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = c.error ?: "Нет данных о курсах",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
                        Text("Обновить")
                    }
                }

                else -> {
                    if (c.fromCache || c.error != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Последние сохранённые данные",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (c.error != null) {
                            Text(
                                text = c.error,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    RateRow("🇺🇸", "USD", "Доллар США", snapshot, "USD")
                    RateRow("🇪🇺", "EUR", "Евро", snapshot, "EUR")
                    RateRow("🇨🇳", "CNY", "Юань", snapshot, "CNY")

                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Дата курса: ${snapshot.rateDate}",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Источник: ЦБ РФ (официальный курс, не биржевой)",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Загружено: ${Format.time(snapshot.fetchedAtMillis)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = onRefresh,
                        enabled = !c.loading,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Обновить")
                    }
                }
            }
        }
    }
}

@Composable
private fun RateRow(
    flag: String,
    code: String,
    name: String,
    snapshot: CurrencySnapshot,
    charCode: String
) {
    val rate = snapshot.rate(charCode)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f, fill = false)
        ) {
            Text(text = flag, fontSize = 18.sp)
            Spacer(Modifier.width(8.dp))
            Column {
                Text(
                    text = "$code — $name",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (rate != null && rate.nominal != 1) {
                        "за ${rate.nominal} ед."
                    } else {
                        "за 1 единицу"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (rate != null) {
                String.format(java.util.Locale.US, "%.4f ₽", rate.ratePerUnit)
            } else {
                "—"
            },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun CryptoCard(
    state: MainUiState,
    onRefresh: () -> Unit,
    onCurrencyChange: (com.weathercurrency.app.model.CryptoCurrency) -> Unit
) {
    val c = state.crypto
    val selectedCcy = c.currency
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Курсы криптовалют",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(Modifier.width(8.dp))
                if (c.loading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                RubUsdToggle(
                    selected = selectedCcy,
                    onSelect = onCurrencyChange
                )
            }

            val snapshot = c.snapshot
            when {
                snapshot == null && c.loading -> {
                    Spacer(Modifier.height(12.dp))
                    Text("Загрузка криптовалют…", style = MaterialTheme.typography.bodyMedium)
                }

                snapshot == null -> {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = c.error ?: "Нет данных о криптовалютах",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
                        Text("Обновить")
                    }
                }

                else -> {
                    if (c.fromCache || c.error != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Последние сохранённые данные",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (c.error != null) {
                            Text(
                                text = c.error,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    snapshot.rates.forEach { rate ->
                        CryptoRow(rate, selectedCcy)
                    }

                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Источник: ${snapshot.source} (рыночная цена, не биржевой курс ЦБ)",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Загружено: ${Format.time(snapshot.fetchedAtMillis)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = onRefresh,
                        enabled = !c.loading,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Обновить")
                    }
                }
            }
        }
    }
}

/**
 * Compact ₽ | $ segmented control. Clickable targets consume taps so they
 * do not fight HorizontalPager drag gestures (pager only reacts to drag).
 */
@Composable
private fun RubUsdToggle(
    selected: com.weathercurrency.app.model.CryptoCurrency,
    onSelect: (com.weathercurrency.app.model.CryptoCurrency) -> Unit
) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier = Modifier
            .background(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
                shape = shape
            )
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        ToggleSegment(
            label = "₽",
            active = selected == com.weathercurrency.app.model.CryptoCurrency.RUB,
            contentDescription = "Показывать в рублях",
            onClick = { onSelect(com.weathercurrency.app.model.CryptoCurrency.RUB) }
        )
        ToggleSegment(
            label = "$",
            active = selected == com.weathercurrency.app.model.CryptoCurrency.USD,
            contentDescription = "Показывать в долларах США",
            onClick = { onSelect(com.weathercurrency.app.model.CryptoCurrency.USD) }
        )
    }
}

@Composable
private fun ToggleSegment(
    label: String,
    active: Boolean,
    contentDescription: String,
    onClick: () -> Unit
) {
    val bg = if (active) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surface.copy(alpha = 0f)
    }
    val fg = if (active) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = 44.dp, minHeight = 36.dp)
            .clip(RoundedCornerShape(50))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
            color = fg,
            maxLines = 1
        )
    }
}

@Composable
private fun CryptoRow(
    rate: com.weathercurrency.app.model.CryptoRate,
    currency: com.weathercurrency.app.model.CryptoCurrency
) {
    val price = Format.priceCrypto(rate.priceFor(currency), currency)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f, fill = false)
        ) {
            Text(text = rate.icon, fontSize = 18.sp)
            Spacer(Modifier.width(8.dp))
            Column {
                Text(
                    text = "${rate.symbol} — ${rate.name}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = currency.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = price ?: "—",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
