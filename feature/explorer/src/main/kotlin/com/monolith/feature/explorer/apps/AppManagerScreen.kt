package com.monolith.feature.explorer.apps

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.monolith.core.model.ThemeMode
import com.monolith.core.ui.components.CornerMenuItem
import com.monolith.core.ui.components.MonolithCornerMenuOverlay
import com.monolith.core.ui.components.MonolithDialog
import com.monolith.core.ui.components.MonolithRefreshIndicator
import com.monolith.core.ui.haptics.MonolithHaptics
import com.monolith.core.ui.theme.LocalMonolithButtonShape
import com.monolith.core.ui.theme.LocalMonolithThemeMode
import com.monolith.feature.explorer.MonolithToolDestination
import com.monolith.feature.explorer.MonolithToolStarMenu
import com.monolith.feature.explorer.storage.formatStorageBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AppManagerScreen(
    onNavigateToFiles: () -> Unit,
    onNavigateToArchives: () -> Unit,
    onNavigateToStorage: () -> Unit,
    onNavigateToDownloader: () -> Unit,
    onNavigateToAi: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenFolderInExplorer: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var starMenuExpanded by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var rootOrigin by remember { mutableStateOf(Offset.Zero) }
    var starAnchorCenter by remember { mutableStateOf(Offset.Unspecified) }
    var menuAnchorCenter by remember { mutableStateOf(Offset.Unspecified) }

    var allApps by remember { mutableStateOf<List<ManagedAppInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var reloadTrigger by remember { mutableIntStateOf(0) }
    var hasUsageStats by remember { mutableStateOf(AppManagerRepository.hasUsageStatsPermission(context)) }
    var usageBannerDismissed by rememberSaveable { mutableStateOf(false) }

    var filterTab by rememberSaveable { mutableStateOf(AppFilterTab.USER_INSTALLED) }
    var sortOrder by rememberSaveable { mutableStateOf(AppSortOrder.NAME) }
    var sortMenuExpanded by remember { mutableStateOf(false) }
    var searchActive by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }

    var selectedPackages by remember { mutableStateOf<Set<String>>(emptySet()) }
    val iconCache = remember { mutableStateMapOf<String, ImageBitmap?>() }

    // Detail sheet state
    var detailApp by remember { mutableStateOf<ManagedAppInfo?>(null) }
    var detailLoading by remember { mutableStateOf(false) }

    // Batch operation states
    var batchBusyLabel by remember { mutableStateOf<String?>(null) }
    var batchProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    // Uninstall sequential queue
    var showUninstallConfirm by remember { mutableStateOf<List<ManagedAppInfo>>(emptyList()) }
    var pendingUninstallQueue by remember { mutableStateOf<List<String>>(emptyList()) }

    // Cache cleanup warning dialog state
    var cacheWarningApps by remember { mutableStateOf<List<ManagedAppInfo>>(emptyList()) }

    // Batch audit report
    var auditReport by remember { mutableStateOf<BatchAuditReport?>(null) }

    val uninstallLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        val next = pendingUninstallQueue.drop(1)
        pendingUninstallQueue = next
        if (next.isEmpty()) {
            reloadTrigger++
        }
    }

    val systemClearCacheLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        reloadTrigger++
    }

    LaunchedEffect(pendingUninstallQueue) {
        val nextPkg = pendingUninstallQueue.firstOrNull() ?: return@LaunchedEffect
        val intent = Intent(AppManagerRepository.buildUninstallIntent(nextPkg)).apply {
            flags = flags and Intent.FLAG_ACTIVITY_NEW_TASK.inv()
        }
        runCatching {
            uninstallLauncher.launch(intent)
        }.onFailure {
            pendingUninstallQueue = pendingUninstallQueue.drop(1)
            snackbarHostState.showSnackbar("Deinstallation für $nextPkg fehlgeschlagen")
        }
    }

    fun startUninstallQueue(packages: List<String>) {
        if (packages.isEmpty()) return
        pendingUninstallQueue = packages
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val updatedUsage = AppManagerRepository.hasUsageStatsPermission(context)
                if (updatedUsage != hasUsageStats) {
                    hasUsageStats = updatedUsage
                }
                reloadTrigger++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(reloadTrigger) {
        isLoading = true
        hasUsageStats = AppManagerRepository.hasUsageStatsPermission(context)
        val loaded = runCatching { AppManagerRepository.loadInstalledApps(context) }
            .getOrElse { emptyList() }
        allApps = loaded
        val validPackages = loaded.mapTo(HashSet()) { it.packageName }
        selectedPackages = selectedPackages.intersect(validPackages)
        detailApp?.let { current ->
            val refreshed = loaded.firstOrNull { it.packageName == current.packageName }
            if (refreshed == null) {
                detailApp = null
            }
        }
        isLoading = false
    }

    val filteredApps = remember(allApps, filterTab, sortOrder, searchQuery) {
        AppManagerRepository.filterAndSortApps(
            apps = allApps,
            filter = filterTab,
            sort = sortOrder,
            query = searchQuery,
        )
    }

    val selectedApps = remember(allApps, selectedPackages) {
        allApps.filter { it.packageName in selectedPackages }
    }
    val selectedTotalBytes = remember(selectedApps) {
        selectedApps.sumOf { it.totalDisplayBytes }
    }

    fun runExportApps(appsToExport: List<ManagedAppInfo>) {
        if (appsToExport.isEmpty()) return
        scope.launch {
            val exportDir = AppManagerRepository.defaultExportDirectory()
            batchBusyLabel = "Exportiere Apps…"
            batchProgress = 0 to appsToExport.size
            var successCount = 0
            withContext(Dispatchers.IO) {
                appsToExport.forEachIndexed { index, app ->
                    val res = AppManagerRepository.exportApp(app, exportDir)
                    if (res.isSuccess) successCount++
                    withContext(Dispatchers.Main) {
                        batchProgress = (index + 1) to appsToExport.size
                    }
                }
            }
            batchBusyLabel = null
            batchProgress = null
            selectedPackages = emptySet()

            val msg = if (successCount == appsToExport.size) {
                "$successCount App(s) exportiert"
            } else {
                "$successCount von ${appsToExport.size} App(s) exportiert"
            }
            val result = snackbarHostState.showSnackbar(
                message = msg,
                actionLabel = "Ordner öffnen",
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) {
                onOpenFolderInExplorer(exportDir.absolutePath)
            }
        }
    }

    fun runTempCleanup(appsToClean: List<ManagedAppInfo>) {
        if (appsToClean.isEmpty()) return
        cacheWarningApps = appsToClean
    }

    fun runBatchAudit(appsToAudit: List<ManagedAppInfo>) {
        if (appsToAudit.isEmpty()) return
        scope.launch {
            batchBusyLabel = "Analysiere Berechtigungen…"
            batchProgress = null
            val report = AppManagerRepository.buildBatchAuditReport(context, appsToAudit)
            batchBusyLabel = null
            auditReport = report
        }
    }

    BackHandler(
        enabled = selectedPackages.isNotEmpty() || starMenuExpanded || menuExpanded || searchActive,
    ) {
        when {
            selectedPackages.isNotEmpty() -> selectedPackages = emptySet()
            starMenuExpanded -> starMenuExpanded = false
            menuExpanded -> menuExpanded = false
            searchActive -> {
                searchActive = false
                searchQuery = ""
            }
        }
    }

    val backgroundRecede by animateFloatAsState(
        targetValue = if (starMenuExpanded) 1f else 0f,
        label = "appsStarMenuRecede",
    )
    val cornerMenuRecede by animateFloatAsState(
        targetValue = if (menuExpanded) 1f else 0f,
        label = "appsCornerMenuRecede",
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { rootOrigin = it.positionInRoot() },
    ) {
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    when {
                        backgroundRecede > 0.01f -> Modifier
                            .blur(10.dp * backgroundRecede)
                            .graphicsLayer {
                                val s = 1f - 0.025f * backgroundRecede
                                scaleX = s
                                scaleY = s
                            }
                        cornerMenuRecede > 0.01f -> Modifier.blur(4.dp * cornerMenuRecede)
                        else -> Modifier
                    },
                ),
            containerColor = if (LocalMonolithThemeMode.current == ThemeMode.OBSIDIAN) {
                Color.Transparent
            } else {
                MaterialTheme.colorScheme.background
            },
            contentColor = MaterialTheme.colorScheme.onBackground,
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            topBar = {
                if (selectedPackages.isNotEmpty()) {
                    TopAppBar(
                        navigationIcon = {
                            IconButton(onClick = { selectedPackages = emptySet() }) {
                                Icon(Icons.Default.Close, contentDescription = "Auswahl aufheben")
                            }
                        },
                        title = {
                            Column {
                                Text(
                                    text = "${selectedPackages.size} ausgewählt",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = formatStorageBytes(selectedTotalBytes),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        },
                        actions = {
                            IconButton(
                                onClick = {
                                    val visiblePackages = filteredApps.mapTo(HashSet()) { it.packageName }
                                    selectedPackages = if (visiblePackages.isNotEmpty() && visiblePackages.all { it in selectedPackages }) {
                                        selectedPackages - visiblePackages
                                    } else {
                                        selectedPackages + visiblePackages
                                    }
                                },
                            ) {
                                Icon(Icons.Outlined.SelectAll, contentDescription = "Alle auswählen")
                            }
                            IconButton(onClick = { runExportApps(selectedApps) }) {
                                Icon(Icons.Outlined.FileDownload, contentDescription = "Exportieren")
                            }
                            IconButton(onClick = { runTempCleanup(selectedApps) }) {
                                Icon(Icons.Outlined.CleaningServices, contentDescription = "Temp/Cache")
                            }
                            IconButton(onClick = { runBatchAudit(selectedApps) }) {
                                Icon(
                                    Icons.Outlined.Security,
                                    contentDescription = "Berechtigungen & Benachrichtigungen",
                                )
                            }
                            IconButton(
                                onClick = {
                                    val uninstallable = selectedApps.filter { it.canUninstall }
                                    if (uninstallable.isEmpty()) {
                                        scope.launch {
                                            snackbarHostState.showSnackbar(
                                                "System-Apps können nicht direkt deinstalliert werden",
                                            )
                                        }
                                    } else {
                                        showUninstallConfirm = uninstallable
                                    }
                                },
                            ) {
                                Icon(Icons.Outlined.Delete, contentDescription = "Deinstallieren")
                            }
                        },
                    )
                } else {
                    TopAppBar(
                        navigationIcon = {
                            IconButton(
                                onClick = {
                                    MonolithHaptics.monolithMenuOpen(context, view)
                                    starMenuExpanded = !starMenuExpanded
                                },
                                modifier = Modifier.onGloballyPositioned { coords ->
                                    starAnchorCenter = coords.boundsInRoot().center - rootOrigin
                                },
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Apps,
                                    contentDescription = "Monolith-Menü",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                        },
                        title = {
                            if (searchActive) {
                                OutlinedTextField(
                                    value = searchQuery,
                                    onValueChange = { searchQuery = it },
                                    placeholder = { Text("App oder Paket suchen…") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            } else {
                                Text(
                                    text = "App-Verwaltung",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        },
                        actions = {
                            IconButton(
                                onClick = {
                                    if (searchActive) {
                                        searchActive = false
                                        searchQuery = ""
                                    } else {
                                        searchActive = true
                                    }
                                },
                            ) {
                                Icon(
                                    imageVector = if (searchActive) Icons.Default.Close else Icons.Outlined.Search,
                                    contentDescription = if (searchActive) "Suche schließen" else "Suchen",
                                )
                            }
                            IconButton(
                                onClick = {
                                    MonolithHaptics.cornerMenuOpen(context, view)
                                    menuExpanded = !menuExpanded
                                },
                                modifier = Modifier.onGloballyPositioned { coords ->
                                    menuAnchorCenter = coords.boundsInRoot().center - rootOrigin
                                },
                            ) {
                                Icon(Icons.Outlined.MoreVert, contentDescription = "Menü")
                            }
                        },
                    )
                }
            },
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .navigationBarsPadding(),
            ) {
                AnimatedVisibility(visible = batchBusyLabel != null) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                    ) {
                        val progress = batchProgress
                        Text(
                            text = if (progress != null) {
                                "${batchBusyLabel.orEmpty()} (${progress.first}/${progress.second})"
                            } else {
                                batchBusyLabel.orEmpty()
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        if (progress != null && progress.second > 0) {
                            LinearProgressIndicator(
                                progress = { progress.first.toFloat() / progress.second.toFloat() },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                }

                if (!hasUsageStats && !usageBannerDismissed) {
                    UsageStatsPermissionBanner(
                        onGrantClick = {
                            runCatching {
                                context.startActivity(AppManagerRepository.buildUsageAccessIntent(context))
                            }.onFailure {
                                scope.launch {
                                    snackbarHostState.showSnackbar("Nutzungszugriff-Einstellungen nicht verfügbar")
                                }
                            }
                        },
                        onDismissClick = { usageBannerDismissed = true },
                    )
                }

                FilterAndSortBar(
                    selectedFilter = filterTab,
                    onSelectFilter = { filterTab = it },
                    selectedSort = sortOrder,
                    sortMenuExpanded = sortMenuExpanded,
                    onSortMenuExpandedChange = { sortMenuExpanded = it },
                    onSelectSort = {
                        sortOrder = it
                        sortMenuExpanded = false
                    },
                    appCount = filteredApps.size,
                )

                when {
                    isLoading && allApps.isEmpty() -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                CircularProgressIndicator()
                                Text(
                                    text = "Installierte Apps werden geladen…",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    filteredApps.isEmpty() -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = if (searchQuery.isNotBlank()) {
                                    "Keine Apps für „$searchQuery“ gefunden"
                                } else {
                                    "Keine Apps in dieser Kategorie"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    else -> {
                        var isPullRefreshing by remember { mutableStateOf(false) }
                        val pullState = rememberPullToRefreshState()
                        val pullThresholdReached = pullState.distanceFraction >= 1f
                        val targetContentOffsetDp = if (isPullRefreshing) {
                            68f
                        } else {
                            pullState.distanceFraction.coerceIn(0f, 1.2f) * 68f
                        }
                        val contentOffsetDp by animateFloatAsState(
                            targetValue = targetContentOffsetDp,
                            label = "appManagerPullRefreshContentOffset",
                        )

                        LaunchedEffect(pullThresholdReached) {
                            if (pullThresholdReached && !isPullRefreshing) {
                                MonolithHaptics.refreshDetent(context, view)
                            }
                        }

                        PullToRefreshBox(
                            isRefreshing = isPullRefreshing,
                            onRefresh = {
                                if (!isPullRefreshing) {
                                    isPullRefreshing = true
                                    reloadTrigger++
                                    scope.launch {
                                        delay(480L)
                                        while (isLoading) {
                                            delay(50L)
                                        }
                                        isPullRefreshing = false
                                        MonolithHaptics.refreshComplete(context, view)
                                    }
                                }
                            },
                            state = pullState,
                            modifier = Modifier.fillMaxSize(),
                            indicator = {
                                MonolithRefreshIndicator(
                                    state = pullState,
                                    isRefreshing = isPullRefreshing,
                                    modifier = Modifier.align(Alignment.TopCenter),
                                )
                            },
                        ) {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        translationY = contentOffsetDp.dp.toPx()
                                    },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                            items(
                                items = filteredApps,
                                key = { it.packageName },
                            ) { app ->
                                val isSelected = app.packageName in selectedPackages
                                val cachedIcon = iconCache[app.packageName]
                                LaunchedEffect(app.packageName) {
                                    if (!iconCache.containsKey(app.packageName)) {
                                        val loadedBitmap = withContext(Dispatchers.IO) {
                                            runCatching {
                                                context.packageManager
                                                    .getApplicationIcon(app.packageName)
                                                    .toBitmap(96, 96)
                                                    .asImageBitmap()
                                            }.getOrNull()
                                        }
                                        iconCache[app.packageName] = loadedBitmap
                                    }
                                }

                                AppListRow(
                                    app = app,
                                    icon = cachedIcon,
                                    isSelected = isSelected,
                                    onClick = {
                                        if (selectedPackages.isNotEmpty()) {
                                            selectedPackages = if (isSelected) {
                                                selectedPackages - app.packageName
                                            } else {
                                                selectedPackages + app.packageName
                                            }
                                        } else {
                                            detailApp = app
                                            detailLoading = true
                                            scope.launch {
                                                val full = AppManagerRepository.loadAppDetails(context, app)
                                                if (detailApp?.packageName == app.packageName) {
                                                    detailApp = full
                                                    detailLoading = false
                                                }
                                            }
                                        }
                                    },
                                    onLongClick = {
                                        MonolithHaptics.dragLift(view)
                                        selectedPackages = if (isSelected) {
                                            selectedPackages - app.packageName
                                        } else {
                                            selectedPackages + app.packageName
                                        }
                                    },
                                )
                            }
                        }
                        }
                    }
                }
            }
        }

        MonolithToolStarMenu(
            expanded = starMenuExpanded,
            anchorCenter = starAnchorCenter,
            anchorIcon = Icons.Outlined.Apps,
            activeDestination = MonolithToolDestination.APPS,
            onSelectFiles = onNavigateToFiles,
            onSelectArchives = onNavigateToArchives,
            onSelectStorage = onNavigateToStorage,
            onSelectDownloader = onNavigateToDownloader,
            onSelectApps = { starMenuExpanded = false },
            onSelectAi = onNavigateToAi,
            onDismiss = { starMenuExpanded = false },
        )

        val cornerItems = buildList {
            add(
                CornerMenuItem(
                    id = "export_dir",
                    label = "Export-Ordner öffnen",
                    onClick = {
                        onOpenFolderInExplorer(AppManagerRepository.defaultExportDirectory().absolutePath)
                    },
                ),
            )
            if (!hasUsageStats) {
                add(
                    CornerMenuItem(
                        id = "usage_access",
                        label = "Nutzungszugriff",
                        subtitle = "Für exakte App- & Cache-Größen",
                        onClick = {
                            runCatching {
                                context.startActivity(AppManagerRepository.buildUsageAccessIntent(context))
                            }
                        },
                    ),
                )
            }
            add(
                CornerMenuItem(
                    id = "settings",
                    label = "App-Einstellungen",
                    onClick = onOpenSettings,
                ),
            )
        }

        MonolithCornerMenuOverlay(
            expanded = menuExpanded,
            anchorCenter = menuAnchorCenter,
            items = cornerItems,
            onDismiss = { menuExpanded = false },
        )
    }

    // Single App Detail Sheet
    detailApp?.let { activeApp ->
        AppDetailModalSheet(
            app = activeApp,
            icon = iconCache[activeApp.packageName],
            isLoadingDetails = detailLoading,
            onDismiss = { detailApp = null },
            onUninstall = {
                detailApp = null
                showUninstallConfirm = listOf(activeApp)
            },
            onExport = {
                detailApp = null
                runExportApps(listOf(activeApp))
            },
            onCleanTemp = {
                detailApp = null
                runTempCleanup(listOf(activeApp))
            },
            onOpenAppInfo = {
                runCatching {
                    context.startActivity(AppManagerRepository.buildAppDetailsIntent(activeApp.packageName))
                }.onFailure {
                    scope.launch {
                        snackbarHostState.showSnackbar("App-Info konnte nicht geöffnet werden")
                    }
                }
            },
            onOpenNotificationSettings = {
                runCatching {
                    context.startActivity(
                        AppManagerRepository.buildNotificationSettingsIntent(activeApp.packageName),
                    )
                }.onFailure {
                    scope.launch {
                        snackbarHostState.showSnackbar("Benachrichtigungs-Einstellungen nicht verfügbar")
                    }
                }
            },
        )
    }

    // Uninstall Confirmation Dialog
    if (showUninstallConfirm.isNotEmpty()) {
        val targets = showUninstallConfirm
        val description = if (targets.size == 1) {
            "Möchtest du „${targets.first().appName}“ wirklich deinstallieren?"
        } else {
            "Möchtest du ${targets.size} ausgewählte Apps nacheinander deinstallieren?"
        }
        MonolithDialog(
            onDismissRequest = { showUninstallConfirm = emptyList() },
            title = "Deinstallieren",
            dismissLabel = "Abbrechen",
            onDismissClick = { showUninstallConfirm = emptyList() },
            confirmLabel = "Deinstallieren",
            onConfirmClick = {
                val packages = targets.map { it.packageName }
                showUninstallConfirm = emptyList()
                selectedPackages = emptySet()
                startUninstallQueue(packages)
            },
        ) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (targets.size > 1) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = targets.joinToString("\n") { "• ${it.appName}" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    // Cache cleanup warning dialog: triggers Android's global ACTION_CLEAR_APP_CACHE dialog (Android 11+)
    if (cacheWarningApps.isNotEmpty()) {
        val targetApps = cacheWarningApps
        val clearAllIntent = remember { AppManagerRepository.buildClearAppCacheIntent() }
        MonolithDialog(
            onDismissRequest = { cacheWarningApps = emptyList() },
            title = "App-Caches leeren",
            dismissLabel = "Abbrechen",
            onDismissClick = { cacheWarningApps = emptyList() },
            confirmLabel = if (clearAllIntent != null) "Alle App-Caches leeren" else "App-Info öffnen",
            onConfirmClick = {
                val firstPkg = targetApps.firstOrNull()?.packageName
                cacheWarningApps = emptyList()
                selectedPackages = emptySet()
                if (clearAllIntent != null) {
                    runCatching {
                        systemClearCacheLauncher.launch(clearAllIntent)
                    }.onFailure {
                        scope.launch {
                            snackbarHostState.showSnackbar("System-Cache-Dialog wird auf diesem Gerät nicht unterstützt")
                        }
                    }
                } else if (firstPkg != null) {
                    runCatching {
                        context.startActivity(AppManagerRepository.buildAppDetailsIntent(firstPkg))
                    }
                }
            },
        ) {
            Text(
                text = if (clearAllIntent != null) {
                    "Android öffnet den System-Dialog zum Leeren temporärer Cache-Dateien aller installierten Apps auf einmal. Nutzerdaten bleiben erhalten."
                } else {
                    "Auf dieser Android-Version muss der Cache in den jeweiligen App-Einstellungen geleert werden."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }

    // Batch Audit Report Dialog
    auditReport?.let { report ->
        BatchAuditReportDialog(
            report = report,
            onDismiss = { auditReport = null },
            onSaveReport = {
                scope.launch {
                    val exportDir = AppManagerRepository.defaultExportDirectory()
                    val saveResult = withContext(Dispatchers.IO) {
                        AppManagerRepository.exportAuditReport(report, exportDir)
                    }
                    auditReport = null
                    saveResult.onSuccess { file ->
                        val res = snackbarHostState.showSnackbar(
                            message = "Bericht gespeichert: ${file.name}",
                            actionLabel = "Ordner öffnen",
                            duration = SnackbarDuration.Long,
                        )
                        if (res == SnackbarResult.ActionPerformed) {
                            onOpenFolderInExplorer(exportDir.absolutePath)
                        }
                    }.onFailure { err ->
                        snackbarHostState.showSnackbar(
                            "Fehler beim Speichern: ${err.message ?: "Unbekannt"}",
                        )
                    }
                }
            },
        )
    }
}

@Composable
private fun UsageStatsPermissionBanner(
    onGrantClick: () -> Unit,
    onDismissClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        shape = CutCornerShape(10.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Nutzungszugriff für exakte Größen",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Ohne Nutzungszugriff zeigt Android nur die APK-Größe statt App-, Daten- & Cache-Größen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onGrantClick) {
                Text("Freigeben")
            }
            IconButton(
                onClick = onDismissClick,
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Ausblenden",
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun FilterAndSortBar(
    selectedFilter: AppFilterTab,
    onSelectFilter: (AppFilterTab) -> Unit,
    selectedSort: AppSortOrder,
    sortMenuExpanded: Boolean,
    onSortMenuExpandedChange: (Boolean) -> Unit,
    onSelectSort: (AppSortOrder) -> Unit,
    appCount: Int,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppFilterTab.entries.forEach { tab ->
            FilterChip(
                selected = selectedFilter == tab,
                onClick = { onSelectFilter(tab) },
                label = { Text(tab.label) },
            )
        }
        Box {
            FilterChip(
                selected = false,
                onClick = { onSortMenuExpandedChange(true) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.Sort,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                },
                label = { Text("${selectedSort.label} ($appCount)") },
            )
            DropdownMenu(
                expanded = sortMenuExpanded,
                onDismissRequest = { onSortMenuExpandedChange(false) },
            ) {
                AppSortOrder.entries.forEach { order ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = order.label,
                                fontWeight = if (order == selectedSort) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        },
                        onClick = { onSelectSort(order) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun AppListRow(
    app: ManagedAppInfo,
    icon: ImageBitmap?,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val containerColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f)
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CutCornerShape(10.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        shape = CutCornerShape(10.dp),
        color = containerColor,
        border = if (isSelected) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
        } else {
            null
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier.size(42.dp),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    isSelected -> {
                        Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = "Ausgewählt",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(34.dp),
                        )
                    }
                    icon != null -> {
                        Image(
                            bitmap = icon,
                            contentDescription = app.appName,
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CutCornerShape(8.dp)),
                        )
                    }
                    else -> {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .background(
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    shape = CutCornerShape(8.dp),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Android,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = app.appName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (app.versionName.isNotBlank()) {
                        Text(
                            text = "v${app.versionName}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (app.isSystemApp) {
                        CompactBadge(
                            text = "System",
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                    if (!app.isEnabled) {
                        CompactBadge(
                            text = "Deaktiviert",
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                    if (app.cacheBytes > 0L) {
                        CompactBadge(
                            text = "Cache ${formatStorageBytes(app.cacheBytes)}",
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                }
            }

            Text(
                text = formatStorageBytes(app.totalDisplayBytes),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun CompactBadge(
    text: String,
    containerColor: Color,
    contentColor: Color,
) {
    Surface(
        shape = CutCornerShape(4.dp),
        color = containerColor,
        contentColor = contentColor,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
            maxLines = 1,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun AppDetailModalSheet(
    app: ManagedAppInfo,
    icon: ImageBitmap?,
    isLoadingDetails: Boolean,
    onDismiss: () -> Unit,
    onUninstall: () -> Unit,
    onExport: () -> Unit,
    onCleanTemp: () -> Unit,
    onOpenAppInfo: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedTab by rememberSaveable(app.packageName) { mutableIntStateOf(0) }
    var showOtherPermissions by rememberSaveable(app.packageName) { mutableStateOf(false) }
    val buttonShape = LocalMonolithButtonShape.current
    val dateFormat = remember { DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.GERMAN) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (icon != null) {
                    Image(
                        bitmap = icon,
                        contentDescription = app.appName,
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CutCornerShape(10.dp)),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                CutCornerShape(10.dp),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Outlined.Android, contentDescription = null)
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = app.appName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = app.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val versionText = buildString {
                        if (app.versionName.isNotBlank()) append("v${app.versionName} ")
                        append("(${app.versionCode})")
                    }
                    Text(
                        text = versionText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            // Dates & Size Breakdown
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = CutCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val installDate = if (app.firstInstallTime > 0L) dateFormat.format(Date(app.firstInstallTime)) else "–"
                    val updateDate = if (app.lastUpdateTime > 0L) dateFormat.format(Date(app.lastUpdateTime)) else "–"
                    Text(
                        text = "Installiert: $installDate · Aktualisiert: $updateDate",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = "APK: ${formatStorageBytes(app.apkSizeBytes)}",
                            style = MaterialTheme.typography.labelMedium,
                        )
                        if (app.hasUsageStats) {
                            Text(
                                text = "App: ${formatStorageBytes(app.appBytes)}",
                                style = MaterialTheme.typography.labelMedium,
                            )
                            Text(
                                text = "Daten: ${formatStorageBytes(app.dataBytes)}",
                                style = MaterialTheme.typography.labelMedium,
                            )
                            Text(
                                text = "Cache: ${formatStorageBytes(app.cacheBytes)}",
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
            }

            // Quick Actions
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onUninstall,
                    enabled = app.canUninstall,
                    shape = buttonShape,
                ) {
                    Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Deinstallieren")
                }
                OutlinedButton(
                    onClick = onExport,
                    shape = buttonShape,
                ) {
                    Icon(Icons.Outlined.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (app.splitSourceDirs.isEmpty()) "Exportieren (.apk)" else "Exportieren (.apks)")
                }
                OutlinedButton(
                    onClick = onCleanTemp,
                    shape = buttonShape,
                ) {
                    Icon(Icons.Outlined.CleaningServices, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Temp/Cache")
                }
                OutlinedButton(
                    onClick = onOpenAppInfo,
                    shape = buttonShape,
                ) {
                    Icon(Icons.Outlined.Info, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("App-Info")
                }
            }

            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Berechtigungen") },
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Benachrichtigungen") },
                )
            }

            if (isLoadingDetails) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else if (selectedTab == 0) {
                val permissions = app.permissions.orEmpty()
                val runtime = permissions.filter { it.category == PermissionCategory.RUNTIME }
                val grantedRuntime = runtime.filter { it.isGranted }
                val deniedRuntime = runtime.filter { !it.isGranted }
                val otherPerms = permissions.filter { it.category != PermissionCategory.RUNTIME }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (runtime.isEmpty() && otherPerms.isEmpty()) {
                        Text(
                            text = "Keine Berechtigungen angefordert",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        if (grantedRuntime.isNotEmpty()) {
                            Text(
                                text = "Erteilt (${grantedRuntime.size})",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            grantedRuntime.forEach { perm ->
                                PermissionRow(perm)
                            }
                        }
                        if (deniedRuntime.isNotEmpty()) {
                            Text(
                                text = "Verweigert (${deniedRuntime.size})",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.error,
                            )
                            deniedRuntime.forEach { perm ->
                                PermissionRow(perm)
                            }
                        }
                        if (otherPerms.isNotEmpty()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showOtherPermissions = !showOtherPermissions }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = "Normale & Spezielle Berechtigungen (${otherPerms.size})",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Icon(
                                    imageVector = if (showOtherPermissions) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                    contentDescription = null,
                                )
                            }
                            if (showOtherPermissions) {
                                otherPerms.forEach { perm ->
                                    PermissionRow(perm)
                                }
                            }
                        }
                    }
                }
            } else {
                val status = app.notificationSummary?.permissionStatus
                    ?: NotificationPermissionStatus.LEGACY_NOT_REQUIRED
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = CutCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Notifications,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Benachrichtigungsberechtigung",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = status.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                    Button(
                        onClick = onOpenNotificationSettings,
                        shape = buttonShape,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Benachrichtigungs-Einstellungen öffnen")
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionRow(entry: AppPermissionEntry) {
    val badgeColor = if (entry.isGranted) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.errorContainer
    }
    val badgeTextColor = if (entry.isGranted) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onErrorContainer
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.label,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = entry.shortName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        CompactBadge(
            text = if (entry.isGranted) "Erteilt" else "Verweigert",
            containerColor = badgeColor,
            contentColor = badgeTextColor,
        )
    }
}

@Composable
private fun BatchAuditReportDialog(
    report: BatchAuditReport,
    onDismiss: () -> Unit,
    onSaveReport: () -> Unit,
) {
    val expandedPackages = remember { mutableStateMapOf<String, Boolean>() }
    MonolithDialog(
        onDismissRequest = onDismiss,
        title = "Berechtigungen & Benachrichtigungen",
        wide = true,
        dismissLabel = "Schließen",
        onDismissClick = onDismiss,
        confirmLabel = "Bericht speichern (.txt)",
        onConfirmClick = onSaveReport,
    ) {
        Text(
            text = "${report.entries.size} App(s) geprüft",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 360.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            report.entries.forEach { entry ->
                val isExpanded = expandedPackages[entry.packageName] == true
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(CutCornerShape(8.dp))
                        .clickable {
                            expandedPackages[entry.packageName] = !isExpanded
                        },
                    shape = CutCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = entry.appName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "Erteilt: ${entry.grantedRuntimePermissions.size} · Verweigert: ${entry.deniedRuntimePermissions.size} · Benachrichtigungen: ${entry.notificationStatus.label}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Icon(
                                imageVector = if (isExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                contentDescription = null,
                            )
                        }
                        if (isExpanded) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                            if (entry.grantedRuntimePermissions.isEmpty() && entry.deniedRuntimePermissions.isEmpty()) {
                                Text(
                                    text = "Keine Laufzeit-Berechtigungen (${entry.totalPermissionCount} gesamt)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                entry.grantedRuntimePermissions.forEach { perm ->
                                    PermissionRow(perm)
                                }
                                entry.deniedRuntimePermissions.forEach { perm ->
                                    PermissionRow(perm)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
