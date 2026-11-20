package com.monolith.feature.explorer.ai

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.monolith.core.common.AppSettingsStore
import com.monolith.core.model.ThemeMode
import com.monolith.core.ui.components.CornerMenuItem
import com.monolith.core.ui.components.MonolithCornerMenuOverlay
import com.monolith.core.ui.components.MonolithDialog
import com.monolith.core.ui.components.monolithMenuBackdrop
import com.monolith.core.ui.components.MonolithDialog
import com.monolith.core.ui.components.MonolithDialogOption
import com.monolith.core.ui.haptics.MonolithHaptics
import com.monolith.core.ui.theme.LocalMonolithButtonShape
import com.monolith.core.ui.theme.LocalMonolithThemeMode
import com.monolith.feature.explorer.MonolithToolDestination
import com.monolith.feature.explorer.MonolithToolStarMenu
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

enum class ChatRole {
    USER,
    ASSISTANT,
    ERROR,
}

data class AiChatMessage(
    val id: Long,
    val role: ChatRole,
    val text: String,
    val thoughtText: String = "",
    val toolCalls: List<ToolCallTrace> = emptyList(),
    val toolStatus: String? = null,
    val modelLabel: String? = null,
    val thinkingLabel: String? = null,
)

private data class ThinkingLevelOption(
    val id: String,
    val label: String,
)

private data class GeminiModelOption(
    val id: String,
    val label: String,
    val supportedThinkingLevels: List<ThinkingLevelOption>,
) {
    /** One level above the smallest supported level. */
    val defaultThinkingLevel: String
        get() = supportedThinkingLevels.getOrElse(1) { supportedThinkingLevels.first() }.id
}

private val THINKING_MINIMAL = ThinkingLevelOption("minimal", "Minimal")
private val THINKING_LOW = ThinkingLevelOption("low", "Niedrig")
private val THINKING_MEDIUM = ThinkingLevelOption("medium", "Mittel")
private val THINKING_HIGH = ThinkingLevelOption("high", "Hoch")
private val THINKING_XHIGH = ThinkingLevelOption("xhigh", "xHigh")

private val GEMINI_MODELS = listOf(
    GeminiModelOption(
        id = "gemini-3.5-flash-lite",
        label = "Gemini 3.5 Flash-Lite",
        supportedThinkingLevels = listOf(THINKING_MINIMAL, THINKING_LOW, THINKING_MEDIUM, THINKING_HIGH),
    ),
    GeminiModelOption(
        id = "gemini-3.8-flash",
        label = "Gemini 3.8 Flash",
        supportedThinkingLevels = listOf(THINKING_LOW, THINKING_MEDIUM, THINKING_HIGH),
    ),
    GeminiModelOption(
        id = "gemini-4-hydrogen",
        label = "Gemini 4 Hydrogen",
        supportedThinkingLevels = listOf(THINKING_LOW, THINKING_MEDIUM, THINKING_HIGH, THINKING_XHIGH),
    ),
    GeminiModelOption(
        id = "gemini-4-neon",
        label = "Gemini 4 Neon",
        supportedThinkingLevels = listOf(THINKING_LOW, THINKING_MEDIUM, THINKING_HIGH, THINKING_XHIGH),
    ),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiChatScreen(
    currentDirectory: String,
    onBack: () -> Unit,
    onOpenArchives: () -> Unit = {},
    onOpenStorage: () -> Unit = {},
    onOpenDownloader: () -> Unit = {},
    onSelectApps: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val view = LocalView.current
    AppSettingsStore.init(context)
    val appSettings by AppSettingsStore.settings.collectAsState()
    val apiKey = appSettings.activeGeminiApiKey.trim()

    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    val messages = remember { mutableStateListOf<AiChatMessage>() }
    var previousInteractionId by remember { mutableStateOf<String?>(null) }
    var inputText by remember { mutableStateOf("") }
    var isStreaming by remember { mutableStateOf(false) }
    var activeJob by remember { mutableStateOf<Job?>(null) }
    var nextMessageId by remember { mutableStateOf(1L) }

    val currentModelOption = remember(appSettings.aiModelId) {
        GEMINI_MODELS.firstOrNull { it.id == appSettings.aiModelId } ?: GEMINI_MODELS.first()
    }
    val currentThinkingOption = remember(currentModelOption, appSettings.aiThinkingLevel) {
        currentModelOption.supportedThinkingLevels.firstOrNull { it.id == appSettings.aiThinkingLevel }
            ?: currentModelOption.supportedThinkingLevels.firstOrNull { it.id == currentModelOption.defaultThinkingLevel }
            ?: currentModelOption.supportedThinkingLevels.first()
    }

    var starMenuExpanded by remember { mutableStateOf(false) }
    var starAnchorCenter by remember { mutableStateOf(Offset.Unspecified) }
    var menuAnchorCenter by remember { mutableStateOf(Offset.Unspecified) }
    var rootOrigin by remember { mutableStateOf(Offset.Zero) }

    var menuExpanded by remember { mutableStateOf(false) }
    var showModelDialog by remember { mutableStateOf(false) }
    var showThinkingDialog by remember { mutableStateOf(false) }
    var showSystemPromptDialog by remember { mutableStateOf(false) }
    var showNewChatConfirm by remember { mutableStateOf(false) }
    var showMissingKeyPopup by remember { mutableStateOf(false) }
    var showApiKeyDialog by remember { mutableStateOf(false) }

    BackHandler(enabled = starMenuExpanded || menuExpanded) {
        starMenuExpanded = false
        menuExpanded = false
    }

    BackHandler(enabled = !starMenuExpanded && !menuExpanded) {
        activeJob?.cancel()
        onBack()
    }

    LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    fun startNewChat() {
        activeJob?.cancel()
        activeJob = null
        isStreaming = false
        messages.clear()
        previousInteractionId = null
    }

    fun sendUserMessage(rawText: String) {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty() || isStreaming) return
        if (apiKey.isBlank()) {
            showMissingKeyPopup = true
            return
        }

        val usedModel = currentModelOption
        val usedThinking = currentThinkingOption

        inputText = ""

        val userMsg = AiChatMessage(
            id = nextMessageId++,
            role = ChatRole.USER,
            text = trimmed,
        )
        val assistantId = nextMessageId++
        val assistantMsg = AiChatMessage(
            id = assistantId,
            role = ChatRole.ASSISTANT,
            text = "",
        )
        messages.add(userMsg)
        messages.add(assistantMsg)
        isStreaming = true

        activeJob = scope.launch {
            val result = GeminiInteractionsClient.sendMessage(
                apiKey = apiKey,
                userMessage = trimmed,
                previousInteractionId = previousInteractionId,
                currentDirectory = currentDirectory,
                model = usedModel.id,
                thinkingLevel = usedThinking.id,
                systemPromptTemplate = appSettings.aiSystemPrompt,
                writeToolsEnabled = appSettings.aiExperimentalWriteTools,
                terminalEnabled = appSettings.aiExperimentalTerminalAccess,
                onTextDelta = { delta ->
                    val idx = messages.indexOfFirst { it.id == assistantId }
                    if (idx >= 0) {
                        val current = messages[idx]
                        messages[idx] = current.copy(text = current.text + delta)
                    }
                },
                onThoughtDelta = { thoughtDelta ->
                    val idx = messages.indexOfFirst { it.id == assistantId }
                    if (idx >= 0) {
                        val current = messages[idx]
                        messages[idx] = current.copy(thoughtText = current.thoughtText + thoughtDelta)
                    }
                },
                onToolCallTrace = { trace ->
                    val idx = messages.indexOfFirst { it.id == assistantId }
                    if (idx >= 0) {
                        val current = messages[idx]
                        messages[idx] = current.copy(toolCalls = current.toolCalls + trace)
                    }
                },
                onToolStatus = { status ->
                    val idx = messages.indexOfFirst { it.id == assistantId }
                    if (idx >= 0) {
                        val current = messages[idx]
                        messages[idx] = current.copy(toolStatus = status)
                    }
                },
            )

            result.onSuccess { newInteractionId ->
                previousInteractionId = newInteractionId
                val idx = messages.indexOfFirst { it.id == assistantId }
                if (idx >= 0) {
                    val current = messages[idx]
                    messages[idx] = current.copy(
                        text = current.text.ifBlank { "(Keine Textantwort erhalten)" },
                        toolStatus = null,
                        modelLabel = usedModel.label,
                        thinkingLabel = usedThinking.label,
                    )
                }
            }.onFailure { err ->
                val idx = messages.indexOfFirst { it.id == assistantId }
                if (idx >= 0) {
                    val existing = messages[idx].text
                    if (existing.isBlank()) {
                        messages[idx] = AiChatMessage(
                            id = assistantId,
                            role = ChatRole.ERROR,
                            text = err.message ?: "Anfrage an Gemini fehlgeschlagen.",
                        )
                    } else {
                        messages[idx] = messages[idx].copy(
                            toolStatus = null,
                            modelLabel = usedModel.label,
                            thinkingLabel = usedThinking.label,
                        )
                        messages.add(
                            AiChatMessage(
                                id = nextMessageId++,
                                role = ChatRole.ERROR,
                                text = err.message ?: "Streaming unterbrochen",
                            ),
                        )
                    }
                }
            }
            isStreaming = false
            activeJob = null
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { rootOrigin = it.positionInRoot() },
    ) {
        val backgroundRecedeState = animateFloatAsState(
            targetValue = if (starMenuExpanded) 1f else 0f,
            label = "aiStarMenuBackgroundRecede",
        )
        val cornerMenuRecedeState = animateFloatAsState(
            targetValue = if (menuExpanded) 1f else 0f,
            label = "aiCornerMenuBackgroundRecede",
        )

        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .monolithMenuBackdrop(backgroundRecedeState, cornerMenuRecedeState),
            containerColor = if (LocalMonolithThemeMode.current == ThemeMode.OBSIDIAN) {
                Color.Transparent
            } else {
                MaterialTheme.colorScheme.background
            },
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = "Monolith",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
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
                                imageVector = Icons.Outlined.ViewInAr,
                                contentDescription = "Monolith-Menü",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    },
                    actions = {
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
            },
            bottomBar = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.extraLarge,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.94f),
                        border = BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
                        ),
                        tonalElevation = 3.dp,
                        shadowElevation = 2.dp,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 8.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            TextField(
                                value = inputText,
                                onValueChange = { inputText = it },
                                placeholder = {
                                    Text(
                                        text = "Nachricht an Gemini…",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                                maxLines = 4,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                keyboardActions = KeyboardActions(
                                    onSend = { sendUserMessage(inputText) },
                                ),
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                    disabledContainerColor = Color.Transparent,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                    disabledIndicatorColor = Color.Transparent,
                                ),
                                modifier = Modifier.weight(1f),
                            )

                            if (isStreaming) {
                                FilledIconButton(
                                    onClick = {
                                        activeJob?.cancel()
                                        activeJob = null
                                        isStreaming = false
                                    },
                                    shape = LocalMonolithButtonShape.current,
                                    modifier = Modifier.size(40.dp),
                                    colors = IconButtonDefaults.filledIconButtonColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                                        contentColor = MaterialTheme.colorScheme.primary,
                                    ),
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Stop,
                                        contentDescription = "Abbrechen",
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            } else {
                                FilledIconButton(
                                    onClick = { sendUserMessage(inputText) },
                                    enabled = inputText.isNotBlank(),
                                    shape = LocalMonolithButtonShape.current,
                                    modifier = Modifier.size(40.dp),
                                    colors = IconButtonDefaults.filledIconButtonColors(
                                        containerColor = MaterialTheme.colorScheme.primary,
                                        contentColor = MaterialTheme.colorScheme.onPrimary,
                                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    ),
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.Send,
                                        contentDescription = "Senden",
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            },
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                if (messages.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        GeometricNeuralNetwork(
                            modifier = Modifier.size(220.dp),
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(messages, key = { it.id }) { msg ->
                            ChatBubble(
                                message = msg,
                                isStreamingThis = isStreaming && msg.id == messages.lastOrNull()?.id && msg.role == ChatRole.ASSISTANT,
                            )
                        }
                    }
                }
            }
        }

        MonolithToolStarMenu(
            expanded = starMenuExpanded,
            anchorCenter = starAnchorCenter,
            anchorIcon = Icons.Outlined.ViewInAr,
            activeDestination = MonolithToolDestination.AI,
            onAnchorClick = {
                starMenuExpanded = false
                activeJob?.cancel()
                onBack()
            },
            onSelectFiles = {
                activeJob?.cancel()
                onBack()
            },
            onSelectArchives = {
                activeJob?.cancel()
                onOpenArchives()
            },
            onSelectStorage = {
                activeJob?.cancel()
                onOpenStorage()
            },
            onSelectDownloader = {
                activeJob?.cancel()
                onOpenDownloader()
            },
            onSelectApps = {
                activeJob?.cancel()
                onSelectApps()
            },
            onSelectAi = {
                starMenuExpanded = false
            },
            onDismiss = { starMenuExpanded = false },
        )

        val shortModel = currentModelOption.label.removePrefix("Gemini ")
        MonolithCornerMenuOverlay(
            expanded = menuExpanded,
            anchorCenter = menuAnchorCenter,
            items = listOf(
                CornerMenuItem(
                    id = "model",
                    label = "Modell",
                    subtitle = "($shortModel ${currentThinkingOption.label})",
                    onClick = { showModelDialog = true },
                ),
                CornerMenuItem(
                    id = "system_prompt",
                    label = "Systemprompt",
                    onClick = { showSystemPromptDialog = true },
                ),
                CornerMenuItem(
                    id = "api_keys",
                    label = "API-Verwaltung",
                    onClick = { showApiKeyDialog = true },
                ),
                CornerMenuItem(
                    id = "new_chat",
                    label = "Neuer Chat",
                    onClick = {
                        if (messages.isEmpty() && previousInteractionId == null) {
                            startNewChat()
                        } else {
                            showNewChatConfirm = true
                        }
                    },
                ),
                CornerMenuItem(
                    id = "settings",
                    label = "App-Einstellungen",
                    onClick = onOpenSettings,
                ),
            ),
            onDismiss = { menuExpanded = false },
        )
    }

    if (showMissingKeyPopup) {
        MonolithDialog(
            onDismissRequest = { showMissingKeyPopup = false },
            title = "Gemini-API-Key fehlt",
            dismissLabel = "Abbrechen",
            onDismissClick = { showMissingKeyPopup = false },
            confirmLabel = "API-Verwaltung öffnen",
            onConfirmClick = {
                showMissingKeyPopup = false
                showApiKeyDialog = true
            },
        ) {
            Text(
                "Für das AI-Chatfenster wird ein Gemini-API-Key benötigt. Möchtest du jetzt die API-Verwaltung öffnen?",
            )
        }
    }

    if (showNewChatConfirm) {
        MonolithDialog(
            onDismissRequest = { showNewChatConfirm = false },
            title = "Neuer Chat",
            dismissLabel = "Abbrechen",
            onDismissClick = { showNewChatConfirm = false },
            confirmLabel = "Neu starten",
            onConfirmClick = {
                showNewChatConfirm = false
                startNewChat()
            },
        ) {
            Text(
                "Der aktuelle Chatverlauf geht dabei verloren. Möchtest du wirklich einen neuen Chat starten?",
            )
        }
    }

    if (showModelDialog) {
        MonolithDialog(
            onDismissRequest = { showModelDialog = false },
            title = "Modell wählen",
            confirmLabel = "Schließen",
            onConfirmClick = { showModelDialog = false },
        ) {
            GEMINI_MODELS.forEach { option ->
                MonolithDialogOption(
                    label = option.label,
                    selected = currentModelOption.id == option.id,
                    onClick = {
                        val preselectedLevel = option.defaultThinkingLevel
                        AppSettingsStore.update {
                            it.copy(
                                aiModelId = option.id,
                                aiThinkingLevel = preselectedLevel,
                            )
                        }
                        showModelDialog = false
                        showThinkingDialog = true
                    },
                )
            }
        }
    }

    if (showThinkingDialog) {
        val supportedLevels = currentModelOption.supportedThinkingLevels
        MonolithDialog(
            onDismissRequest = { showThinkingDialog = false },
            title = "Denkaufwand",
            confirmLabel = "Schließen",
            onConfirmClick = { showThinkingDialog = false },
        ) {
            supportedLevels.forEach { option ->
                MonolithDialogOption(
                    label = option.label,
                    selected = currentThinkingOption.id == option.id,
                    onClick = {
                        AppSettingsStore.update { it.copy(aiThinkingLevel = option.id) }
                        showThinkingDialog = false
                    },
                )
            }
        }
    }

    if (showSystemPromptDialog) {
        val currentRolePrompt = remember(appSettings.aiSystemPrompt) {
            GeminiInteractionsClient.extractUserRolePrompt(appSettings.aiSystemPrompt)
                .ifBlank { GeminiInteractionsClient.DEFAULT_ROLE_PROMPT }
        }
        val activeEnvironmentPrompt = remember(
            appSettings.aiExperimentalWriteTools,
            appSettings.aiExperimentalTerminalAccess,
        ) {
            GeminiInteractionsClient.buildEnvironmentPrompt(
                writeToolsEnabled = appSettings.aiExperimentalWriteTools,
                terminalEnabled = appSettings.aiExperimentalTerminalAccess,
            )
        }
        var promptDraft by remember(appSettings.aiSystemPrompt) {
            mutableStateOf(currentRolePrompt)
        }
        var showResetPromptConfirm by remember { mutableStateOf(false) }

        MonolithDialog(
            onDismissRequest = { showSystemPromptDialog = false },
            title = "Systemprompt",
            wide = true,
            dismissLabel = "Abbrechen",
            onDismissClick = { showSystemPromptDialog = false },
            confirmLabel = "Speichern",
            onConfirmClick = {
                val cleaned = GeminiInteractionsClient.extractUserRolePrompt(promptDraft)
                AppSettingsStore.update { it.copy(aiSystemPrompt = cleaned) }
                showSystemPromptDialog = false
            },
        ) {
            OutlinedTextField(
                value = promptDraft,
                onValueChange = { promptDraft = it },
                label = { Text("1. Rolle & Verhalten (eigener Teil)") },
                minLines = 4,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedButton(
                onClick = { showResetPromptConfirm = true },
                shape = LocalMonolithButtonShape.current,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Auf Standard zurücksetzen")
            }

            Text(
                text = "2. Umgebung & Werkzeuge (automatisch nach Einstellung, markierbar):",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )

            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth(),
            ) {
                SelectionContainer {
                    Text(
                        text = activeEnvironmentPrompt,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                }
            }
        }

        if (showResetPromptConfirm) {
            MonolithDialog(
                onDismissRequest = { showResetPromptConfirm = false },
                title = "Systemprompt zurücksetzen",
                dismissLabel = "Abbrechen",
                onDismissClick = { showResetPromptConfirm = false },
                confirmLabel = "Zurücksetzen",
                onConfirmClick = {
                    showResetPromptConfirm = false
                    promptDraft = GeminiInteractionsClient.DEFAULT_ROLE_PROMPT
                    AppSettingsStore.update { it.copy(aiSystemPrompt = "") }
                },
            ) {
                Text("Möchtest du deinen eigenen Prompt-Teil wirklich auf den Standard zurücksetzen?")
            }
        }
    }

    if (showApiKeyDialog) {
        var newLabel by remember { mutableStateOf("") }
        var newKey by remember { mutableStateOf("") }
        var showNewKey by remember { mutableStateOf(false) }

        MonolithDialog(
            onDismissRequest = { showApiKeyDialog = false },
            title = "API-Verwaltung",
            confirmLabel = "Schließen",
            onConfirmClick = { showApiKeyDialog = false },
        ) {
            if (appSettings.geminiApiKeys.isEmpty()) {
                Text(
                    text = "Noch keine API-Keys gespeichert.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                appSettings.geminiApiKeys.forEach { entry ->
                    val isSelected = entry.id == appSettings.activeGeminiApiKeyId
                    MonolithDialogOption(
                        label = entry.label,
                        subtitle = maskApiKey(entry.key),
                        selected = isSelected,
                        onClick = { AppSettingsStore.selectGeminiApiKey(entry.id) },
                        trailing = {
                            IconButton(
                                onClick = { AppSettingsStore.removeGeminiApiKey(entry.id) },
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.DeleteOutline,
                                    contentDescription = "Key entfernen",
                                )
                            }
                        },
                    )
                }
            }

            HorizontalDivider(thickness = 0.5.dp)

            Text(
                text = "Neuen Key hinzufügen",
                style = MaterialTheme.typography.labelLarge,
            )

            OutlinedTextField(
                value = newLabel,
                onValueChange = { newLabel = it },
                label = { Text("Bezeichnung (optional)") },
                placeholder = { Text("z. B. Privat, Arbeit") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = newKey,
                onValueChange = { newKey = it },
                label = { Text("Gemini-API-Key") },
                placeholder = { Text("AIza…") },
                singleLine = true,
                visualTransformation = if (showNewKey) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    IconButton(onClick = { showNewKey = !showNewKey }) {
                        Icon(
                            imageVector = if (showNewKey) {
                                Icons.Outlined.VisibilityOff
                            } else {
                                Icons.Outlined.Visibility
                            },
                            contentDescription = if (showNewKey) "Verbergen" else "Anzeigen",
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                onClick = {
                    AppSettingsStore.addGeminiApiKey(newLabel, newKey)
                    newLabel = ""
                    newKey = ""
                },
                enabled = newKey.isNotBlank(),
                shape = LocalMonolithButtonShape.current,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Hinzufügen & aktivieren")
            }
        }
    }
}

private enum class NodeShape {
    CIRCLE,
    DIAMOND,
    HEXAGON,
    TRIANGLE,
}

private data class NeuralNode(
    val xFrac: Float,
    val yFrac: Float,
    val shape: NodeShape,
    val radiusDp: Float,
    val isAccent: Boolean,
)

@Composable
private fun GeometricNeuralNetwork(modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val outline = MaterialTheme.colorScheme.outlineVariant
    val surfaceColor = MaterialTheme.colorScheme.surfaceContainerHighest

    val transition = rememberInfiniteTransition(label = "neuralPulse")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "pulsePhase",
    )

    val nodes = remember {
        listOf(
            NeuralNode(0.16f, 0.28f, NodeShape.CIRCLE, 7f, false),
            NeuralNode(0.14f, 0.52f, NodeShape.DIAMOND, 8f, true),
            NeuralNode(0.18f, 0.74f, NodeShape.TRIANGLE, 8f, false),
            NeuralNode(0.42f, 0.20f, NodeShape.HEXAGON, 9f, true),
            NeuralNode(0.40f, 0.48f, NodeShape.DIAMOND, 10f, false),
            NeuralNode(0.44f, 0.78f, NodeShape.CIRCLE, 7f, false),
            NeuralNode(0.66f, 0.30f, NodeShape.TRIANGLE, 9f, false),
            NeuralNode(0.64f, 0.62f, NodeShape.HEXAGON, 10f, true),
            NeuralNode(0.86f, 0.46f, NodeShape.HEXAGON, 12f, true),
        )
    }

    val edges = remember {
        listOf(
            0 to 3, 0 to 4,
            1 to 3, 1 to 4, 1 to 5,
            2 to 4, 2 to 5,
            3 to 6, 3 to 7,
            4 to 6, 4 to 7,
            5 to 7,
            6 to 8, 7 to 8,
            3 to 8, 4 to 8,
        )
    }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val positions = nodes.map { Offset(it.xFrac * w, it.yFrac * h) }

        edges.forEachIndexed { idx, (fromIdx, toIdx) ->
            val start = positions[fromIdx]
            val end = positions[toIdx]
            drawLine(
                color = outline.copy(alpha = 0.65f),
                start = start,
                end = end,
                strokeWidth = 1.4.dp.toPx(),
                cap = StrokeCap.Round,
            )

            val localT = (pulse + idx * 0.11f) % 1f
            if (localT < 0.75f) {
                val frac = localT / 0.75f
                val dotPos = Offset(
                    x = start.x + (end.x - start.x) * frac,
                    y = start.y + (end.y - start.y) * frac,
                )
                drawCircle(
                    color = primary.copy(alpha = (1f - frac) * 0.75f),
                    radius = 2.2.dp.toPx(),
                    center = dotPos,
                )
            }
        }

        nodes.forEachIndexed { idx, node ->
            val center = positions[idx]
            val r = node.radiusDp.dp.toPx()
            val strokeColor = if (node.isAccent) primary else secondary
            val fillColor = if (node.isAccent) primary.copy(alpha = 0.16f) else surfaceColor

            val path = when (node.shape) {
                NodeShape.CIRCLE -> null
                NodeShape.DIAMOND -> Path().apply {
                    moveTo(center.x, center.y - r)
                    lineTo(center.x + r, center.y)
                    lineTo(center.x, center.y + r)
                    lineTo(center.x - r, center.y)
                    close()
                }
                NodeShape.TRIANGLE -> Path().apply {
                    for (k in 0..2) {
                        val angle = (-90.0 + k * 120.0) * (PI / 180.0)
                        val px = center.x + (r * cos(angle)).toFloat()
                        val py = center.y + (r * sin(angle)).toFloat()
                        if (k == 0) moveTo(px, py) else lineTo(px, py)
                    }
                    close()
                }
                NodeShape.HEXAGON -> Path().apply {
                    for (k in 0..5) {
                        val angle = (k * 60.0) * (PI / 180.0)
                        val px = center.x + (r * cos(angle)).toFloat()
                        val py = center.y + (r * sin(angle)).toFloat()
                        if (k == 0) moveTo(px, py) else lineTo(px, py)
                    }
                    close()
                }
            }

            if (path == null) {
                drawCircle(color = fillColor, radius = r, center = center, style = Fill)
                drawCircle(
                    color = strokeColor,
                    radius = r,
                    center = center,
                    style = Stroke(width = 1.6.dp.toPx()),
                )
            } else {
                drawPath(path = path, color = fillColor, style = Fill)
                drawPath(
                    path = path,
                    color = strokeColor,
                    style = Stroke(width = 1.6.dp.toPx()),
                )
            }
        }
    }
}

private fun maskApiKey(key: String): String {
    val trimmed = key.trim()
    if (trimmed.length <= 10) return "••••••••"
    return "${trimmed.take(6)}••••${trimmed.takeLast(4)}"
}

@Composable
private fun ChatBubble(
    message: AiChatMessage,
    isStreamingThis: Boolean,
) {
    val isUser = message.role == ChatRole.USER
    val isError = message.role == ChatRole.ERROR
    val alignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart

    val containerColor = when {
        isUser -> MaterialTheme.colorScheme.primaryContainer
        isError -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = when {
        isUser -> MaterialTheme.colorScheme.onPrimaryContainer
        isError -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    var thoughtsExpanded by remember(message.id) { mutableStateOf(false) }
    var toolsExpanded by remember(message.id) { mutableStateOf(false) }

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = alignment,
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 320.dp),
            shape = MaterialTheme.shapes.medium,
            color = containerColor,
            contentColor = contentColor,
        ) {
            SelectionContainer {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                if (message.thoughtText.isNotBlank()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { thoughtsExpanded = !thoughtsExpanded }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Psychology,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(15.dp),
                        )
                        Text(
                            text = "Denkprozess",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            imageVector = if (thoughtsExpanded) {
                                Icons.Outlined.ExpandLess
                            } else {
                                Icons.Outlined.ExpandMore
                            },
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    AnimatedVisibility(visible = thoughtsExpanded) {
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
                        ) {
                            Text(
                                text = message.thoughtText.trim(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(8.dp),
                            )
                        }
                    }
                }

                if (message.toolCalls.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { toolsExpanded = !toolsExpanded }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Build,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = "Tool-Calls (${message.toolCalls.size})",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            imageVector = if (toolsExpanded) {
                                Icons.Outlined.ExpandLess
                            } else {
                                Icons.Outlined.ExpandMore
                            },
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    AnimatedVisibility(visible = toolsExpanded) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            message.toolCalls.forEach { trace ->
                                Surface(
                                    shape = MaterialTheme.shapes.small,
                                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Column(
                                        modifier = Modifier.padding(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(2.dp),
                                    ) {
                                        Text(
                                            text = "${trace.name}(${trace.arguments})",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontFamily = FontFamily.Monospace,
                                            ),
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                        Text(
                                            text = trace.resultPreview,
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontFamily = FontFamily.Monospace,
                                            ),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 4,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                if (!message.toolStatus.isNullOrBlank()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 1.5.dp,
                        )
                        Text(
                            text = message.toolStatus,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                if (message.text.isNotEmpty()) {
                    if (isUser || isError) {
                        Text(
                            text = message.text,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else {
                        MarkdownText(
                            text = message.text,
                            color = contentColor,
                        )
                    }
                } else if (isStreamingThis && message.toolStatus.isNullOrBlank()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 1.8.dp,
                        )
                        Text(
                            text = "Denke nach…",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                if (!isUser && !isError && !isStreamingThis && message.modelLabel != null) {
                    val metaText = if (message.thinkingLabel != null) {
                        "${message.modelLabel} · ${message.thinkingLabel}"
                    } else {
                        message.modelLabel
                    }
                    Text(
                        text = metaText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                    )
                }
            }
            }
        }
    }
}
