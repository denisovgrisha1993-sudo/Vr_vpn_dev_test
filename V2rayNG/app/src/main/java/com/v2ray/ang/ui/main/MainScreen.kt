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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
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
            containerColor = Color(0xFF10132B),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp)
            ) {
                Text(
                    text = "🚀 Инструкция OneTap Mobile",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(16.dp))
                InstructionItem("1", "Скопируйте ключ или PIN из бота в Telegram.")
                InstructionItem("2", "Нажмите кнопку «📋 Из буфера» на главном экране.")
                InstructionItem("3", "Нажмите на большую кнопку питания в центре для подключения.")
                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = { showInstructionSheet = false },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3861FB)),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("Понятно", color = Color.White, fontWeight = FontWeight.Bold)
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
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF0A0C20),
                            Color(0xFF060714),
                            Color(0xFF04050D)
                        )
                    )
                )
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Премиум Header вместо серого TopBar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { scope.launch { drawerState.open() } },
                        modifier = Modifier
                            .size(42.dp)
                            .background(Color(0xFF151838), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Menu,
                            contentDescription = "Menu",
                            tint = Color.White
                        )
                    }

                    Text(
                        text = "OneTap Mobile",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )

                    IconButton(
                        onClick = { onNavigate("settings") },
                        modifier = Modifier
                            .size(42.dp)
                            .background(Color(0xFF151838), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Settings,
                            contentDescription = "Settings",
                            tint = Color.White
                        )
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

                    // Центральная неоновая кнопка
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp, bottom = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CyberPowerButton(
                            isRunning = isRunning,
                            onClick = { onAction(MainAction.ToggleService) }
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        Text(
                            text = if (isRunning) "ПОДКЛЮЧЕНО" else "ОТКЛЮЧЕНО",
                            color = if (isRunning) Color(0xFF3861FB) else Color(0xFF7A83A6),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 2.sp
                        )

                        Spacer(modifier = Modifier.height(20.dp))

                        // Компактные кнопки управления
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ModernPillButton(
                                text = "📋 Вставить ключ",
                                accentColor = Color(0xFF3861FB),
                                onClick = { onAction(MainAction.ImportClipboard) }
                            )

                            ModernPillButton(
                                text = "📖 Инструкция",
                                accentColor = Color(0xFF5E6AD2),
                                onClick = { showInstructionSheet = true }
                            )
                        }
                    }

                    // Карточка списка серверов
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .heightIn(min = 450.dp)
                            .clip(RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp))
                            .background(Color(0xFF10132B))
                            .border(
                                width = 1.dp,
                                color = Color(0xFF1D224D),
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
private fun ModernPillButton(
    text: String,
    accentColor: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF151838))
            .border(1.dp, accentColor.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
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
                .background(Color(0xFF3861FB), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(text = num, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(text = desc, color = Color(0xFFC3C8E2), fontSize = 13.sp, lineHeight = 18.sp)
    }
}

@Composable
fun CyberPowerButton(
    isRunning: Boolean,
    onClick: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse_anim")

    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.98f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    val activeColor = Color(0xFF3861FB)
    val glowColor = if (isRunning) Color(0xFF3861FB) else Color(0xFF1E234D)

    val glowAlpha by animateFloatAsState(
        targetValue = if (isRunning) 0.65f else 0.2f,
        animationSpec = tween(500),
        label = "glowAlpha"
    )

    Box(
        modifier = Modifier.size(230.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(230.dp)
                .scale(if (isRunning) pulseScale else 1f)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            glowColor.copy(alpha = glowAlpha),
                            glowColor.copy(alpha = glowAlpha * 0.25f),
                            Color.Transparent
                        )
                    )
                )
        )

        Box(
            modifier = Modifier
                .size(180.dp)
                .clip(CircleShape)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color(0xFF1E234F), Color(0xFF10132B))
                    )
                )
                .border(
                    width = 2.dp,
                    color = if (isRunning) activeColor.copy(alpha = 0.8f) else Color(0xFF282F66),
                    shape = CircleShape
                )
        )

        Box(
            modifier = Modifier
                .size(130.dp)
                .clip(CircleShape)
                .background(
                    Brush.verticalGradient(
                        colors = if (isRunning) listOf(Color(0xFF3861FB), Color(0xFF1C3DBF))
                        else listOf(Color(0xFF161938), Color(0xFF0E1024))
                    )
                )
                .border(
                    width = 2.dp,
                    color = if (isRunning) Color(0xFF7593FF) else Color(0xFF2D356E),
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
                targetValue = if (isRunning) Color.White else Color(0xFF7B84AC),
                animationSpec = tween(300),
                label = "iconColor"
            )

            Canvas(modifier = Modifier.size(44.dp)) {
                drawArc(
                    color = iconColor,
                    startAngle = -60f,
                    sweepAngle = 300f,
                    useCenter = false,
                    style = Stroke(width = 7f, cap = StrokeCap.Round)
                )
                drawLine(
                    color = iconColor,
                    start = Offset(size.width / 2, 0f),
                    end = Offset(size.width / 2, size.height / 2.3f),
                    strokeWidth = 7f,
                    cap = StrokeCap.Round
                )
            }
        }
    }
}
