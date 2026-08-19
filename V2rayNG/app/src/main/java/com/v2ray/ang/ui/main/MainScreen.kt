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
    var showSearch by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showDelAllConfirm by remember { mutableStateOf(false) }
    var showDelDuplicateConfirm by remember { mutableStateOf(false) }
    var showDelInvalidConfirm by remember { mutableStateOf(false) }
    var showRemoveConfirm by remember { mutableStateOf<String?>(null) }

    var showInstructionBanner by remember { mutableStateOf(false) }
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
        Scaffold(
            containerColor = Color(0xFF090B1E),
            contentWindowInsets = ScaffoldDefaults.contentWindowInsets,
            topBar = {
                MainTopBar(
                    isLoading = isLoading,
                    showSearch = showSearch,
                    searchQuery = searchQuery,
                    onSearchQueryChange = { query: String ->
                        searchQuery = query
                        onAction(MainAction.Search(query))
                    },
                    onSearchClose = {
                        searchQuery = ""
                        onAction(MainAction.Search(""))
                        showSearch = false
                    },
                    onSearchToggle = { show: Boolean -> showSearch = show },
                    onMenuClick = { scope.launch { drawerState.open() } },
                    onAction = onAction,
                    onDelAllConfig = { showDelAllConfirm = true },
                    onDelDuplicateConfig = { showDelDuplicateConfirm = true },
                    onDelInvalidConfig = { showDelInvalidConfirm = true }
                )
            }
        ) { innerPadding ->
            if (groups.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color(0xFF0D1029),
                                    Color(0xFF080915),
                                    Color(0xFF05060C)
                                )
                            )
                        )
                        .verticalScroll(
                            state = scrollState,
                            flingBehavior = smoothFlingBehavior
                        )
                ) {
                    if (groups.size > 1) {
                        GroupTabBar(
                            groups = groups,
                            selectedTabIndex = pagerState.currentPage.coerceIn(0, groups.lastIndex),
                            mainViewModel = mainViewModel,
                            onTabClick = { targetIndex ->
                                scope.launch {
                                    pagerState.animateScrollToPage(targetIndex)
                                }
                            }
                        )
                    }

                    // --- КНОПКА ПИТАНИЯ EASYGO STYLE ---
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 28.dp, bottom = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CyberPowerButton(
                            isRunning = isRunning,
                            onClick = { onAction(MainAction.ToggleService) }
                        )

                        Spacer(modifier = Modifier.height(18.dp))

                        // ПАНЕЛЬ БЫСТРЫХ ДЕЙСТВИЙ (ВСТАВИТЬ / QR / ГИД)
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CustomActionButton(
                                text = "📋 Из буфера",
                                accentColor = Color(0xFF3861FB),
                                onClick = { onAction(MainAction.ImportClipboard) }
                            )

                            CustomActionButton(
                                text = "📷 QR-код",
                                accentColor = Color(0xFF6C5CE7),
                                onClick = { onAction(MainAction.ImportQRcode) }
                            )

                            CustomActionButton(
                                text = if (showInstructionBanner) "📖 Скрыть" else "📖 Инструкция",
                                accentColor = Color(0xFF00D2D3),
                                onClick = { showInstructionBanner = !showInstructionBanner }
                            )
                        }
                    }

                    // --- БАННЕР ИНСТРУКЦИИ ---
                    AnimatedVisibility(visible = showInstructionBanner) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp)
                                .background(Color(0xFF141733), RoundedCornerShape(20.dp))
                                .border(1.dp, Color(0xFF272C5A), RoundedCornerShape(20.dp))
                                .padding(16.dp)
                        ) {
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "🚀 Быстрый старт OneTap Mobile",
                                        color = Color(0xFF00D2D3),
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Box(
                                        modifier = Modifier
                                            .size(24.dp)
                                            .clickable { showInstructionBanner = false },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Canvas(modifier = Modifier.size(12.dp)) {
                                            drawLine(
                                                color = Color.Gray,
                                                start = Offset(0f, 0f),
                                                end = Offset(size.width, size.height),
                                                strokeWidth = 3f,
                                                cap = StrokeCap.Round
                                            )
                                            drawLine(
                                                color = Color.Gray,
                                                start = Offset(size.width, 0f),
                                                end = Offset(0f, size.height),
                                                strokeWidth = 3f,
                                                cap = StrokeCap.Round
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                InlineInstructionStep("1", "Скопируйте полученный ключ VLESS или PIN-код.")
                                InlineInstructionStep("2", "Нажмите кнопку «📋 Из буфера» для моментального добавления.")
                                InlineInstructionStep("3", "Нажмите на центральную кнопку питания для активации.")
                            }
                        }
                    }

                    // --- КАРТОЧНЫЙ СПИСОК СЕРВЕРОВ (EASYGO STYLE) ---
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(540.dp)
                            .clip(RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp))
                            .background(Color(0xFF131738))
                            .border(
                                width = 1.dp,
                                color = Color(0xFF242A5C),
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
                                searchQuery = searchQuery,
                                lazyListStates = lazyListStates,
                                lazyGridStates = lazyGridStates,
                                onSelectServer = { guid -> onAction(MainAction.SelectServer(guid)) },
                                onEditServer = { guid, profile -> onAction(MainAction.EditServer(guid, profile)) },
                                onShareServer = { guid, profile ->
                                    shareTarget = Triple(guid, profile, false)
                                },
                                onMoreServer = { guid, profile ->
                                    shareTarget = Triple(guid, profile, true)
                                },
                                onRemoveServer = { guid ->
                                    if (confirmRemove) showRemoveConfirm = guid
                                    else onAction(MainAction.RemoveServer(guid))
                                },
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CustomActionButton(
    text: String,
    accentColor: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF171B3E))
            .border(1.dp, accentColor.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun InlineInstructionStep(number: String, text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .padding(top = 2.dp)
                .size(18.dp)
                .background(Color(0xFF3861FB), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = number,
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            color = Color(0xFFC7CCE6),
            fontSize = 12.sp,
            lineHeight = 16.sp
        )
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
            animation = tween(1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    val activeColor = Color(0xFF3861FB)
    val idleColor = Color(0xFF282D5C)
    val glowColor = if (isRunning) Color(0xFF3861FB) else Color(0xFF1B1E3D)

    val buttonGlowAlpha by animateFloatAsState(
        targetValue = if (isRunning) 0.6f else 0.25f,
        animationSpec = tween(500),
        label = "buttonGlowAlpha"
    )

    Box(
        modifier = Modifier.size(240.dp),
        contentAlignment = Alignment.Center
    ) {
        // Внешний неоновый ореол (EasyGo Glow)
        Box(
            modifier = Modifier
                .size(240.dp)
                .scale(if (isRunning) pulseScale else 1f)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            glowColor.copy(alpha = buttonGlowAlpha),
                            glowColor.copy(alpha = buttonGlowAlpha * 0.3f),
                            Color.Transparent
                        )
                    )
                )
        )

        // Внешнее кольцо
        Box(
            modifier = Modifier
                .size(190.dp)
                .clip(CircleShape)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color(0xFF232854), Color(0xFF131633))
                    )
                )
                .border(
                    width = 2.dp,
                    color = if (isRunning) activeColor.copy(alpha = 0.6f) else Color(0xFF272C59),
                    shape = CircleShape
                )
        )

        // Центральная кнопка включения
        Box(
            modifier = Modifier
                .size(135.dp)
                .clip(CircleShape)
                .background(
                    Brush.verticalGradient(
                        colors = if (isRunning) listOf(Color(0xFF3861FB), Color(0xFF1F3BB3))
                        else listOf(Color(0xFF1A1D3B), Color(0xFF0F1124))
                    )
                )
                .border(
                    width = 2.dp,
                    color = if (isRunning) Color(0xFF6C8CFF) else Color(0xFF2C3260),
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
                targetValue = if (isRunning) Color.White else Color(0xFF646B96),
                animationSpec = tween(300),
                label = "iconColor"
            )

            Canvas(modifier = Modifier.size(46.dp)) {
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
