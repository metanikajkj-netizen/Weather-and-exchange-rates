package com.weathercurrency.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.weathercurrency.app.proxy.ProxyUiState
import com.weathercurrency.app.proxy.VpnUiState
import com.weathercurrency.app.subscription.SubscriptionUiState

// Premium Dark Blue / Frosted Glass palette (TASK25).
private val BgDeep = Color(0xFF0C1728)
private val CardBlue = Color(0xFF15283E)
private val CardRaised = Color(0xFF203A54)
private val AccentBlue = Color(0xFF38BDF8)
private val AccentSoft = Color(0xFF7DD3FC)
private val TextPrimary = Color(0xFFF0F7FF)
private val TextSecondary = Color(0xFF94AFCB)
private val StateGray = Color(0xFF6B7C93)
private val StateGreen = Color(0xFF34D399)
private val StateRed = Color(0xFFF87171)

private data class StatusUi(
    val label: String,
    val dot: Color,
)

private fun statusFor(state: ProxyUiState): StatusUi = when {
    state.testing -> StatusUi("Проверка", AccentBlue)
    state.vpnState == VpnUiState.Connecting -> StatusUi("Подключение", AccentBlue)
    state.vpnState == VpnUiState.Connected -> StatusUi("Подключено", StateGreen)
    state.vpnState == VpnUiState.Error -> StatusUi("Ошибка соединения", StateRed)
    else -> StatusUi("Не подключено", StateGray)
}

/**
 * Unified VPN screen (TASK29.5): connection status + subscription + promo in one block.
 * Replaces separate «VPN-подписка» screen. Uses per-device gateway credentials.
 */
@Composable
fun ProxyVpnScreen(
    state: ProxyUiState,
    subState: SubscriptionUiState,
    onMenuClick: () -> Unit,
    onTest: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onPromoChange: (String) -> Unit,
    onActivatePromo: () -> Unit,
    onRefreshSub: () -> Unit,
    onDiagnostics: () -> Unit = {},
    onSplitTunnel: () -> Unit = {}
) {
    val status = statusFor(state)
    val statusColor by animateColorAsState(status.dot, label = "statusColor")

    var testPressed by remember { mutableStateOf(false) }
    var connPressed by remember { mutableStateOf(false) }
    val testScale by animateFloatAsState(if (testPressed) 0.93f else 1f, label = "testScale")
    val connScale by animateFloatAsState(if (connPressed) 0.93f else 1f, label = "connScale")

    val spin = rememberInfiniteTransition(label = "spin")
    val spinAngle by spin.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
        label = "spinAngle"
    )
    val glow by spin.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse),
        label = "glow"
    )

    val testBusy = state.testing
    val connBusy = state.vpnState == VpnUiState.Connecting
    val connected = state.vpnState == VpnUiState.Connected
    val buttonEnabled = !connBusy && !testBusy

    val subOk = subState.expiresAt > (System.currentTimeMillis() / 1000 + subState.serverTimeOffset)
    var promoExpanded by remember { mutableStateOf(!subOk) }

    // Live countdown for short subscriptions (< 1 day)
    var nowSec by remember { mutableStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(Unit) {
        while (true) {
            nowSec = System.currentTimeMillis() / 1000 + subState.serverTimeOffset
            kotlinx.coroutines.delay(1000)
        }
    }
    val remainingSec = if (subState.expiresAt > 0) (subState.expiresAt - nowSec).coerceAtLeast(0) else 0
    val isShortSub = subOk && remainingSec in 1..86399 // < 1 day

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDeep)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onMenuClick) {
                Icon(Icons.Default.Menu, contentDescription = "Меню", tint = TextSecondary)
            }
            Text(
                text = "VPN",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
            )
        }

        Spacer(Modifier.height(16.dp))

        // Connection status indicator
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .background(CardBlue)
                .padding(horizontal = 20.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (connBusy || testBusy) {
                    Canvas(Modifier.size(14.dp)) {
                        drawArc(
                            color = statusColor,
                            startAngle = spinAngle,
                            sweepAngle = 280f,
                            useCenter = false,
                            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
                        )
                    }
                } else {
                    Box(
                        Modifier
                            .size(12.dp)
                            .shadow(6.dp, CircleShape)
                            .clip(CircleShape)
                            .background(statusColor)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    text = status.label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                )
            }
        }

        if (state.permissionNeeded) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Подтвердите разрешение системы на VPN",
                style = MaterialTheme.typography.bodySmall,
                color = AccentSoft,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(Modifier.height(20.dp))

        // Subscription card (compact)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(CardBlue)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(10.dp).clip(CircleShape)
                        .background(if (subOk) StateGreen else StateRed)
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = if (subOk) "Подписка активна" else "Нет активной подписки",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (subOk) StateGreen else StateRed,
                    )
                    if (subOk) {
                        val displayText = if (isShortSub) {
                            val h = remainingSec / 3600
                            val m = (remainingSec % 3600) / 60
                            val s = remainingSec % 60
                            if (h > 0) "Осталось %02d:%02d:%02d".format(h, m, s)
                            else "Осталось %02d:%02d".format(m, s)
                        } else {
                            "Осталось ${subState.daysLeft} дн." +
                                (if (subState.expiresAt > 0) " · до ${java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.getDefault()).format(java.util.Date(subState.expiresAt * 1000))}" else "")
                        }
                        Text(
                            text = displayText,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                        )
                    }
                }
                IconButton(onClick = onRefreshSub, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "Обновить",
                        tint = TextSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // Two large round buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Left: Тестирование
            CircleActionButton(
                label = "Тестирование",
                modifier = Modifier.scale(testScale),
                container = CardRaised,
                ring = AccentSoft,
                contentTint = AccentSoft,
                glowAlpha = if (testBusy) glow else 0f,
                enabled = buttonEnabled && state.vpnState != VpnUiState.Connecting,
                onClick = {
                    testPressed = true
                    onTest()
                },
                onPressChange = { testPressed = it },
                busy = testBusy,
            ) { tint ->
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(40.dp),
                )
            }

            // Right: Подключиться / Отключиться
            CircleActionButton(
                label = if (connected) "Отключиться" else "Подключиться",
                modifier = Modifier.scale(connScale),
                container = if (connected) Color(0xFF0EA5E9) else AccentBlue,
                ring = AccentSoft,
                contentTint = if (connected) Color(0xFF0C1728) else Color(0xFF0C1728),
                glowAlpha = if (connected) glow else if (connBusy) glow else 0f,
                enabled = buttonEnabled || (connected && !connBusy),
                onClick = {
                    connPressed = true
                    if (connected) onDisconnect() else onConnect()
                },
                onPressChange = { connPressed = it },
                busy = connBusy,
            ) { tint ->
                if (connBusy) {
                    Canvas(Modifier.size(36.dp)) {
                        drawArc(
                            color = tint,
                            startAngle = spinAngle,
                            sweepAngle = 300f,
                            useCenter = false,
                            style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round),
                        )
                    }
                } else {
                    PowerIcon(tint = tint, modifier = Modifier.size(42.dp))
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Test / connection message
        val message = when {
            state.testing -> null
            state.testSuccess == true -> buildString {
                append("Соединение доступно")
                state.testLatencyMs?.let { append(" · $it мс") }
            }
            state.testSuccess == false -> "Не удалось подключиться к серверу"
            state.vpnState == VpnUiState.Connected -> "Трафик идёт через защищённый канал"
            state.vpnState == VpnUiState.Error -> "Ошибка соединения"
            else -> null
        }
        message?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = when {
                    state.testSuccess == true || state.vpnState == VpnUiState.Connected -> AccentSoft
                    state.testSuccess == false || state.vpnState == VpnUiState.Error -> StateRed
                    else -> TextSecondary
                },
                textAlign = TextAlign.Center,
            )
        }

        Spacer(Modifier.height(16.dp))

        // Diagnostics button (always visible)
        OutlinedButton(
            onClick = onDiagnostics,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentSoft),
            border = ButtonDefaults.outlinedButtonBorder.copy(
                brush = androidx.compose.ui.graphics.SolidColor(AccentSoft.copy(alpha = 0.5f)),
            ),
        ) {
            Text("Диагностика VPN", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }

        Spacer(Modifier.height(8.dp))

        // Split tunneling button
        OutlinedButton(
            onClick = onSplitTunnel,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentSoft),
            border = ButtonDefaults.outlinedButtonBorder.copy(
                brush = androidx.compose.ui.graphics.SolidColor(AccentSoft.copy(alpha = 0.5f)),
            ),
        ) {
            Text("Раздельное туннелирование", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }

        Spacer(Modifier.height(16.dp))

        // Promo code section (expandable)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(CardBlue),
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { promoExpanded = !promoExpanded },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Активировать промокод",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        imageVector = if (promoExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        tint = TextSecondary,
                    )
                }

                if (promoExpanded) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = subState.promoCode,
                        onValueChange = onPromoChange,
                        label = { Text("Промокод") },
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedContainerColor = CardRaised,
                            unfocusedContainerColor = CardRaised,
                            focusedIndicatorColor = AccentBlue,
                            unfocusedIndicatorColor = StateGray,
                            focusedLabelColor = AccentSoft,
                            unfocusedLabelColor = TextSecondary,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = onActivatePromo,
                        enabled = !subState.activating && subState.promoCode.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AccentBlue,
                            contentColor = Color(0xFF0C1728),
                            disabledContainerColor = CardRaised,
                            disabledContentColor = StateGray,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (subState.activating) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color(0xFF0C1728))
                            Spacer(Modifier.width(8.dp))
                            Text("Активация…")
                        } else {
                            Text("Активировать")
                        }
                    }
                    subState.message?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = it,
                            color = if (it.contains("Не удалось") || it.contains("Нет ")) StateRed else StateGreen,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Text(
            text = "Без ввода адресов и паролей — всё уже настроено",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Circular action button with press-scale and optional glow ring.
 */
@Composable
private fun CircleActionButton(
    label: String,
    modifier: Modifier = Modifier,
    container: Color,
    ring: Color,
    contentTint: Color,
    glowAlpha: Float,
    enabled: Boolean,
    busy: Boolean,
    onClick: () -> Unit,
    onPressChange: (Boolean) -> Unit,
    icon: @Composable (Color) -> Unit,
) {
    val glow by animateFloatAsState(glowAlpha, label = "btnGlow")
    val bgColor by animateColorAsState(
        if (enabled) container else container.copy(alpha = 0.45f),
        label = "btnBg",
    )
    val tint by animateColorAsState(
        if (enabled) contentTint else contentTint.copy(alpha = 0.5f),
        label = "btnTint",
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(112.dp)
                .shadow(
                    elevation = if (glow > 0.05f) (10 * glow).dp else 0.dp,
                    shape = CircleShape,
                    ambientColor = ring.copy(alpha = glow),
                    spotColor = ring.copy(alpha = glow),
                )
                .clip(CircleShape)
                .background(bgColor)
                .then(
                    if (glow > 0.05f) {
                        Modifier.shadow(0.dp, shape = CircleShape)
                    } else {
                        Modifier
                    }
                )
                .pointerInput(enabled) {
                    detectTapGestures(
                        onPress = {
                            if (enabled) {
                                onPressChange(true)
                                tryAwaitRelease()
                                onPressChange(false)
                            }
                        },
                        onTap = {
                            if (enabled && !busy) onClick()
                        },
                    )
                },
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(3.dp)
                    .clip(CircleShape)
                    .background(Color.Transparent),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    drawCircle(
                        color = ring.copy(alpha = 0.35f + 0.45f * glow),
                        radius = size.minDimension / 2f - 1.dp.toPx(),
                        style = Stroke(width = 1.5.dp.toPx()),
                    )
                }
                icon(tint)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) TextPrimary else TextSecondary,
            textAlign = TextAlign.Center,
            fontSize = 14.sp,
            maxLines = 1,
        )
    }
}

/** Minimal power glyph (circle + vertical bar). */
@Composable
private fun PowerIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.09f
        drawArc(
            color = tint,
            startAngle = -55f,
            sweepAngle = 290f,
            useCenter = false,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
            topLeft = Offset(stroke / 2f, stroke / 2f),
            size = androidx.compose.ui.geometry.Size(w - stroke, h - stroke),
        )
        drawLine(
            color = tint,
            start = Offset(w / 2f, h * 0.12f),
            end = Offset(w / 2f, h * 0.52f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}
