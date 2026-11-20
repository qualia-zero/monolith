package com.monolith.feature.viewers

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.monolith.core.ui.components.MonolithDialog
import com.monolith.core.ui.theme.LocalMonolithButtonShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale

private const val LARGE_FILE_WARNING_BYTES = 2L * 1024L * 1024L

private data class DecodedTextFile(
    val content: String,
    val charset: Charset,
    val hasUtf8Bom: Boolean,
    val encodingLabel: String,
    val sizeBytes: Long,
)

/**
 * Material 3 text editor screen with encoding detection, search & highlight,
 * line-wrap/monospace/read-only toggles, and unsaved-changes confirmation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextEditorScreen(
    path: String,
    onNavigateBack: () -> Unit,
) {
    val file = remember(path) { File(path) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var isLoading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var isLargeFileWarning by remember { mutableStateOf(false) }
    var largeFileSizeBytes by remember { mutableStateOf(0L) }

    var detectedCharset by remember { mutableStateOf(Charsets.UTF_8) }
    var hasUtf8Bom by remember { mutableStateOf(false) }
    var encodingLabel by remember { mutableStateOf("UTF-8") }

    var originalText by remember { mutableStateOf("") }
    var textFieldValue by remember { mutableStateOf(TextFieldValue("")) }

    var isReadOnly by remember { mutableStateOf(false) }
    var isWordWrap by remember { mutableStateOf(true) }
    var isMonospace by remember { mutableStateOf(true) }
    var isSaving by remember { mutableStateOf(false) }

    var isSearchVisible by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var currentMatchIndex by remember { mutableIntStateOf(0) }

    var showOverflowMenu by remember { mutableStateOf(false) }
    var showUnsavedDialog by remember { mutableStateOf(false) }

    val isModified = !isLoading && loadError == null && textFieldValue.text != originalText

    val verticalScrollState = rememberScrollState()
    val horizontalScrollState = rememberScrollState()
    var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }

    val searchMatches = remember(textFieldValue.text, searchQuery) {
        findMatches(textFieldValue.text, searchQuery)
    }

    LaunchedEffect(searchMatches.size) {
        currentMatchIndex = if (searchMatches.isEmpty()) {
            0
        } else {
            currentMatchIndex.coerceIn(0, searchMatches.lastIndex)
        }
    }

    LaunchedEffect(path) {
        isLoading = true
        loadError = null
        try {
            val decoded = loadTextFile(file)
            originalText = decoded.content
            textFieldValue = TextFieldValue(decoded.content)
            detectedCharset = decoded.charset
            hasUtf8Bom = decoded.hasUtf8Bom
            encodingLabel = decoded.encodingLabel
            largeFileSizeBytes = decoded.sizeBytes
            isLargeFileWarning = decoded.sizeBytes > LARGE_FILE_WARNING_BYTES
        } catch (e: Exception) {
            loadError = e.localizedMessage ?: "Datei konnte nicht geladen werden"
        } finally {
            isLoading = false
        }
    }

    fun jumpToMatch(index: Int) {
        if (searchMatches.isEmpty()) return
        val safeIndex = index.coerceIn(0, searchMatches.lastIndex)
        currentMatchIndex = safeIndex
        val range = searchMatches[safeIndex]
        textFieldValue = textFieldValue.copy(
            selection = TextRange(range.first, range.last + 1),
        )
        val layout = textLayoutResult ?: return
        val lineTop = layout.getBoundingBox(range.first).top
        scope.launch {
            verticalScrollState.animateScrollTo(lineTop.toInt().coerceAtLeast(0))
        }
    }

    fun saveFile(closeAfterSave: Boolean = false) {
        if (isReadOnly || isSaving) return
        scope.launch {
            isSaving = true
            try {
                saveTextFile(
                    file = file,
                    content = textFieldValue.text,
                    charset = detectedCharset,
                    includeUtf8Bom = hasUtf8Bom,
                )
                originalText = textFieldValue.text
                if (closeAfterSave) {
                    onNavigateBack()
                } else {
                    snackbarHostState.showSnackbar("Gespeichert")
                }
            } catch (e: Exception) {
                snackbarHostState.showSnackbar(
                    "Fehler beim Speichern: ${e.localizedMessage ?: "Unbekannter Fehler"}",
                )
            } finally {
                isSaving = false
            }
        }
    }

    fun handleBack() {
        if (isModified) {
            showUnsavedDialog = true
        } else {
            onNavigateBack()
        }
    }

    BackHandler(enabled = isModified && !showUnsavedDialog) {
        showUnsavedDialog = true
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { handleBack() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Zurück",
                        )
                    }
                },
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = file.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (isModified) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary),
                            )
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            isSearchVisible = !isSearchVisible
                            if (!isSearchVisible) {
                                searchQuery = ""
                            }
                        },
                        enabled = !isLoading && loadError == null,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Suchen",
                        )
                    }

                    IconButton(
                        onClick = { saveFile() },
                        enabled = isModified && !isReadOnly && !isSaving,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Save,
                            contentDescription = "Speichern",
                        )
                    }

                    Box {
                        IconButton(onClick = { showOverflowMenu = true }) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "Weitere Optionen",
                            )
                        }

                        DropdownMenu(
                            expanded = showOverflowMenu,
                            onDismissRequest = { showOverflowMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Nur-Lese-Modus") },
                                trailingIcon = {
                                    if (isReadOnly) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                        )
                                    }
                                },
                                onClick = {
                                    isReadOnly = !isReadOnly
                                    showOverflowMenu = false
                                },
                            )

                            DropdownMenuItem(
                                text = { Text("Zeilenumbruch") },
                                trailingIcon = {
                                    if (isWordWrap) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                        )
                                    }
                                },
                                onClick = {
                                    isWordWrap = !isWordWrap
                                    showOverflowMenu = false
                                },
                            )

                            DropdownMenuItem(
                                text = { Text("Monospace") },
                                trailingIcon = {
                                    if (isMonospace) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                        )
                                    }
                                },
                                onClick = {
                                    isMonospace = !isMonospace
                                    showOverflowMenu = false
                                },
                            )

                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = "Zeichensatz: $encodingLabel",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                                enabled = false,
                                onClick = {},
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            AnimatedVisibility(visible = isLargeFileWarning) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Große Datei (${formatEditorFileSize(largeFileSizeBytes)}) – Bearbeitung kann langsam sein.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = { isLargeFileWarning = false },
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Hinweis schließen",
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }

            AnimatedVisibility(visible = isSearchVisible) {
                SearchBar(
                    query = searchQuery,
                    onQueryChange = {
                        searchQuery = it
                        currentMatchIndex = 0
                    },
                    matchCount = searchMatches.size,
                    currentMatchIndex = currentMatchIndex,
                    onPrevMatch = {
                        val prev = if (currentMatchIndex > 0) currentMatchIndex - 1 else searchMatches.lastIndex
                        jumpToMatch(prev)
                    },
                    onNextMatch = {
                        val next = if (currentMatchIndex < searchMatches.lastIndex) currentMatchIndex + 1 else 0
                        jumpToMatch(next)
                    },
                    onClose = {
                        isSearchVisible = false
                        searchQuery = ""
                    },
                )
            }

            when {
                isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }

                loadError != null -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = loadError.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                else -> {
                    val highlightColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                    val activeHighlightColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.48f)
                    val activeMatchRange = searchMatches.getOrNull(currentMatchIndex)

                    val visualTransformation = remember(
                        searchMatches,
                        activeMatchRange,
                        highlightColor,
                        activeHighlightColor,
                    ) {
                        if (searchMatches.isEmpty()) {
                            VisualTransformation.None
                        } else {
                            SearchHighlightVisualTransformation(
                                matches = searchMatches,
                                activeMatch = activeMatchRange,
                                highlightColor = highlightColor,
                                activeHighlightColor = activeHighlightColor,
                            )
                        }
                    }

                    val editorTextStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontFamily = if (isMonospace) FontFamily.Monospace else FontFamily.SansSerif,
                        fontSize = 14.sp,
                        lineHeight = 22.sp,
                    )

                    BoxWithConstraints(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(verticalScrollState),
                    ) {
                        val minWidth = maxWidth
                        val minHeight = maxHeight

                        val fieldModifier = if (isWordWrap) {
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = minHeight)
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                        } else {
                            Modifier
                                .horizontalScroll(horizontalScrollState)
                                .widthIn(min = minWidth)
                                .heightIn(min = minHeight)
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                        }

                        BasicTextField(
                            value = textFieldValue,
                            onValueChange = { newValue ->
                                if (!isReadOnly) {
                                    textFieldValue = newValue
                                }
                            },
                            readOnly = isReadOnly,
                            textStyle = editorTextStyle,
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            visualTransformation = visualTransformation,
                            onTextLayout = { textLayoutResult = it },
                            modifier = fieldModifier,
                        )
                    }
                }
            }
        }
    }

    if (showUnsavedDialog) {
        MonolithDialog(
            onDismissRequest = { showUnsavedDialog = false },
            title = "Ungespeicherte Änderungen",
        ) {
            Text("Möchtest du die Änderungen vor dem Schließen speichern?")
            val canSave = !isReadOnly && !isSaving
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = {
                        showUnsavedDialog = false
                        onNavigateBack()
                    },
                    shape = LocalMonolithButtonShape.current,
                ) {
                    Text("Verwerfen")
                }
                Spacer(modifier = Modifier.weight(1f))
                TextButton(
                    onClick = { showUnsavedDialog = false },
                    shape = LocalMonolithButtonShape.current,
                ) {
                    Text("Abbrechen")
                }
                OutlinedButton(
                    onClick = {
                        showUnsavedDialog = false
                        saveFile(closeAfterSave = true)
                    },
                    enabled = canSave,
                    shape = LocalMonolithButtonShape.current,
                    border = BorderStroke(
                        1.dp,
                        if (canSave) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                    ),
                ) {
                    Text("Speichern")
                }
            }
        }
    }
}

@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    matchCount: Int,
    currentMatchIndex: Int,
    onPrevMatch: () -> Unit,
    onNextMatch: () -> Unit,
    onClose: () -> Unit,
) {
    Surface(
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = { Text("Suchen…") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )

            Spacer(modifier = Modifier.width(8.dp))

            val counterText = when {
                query.isEmpty() -> ""
                matchCount == 0 -> "0 / 0"
                else -> "${currentMatchIndex + 1} / $matchCount"
            }
            if (counterText.isNotEmpty()) {
                Text(
                    text = counterText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }

            IconButton(
                onClick = onPrevMatch,
                enabled = matchCount > 0,
            ) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowUp,
                    contentDescription = "Vorheriger Treffer",
                )
            }

            IconButton(
                onClick = onNextMatch,
                enabled = matchCount > 0,
            ) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = "Nächster Treffer",
                )
            }

            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Suche schließen",
                )
            }
        }
    }
}

private class SearchHighlightVisualTransformation(
    private val matches: List<IntRange>,
    private val activeMatch: IntRange?,
    private val highlightColor: Color,
    private val activeHighlightColor: Color,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val annotated = buildAnnotatedString {
            append(text.text)
            for (range in matches) {
                if (range.first >= 0 && range.last < text.length) {
                    val isCurrent = range == activeMatch
                    addStyle(
                        style = SpanStyle(
                            background = if (isCurrent) activeHighlightColor else highlightColor,
                        ),
                        start = range.first,
                        end = range.last + 1,
                    )
                }
            }
        }
        return TransformedText(annotated, OffsetMapping.Identity)
    }
}

private fun findMatches(text: String, query: String): List<IntRange> {
    if (query.isEmpty() || text.isEmpty()) return emptyList()
    val results = mutableListOf<IntRange>()
    var startIndex = 0
    while (startIndex <= text.length - query.length) {
        val found = text.indexOf(query, startIndex = startIndex, ignoreCase = true)
        if (found < 0) break
        results.add(found until (found + query.length))
        startIndex = found + query.length.coerceAtLeast(1)
    }
    return results
}

private suspend fun loadTextFile(file: File): DecodedTextFile = withContext(Dispatchers.IO) {
    val bytes = file.readBytes()
    val sizeBytes = bytes.size.toLong()
    val hasUtf8Bom = bytes.size >= 3 &&
        bytes[0] == 0xEF.toByte() &&
        bytes[1] == 0xBB.toByte() &&
        bytes[2] == 0xBF.toByte()

    val payloadOffset = if (hasUtf8Bom) 3 else 0
    val payloadLength = bytes.size - payloadOffset

    try {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val decoded = decoder.decode(ByteBuffer.wrap(bytes, payloadOffset, payloadLength)).toString()
        DecodedTextFile(
            content = decoded,
            charset = Charsets.UTF_8,
            hasUtf8Bom = hasUtf8Bom,
            encodingLabel = if (hasUtf8Bom) "UTF-8 (BOM)" else "UTF-8",
            sizeBytes = sizeBytes,
        )
    } catch (_: CharacterCodingException) {
        val decoded = String(bytes, Charsets.ISO_8859_1)
        DecodedTextFile(
            content = decoded,
            charset = Charsets.ISO_8859_1,
            hasUtf8Bom = false,
            encodingLabel = "ISO-8859-1",
            sizeBytes = sizeBytes,
        )
    }
}

private suspend fun saveTextFile(
    file: File,
    content: String,
    charset: Charset,
    includeUtf8Bom: Boolean,
) = withContext(Dispatchers.IO) {
    val encoded = content.toByteArray(charset)
    if (includeUtf8Bom && charset == Charsets.UTF_8) {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        file.writeBytes(bom + encoded)
    } else {
        file.writeBytes(encoded)
    }
}

private fun formatEditorFileSize(bytes: Long): String {
    return when {
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> String.format(Locale.GERMAN, "%.1f KB", bytes / 1024.0)
        else -> String.format(Locale.GERMAN, "%.2f MB", bytes / (1024.0 * 1024.0))
    }
}
