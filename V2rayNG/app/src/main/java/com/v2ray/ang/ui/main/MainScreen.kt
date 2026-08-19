package com.v2ray.ang.ui.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.compose.QRCodeDialog
import com.v2ray.ang.dto.entities.ProfileItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

fun formatServerRemarks(raw: String): String {
    if (raw.isBlank()) return "OneTap Fast Node"
    var name = raw
    val tgIndex = name.indexOf("-tg_")
    if (tgIndex != -1) name = name.substring(0, tgIndex)
    val tgAltIndex = name.indexOf("_tg_")
    if (tgAltIndex != -1) name = name.substring(0, tgAltIndex)

    name = name.replace("OneTap-Mobile-", "")
               .replace("OneTap-", "")
               .replace("-VLESS", "")
               .replace("_VLESS", "")
               .trim()

    return if (name.isBlank()) "OneTap Fast Node" else name
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    mainViewModel: MainViewModel,
    onAction: (MainAction) -> Unit,
    onNavigate: (String) -> Unit,
    shareMethodEntries: List<String>,
    shareMethodMoreEntries: List<String>
) {
    val uiState by mainViewModel.uiState.collectAsStateWithLifecycle()
    val groups = uiState.groups
    val isLoading by mainViewModel.isLoading.collectAsStateWithLifecycle()
    val isRunning = uiState.isRunning
    val selectedGuid = uiState.selectedGuid
    val doubleColumnDisplay = uiState.doubleColumnDisplay
    val confirmRemove = uiState.confirmRemove
    val shareQRCodeBitmap = uiState.shareQRCodeBitmap

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var showDelAllConfirm by remember { mutableStateOf(false) }
    var showDelDuplicateConfirm by remember { mutableStateOf(false) }
    var showDelInvalidConfirm by remember { mutableStateOf(false) }
    var showRemoveConfirm by remember { mutableStateOf<String?>(null) }

    var showInstructionSheet by remember { mutableStateOf(false) }
    var shareTarget by remember { mutableStateOf<Triple<String, ProfileItem, Boolean>?>(null) }

    val pagerState = rememberPagerState(
        initialPage = 0,
        pageCount = { groups.size.coerceAtLeast(1) }
    )

    val lazyListStates = remember { mutableStateMapOf<String, LazyListState>() }
    val lazyGridStates = remember { mutableStateMapOf<String, LazyGridState>() }

    var locateInProgress by remember { mutableStateOf(false) }

    val scrollState = rememberScrollState()
    val smoothFlingBehavior = ScrollableDefaults.flingBehavior()

    LaunchedEffect(groups) {
        val validGroupIds = groups.map { it.id }.toSet()
        lazyListStates.keys.retainAll(validGroupIds)
        lazyGridStates.keys.retainAll(validGroupIds)
    }

    val latestDoubleColumnDisplay by rememberUpdatedState(doubleColumnDisplay)

    LaunchedEffect(groups, uiState.selectedGroupId) {
        if (groups.isEmpty()) return@LaunchedEffect
        val selectedIndex = groups.indexOfFirst { it.id == uiState.selectedGroupId }
            .takeIf { it >= 0 } ?: 0
        if (!pagerState.isScrollInProgress && pagerState.settledPage != selectedIndex) {
            pagerState.scrollToPage(selectedIndex)
        }
    }

    val latestGroups by rememberUpdatedState(groups)
    val latestLocateInProgress by rememberUpdatedState(locateInProgress)

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect { page ->
                val currentGroups = latestGroups
                if (!latestLocateInProgress && page in currentGroups.indices) {
                    onAction(MainAction.SelectGroup(currentGroups[page].id))
                }
            }
    }

    LaunchedEffect(uiState.locateTarget) {
        val target = uiState.locateTarget ?: return@LaunchedEffect
        if (target.groupIndex !in 0 until pagerState.pageCount) {
            mainViewModel.onAction(MainAction.LocateHandled(target))
            return@LaunchedEffect
        }

        locateInProgress = true
        try {
            if (pagerState.settledPage != target.groupIndex) {
                pagerState.scrollToPage(target.groupIndex)
            }
            onAction(MainAction.SelectGroup(target.groupId))

            repeat(10) {
                val ready = if (latestDoubleColumnDisplay) {
                    lazyGridStates[target.groupId] != null
                } else {
                    lazyListStates[target.groupId] != null
                }
                if (ready) return@repeat
                delay(16L)
            }

            if (latestDoubleColumnDisplay) {
                lazyGridStates[target.groupId]?.let { gridState ->
                    gridState.scrollToItem(
                        index = target.itemPosition,
                        scrollOffset = -gridState.layoutInfo.viewportSize.height / 3
                    )
                }
            } else {
                lazyListStates[target.groupId]?.let { listState ->
                    listState.scrollToItem(
                        index = target.itemPosition,
                        scrollOffset = -listState.layoutInfo.viewportSize.height / 3
                    )
                }
            }
        } finally {
            delay(32L)
            locateInProgress = false
            mainViewModel.onAction(MainAction.LocateHandled(target))
        }
    }

    MainDialogs(
        showDelAllConfirm = showDelAllConfirm,
        onDismissDelAll = { showDelAllConfirm = false },
        onConfirmDelAll = { showDelAllConfirm = false; onAction(MainAction.RemoveAllServers) },
        showDelDuplicateConfirm = showDelDuplicateConfirm,
        onDismissDelDuplicate = { showDelDuplicateConfirm = false },
        onConfirmDelDuplicate = { showDelDuplicateConfirm = false; onAction(MainAction.RemoveDuplicateServers) },
        showDelInvalidConfirm = showDelInvalidConfirm,
        onDismissDelInvalid = { showDelInvalidConfirm = false },
        onConfirmDelInvalid = { showDelInvalidConfirm = false; onAction(MainAction.RemoveInvalidServers) },
        showRemoveConfirm = showRemoveConfirm,
        onDismissRemove = { showRemoveConfirm = null },
        onConfirmRemove = { guid -> showRemoveConfirm = null; onAction(MainAction.RemoveServer(guid)) }
    )

    if (shareTarget != null) {
        val (guid, profile, more) = shareTarget!!
        ShareMethodDialog(
            guid = guid,
            profile = profile,
            more = more,
            shareMethodEntries = shareMethodEntries,
            shareMethodMoreEntries = shareMethodMoreEntries,
            onDismiss = { shareTarget = null },
            onAction = onAction
        )
    }
    if (shareQRCodeBitmap != null) {
        QRCodeDialog(bitmap = shareQRCodeBitmap, onDismiss = { onAction(MainAction.DismissQRCodeDialog) })
    }

    if (showInstructionSheet) {
        ModalBottomSheet(
            onDismissRequest = { showInstructionSheet = false },
            containerColor = Color(0xFF131622),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp)
            ) {
                Text(
                    text = "Быстрый старт OneTap Mobile",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(16.dp))
                InstructionItem("1", "Скопируйте ключ или PIN из Telegram-бота.")
                InstructionItem("2", "Нажмите кнопку «Вставить ключ» на панели быстрого доступа.")
                InstructionItem("3", "Нажмите центральный OneTap-переключатель для активации защиты.")
                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = { showInstructionSheet = false },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00F5A0)),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("Готово", color = Color(0xFF090A0F), fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            MainDrawerContent(
                onNavigate = { route ->
                    scope.launch { drawerState.close() }
                    onNavigate(route)
                }
            )
        }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF090A0F))
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF131622))
                                .border(1.dp, Color(0x1AFFFFFF), CircleShape)
                                .clickable { scope.launch { drawerState.open() } },
                            contentAlignment = Alignment.Center
                        ) {
                            Canvas(modifier = Modifier.size(16.dp)) {
                                val stroke = 2.dp.toPx()
                                drawLine(Color.White, Offset(0f, 3.dp.toPx()), Offset(size.width, 3.dp.toPx()), strokeWidth = stroke, cap = StrokeCap.Round)
                                drawLine(Color.White, Offset(0f, 8.dp.toPx()), Offset(size.width, 8.dp.toPx()), strokeWidth = stroke, cap = StrokeCap.Round)
                                drawLine(Color.White, Offset(0f, 13.dp.toPx()), Offset(size.width, 13.dp.toPx()), strokeWidth = stroke, cap = StrokeCap.Round)
                            }
                        }

                        Column {
                            Text(
                                text = "OneTap",
                                color = Color.White,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp
                            )
                            Text(
                                text = "VLESS REALITY",
                                color = Color(0xFF00D2FF),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF131622))
                                .border(1.dp, Color(0x14FFFFFF), RoundedCornerShape(12.dp))
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .background(if (isRunning) Color(0xFF00F5A0) else Color(0xFF6B7280), CircleShape)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "PRO",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF131622))
                                .border(1.dp, Color(0x1AFFFFFF), CircleShape)
                                .clickable { onNavigate("settings") },
                            contentAlignment = Alignment.Center
                        ) {
                            Canvas(modifier = Modifier.size(16.dp)) {
                                drawCircle(color = Color.White, radius = 3.5.dp.toPx(), style = Stroke(width = 2.dp.toPx()))
                                drawCircle(color = Color.White, radius = 7.dp.toPx(), style = Stroke(width = 1.5.dp.toPx()))
                            }
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(state = scrollState, flingBehavior = smoothFlingBehavior)
                ) {
                    if (groups.size > 1) {
                        GroupTabBar(
                            groups = groups,
                            selectedTabIndex = pagerState.currentPage.coerceIn(0, groups.lastIndex),
                            mainViewModel = mainViewModel,
                            onTabClick = { targetIndex ->
                                scope.launch { pagerState.animateScrollToPage(targetIndex) }
                            }
                        )
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 20.dp, bottom = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CyberPowerButton(
                            isRunning = isRunning,
                            isLoading = isLoading,
                            onClick = { onAction(MainAction.ToggleService) }
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = if (isRunning) "PROTECTED & SECURE" else "TAP TO CONNECT",
                            color = if (isRunning) Color(0xFF00F5A0) else Color(0xFF8F9CAE),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 2.sp
                        )

                        Spacer(modifier = Modifier.height(18.dp))

                        LiveTelemetryCard(
                            isRunning = isRunning,
                            ping = uiState.currentRealPing,
                            onTestPing = { onAction(MainAction.TestCurrentServer) }
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ModernPillButton(
                                text = "Вставить ключ",
                                iconType = IconType.CLIPBOARD,
                                accentColor = Color(0xFF00D2FF),
                                onClick = { onAction(MainAction.ImportClipboard) }
                            )

                            ModernPillButton(
                                text = "Инструкция",
                                iconType = IconType.BOOK,
                                accentColor = Color(0xFF8F9CAE),
                                onClick = { showInstructionSheet = true }
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .heightIn(min = 450.dp)
                            .clip(RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp))
                            .background(Color(0xFF131622))
                            .border(
                                width = 1.dp,
                                color = Color(0x14FFFFFF),
                                shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
                            )
                    ) {
                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier.fillMaxSize(),
                            userScrollEnabled = true,
                            beyondViewportPageCount = 1,
                            key = { page -> groups.getOrNull(page)?.id ?: "group-page-$page" }
                        ) { page ->
                            val group = groups.getOrNull(page) ?: return@HorizontalPager

                            GroupPagerPage(
                                groupId = group.id,
                                mainViewModel = mainViewModel,
                                selectedGuid = selectedGuid,
                                doubleColumnDisplay = doubleColumnDisplay,
                                confirmRemove = confirmRemove,
                                searchQuery = "",
                                lazyListStates = lazyListStates,
                                lazyGridStates = lazyGridStates,
                                onSelectServer = { guid -> onAction(MainAction.SelectServer(guid)) },
                                onEditServer = { guid, profile -> onAction(MainAction.EditServer(guid, profile)) },
                                onShareServer = { guid, profile -> shareTarget = Triple(guid, profile, false) },
                                onMoreServer = { guid, profile -> shareTarget = Triple(guid, profile, true) },
                                onRemoveServer = { guid ->
                                    if (confirmRemove) showRemoveConfirm = guid
                                    else onAction(MainAction.RemoveServer(guid))
                                },
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveTelemetryCard(
    isRunning: Boolean,
    ping: Long,
    onTestPing: () -> Unit
) {
    val pingText = when {
        !isRunning -> "-- ms"
        ping == 0L -> "Тест..."
        ping < 0L -> "Тап для замера"
        ping >= 9999L -> "Таймаут"
        else -> "$ping ms"
    }

    val pingColor = when {
        !isRunning || ping <= 0L -> Color(0xFF6B7280)
        ping < 120L -> Color(0xFF00F5A0)
        ping < 250L -> Color(0xFFFFCC00)
        else -> Color(0xFFFF3B30)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF131622))
            .border(1.dp, Color(0x14FFFFFF), RoundedCornerShape(18.dp))
            .clickable(enabled = isRunning, onClick = onTestPing)
            .padding(vertical = 12.dp, horizontal = 16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(pingColor, CircleShape)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = pingText,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "LATENCY (TAP)",
                        color = Color(0xFF8F9CAE),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(24.dp)
                    .background(Color(0x14FFFFFF))
            )

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = if (isRunning) "TLS 1.3" else "DIRECT",
                    color = if (isRunning) Color(0xFF00D2FF) else Color(0xFF6B7280),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "SECURITY",
                    color = Color(0xFF8F9CAE),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(24.dp)
                    .background(Color(0x14FFFFFF))
            )

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = if (isRunning) "ONLINE" else "IDLE",
                    color = if (isRunning) Color(0xFF00F5A0) else Color(0xFF6B7280),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "STATUS",
                    color = Color(0xFF8F9CAE),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

enum class IconType { CLIPBOARD, BOOK }

@Composable
private fun ModernPillButton(
    text: String,
    iconType: IconType,
    accentColor: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF131622))
            .border(1.dp, accentColor.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Canvas(modifier = Modifier.size(14.dp)) {
                val strokePx = 1.6.dp.toPx()
                when (iconType) {
                    IconType.CLIPBOARD -> {
                        drawRoundRect(
                            color = Color.White,
                            topLeft = Offset(1.5.dp.toPx(), 2.5.dp.toPx()),
                            size = Size(11.dp.toPx(), 11.dp.toPx()),
                            cornerRadius = CornerRadius(2.dp.toPx()),
                            style = Stroke(width = strokePx)
                        )
                        drawLine(
                            color = Color.White,
                            start = Offset(4.5.dp.toPx(), 1.2.dp.toPx()),
                            end = Offset(9.5.dp.toPx(), 1.2.dp.toPx()),
                            strokeWidth = strokePx,
                            cap = StrokeCap.Round
                        )
                    }
                    IconType.BOOK -> {
                        drawArc(
                            color = Color.White,
                            startAngle = 180f,
                            sweepAngle = 180f,
                            useCenter = false,
                            topLeft = Offset(1.dp.toPx(), 3.dp.toPx()),
                            size = Size(12.dp.toPx(), 8.dp.toPx()),
                            style = Stroke(width = strokePx)
                        )
                        drawLine(
                            color = Color.White,
                            start = Offset(7.dp.toPx(), 3.dp.toPx()),
                            end = Offset(7.dp.toPx(), 12.dp.toPx()),
                            strokeWidth = strokePx,
                            cap = StrokeCap.Round
                        )
                    }
                }
            }

            Text(
                text = text,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun InstructionItem(num: String, desc: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .background(Color(0xFF00F5A0), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(text = num, color = Color(0xFF090A0F), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(text = desc, color = Color(0xFF8F9CAE), fontSize = 13.sp, lineHeight = 18.sp)
    }
}

@Composable
fun CyberPowerButton(
    isRunning: Boolean,
    isLoading: Boolean,
    onClick: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "cyber_switch")

    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.98f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    val spinAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "spinAngle"
    )

    val mintGlow = Color(0xFF00F5A0)
    val cyanGlow = Color(0xFF00D2FF)

    val glowAlpha by animateFloatAsState(
        targetValue = if (isRunning) 0.55f else 0.12f,
        animationSpec = tween(500),
        label = "glowAlpha"
    )

    Box(
        modifier = Modifier.size(210.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(210.dp)
                .scale(if (isRunning) pulseScale else 1f)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            if (isRunning) mintGlow.copy(alpha = glowAlpha) else Color(0x22131622),
                            if (isRunning) cyanGlow.copy(alpha = glowAlpha * 0.35f) else Color.Transparent,
                            Color.Transparent
                        )
                    )
                )
        )

        Canvas(
            modifier = Modifier
                .size(165.dp)
                .rotate(if (isLoading) spinAngle else 0f)
        ) {
            val ringColor = when {
                isLoading -> cyanGlow
                isRunning -> mintGlow
                else -> Color(0xFF1E2235)
            }
            drawArc(
                color = ringColor,
                startAngle = if (isLoading) 0f else -90f,
                sweepAngle = if (isLoading) 120f else 360f,
                useCenter = false,
                style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
            )
        }

        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape)
                .background(
                    Brush.verticalGradient(
                        colors = if (isRunning) listOf(Color(0xFF005A3E), Color(0xFF0B211C))
                        else listOf(Color(0xFF1A1E2E), Color(0xFF11141F))
                    )
                )
                .border(
                    width = 1.5.dp,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            if (isRunning) mintGlow else Color(0x33FFFFFF),
                            Color(0x05FFFFFF)
                        )
                    ),
                    shape = CircleShape
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick
                ),
            contentAlignment = Alignment.Center
        ) {
            val iconColor by animateColorAsState(
                targetValue = if (isRunning) Color(0xFF00F5A0) else Color(0xFF6B7280),
                animationSpec = tween(300),
                label = "iconColor"
            )

            Canvas(modifier = Modifier.size(42.dp)) {
                drawArc(
                    color = iconColor,
                    startAngle = -60f,
                    sweepAngle = 300f,
                    useCenter = false,
                    style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round)
                )
                drawLine(
                    color = iconColor,
                    start = Offset(size.width / 2, 0f),
                    end = Offset(size.width / 2, size.height / 2.2f),
                    strokeWidth = 6.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
        }
    }
}
