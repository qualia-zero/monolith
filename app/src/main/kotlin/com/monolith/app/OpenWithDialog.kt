package com.monolith.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toBitmap
import com.monolith.core.model.DefaultAppAssociation
import com.monolith.core.model.FileCategory
import com.monolith.core.model.VfsNode
import com.monolith.core.ui.components.MonolithDialog
import com.monolith.core.ui.theme.LocalMonolithButtonShape
import java.io.File
import java.util.Locale

internal const val HANDLER_INTERNAL_IMAGE = "internal:image"
internal const val HANDLER_INTERNAL_VIDEO = "internal:video"
internal const val HANDLER_INTERNAL_AUDIO = "internal:audio"
internal const val HANDLER_INTERNAL_TEXT = "internal:text"
internal const val HANDLER_INTERNAL_ARCHIVE = "internal:archive"

internal data class OpenWithOption(
    val handlerId: String,
    val label: String,
    val iconVector: ImageVector? = null,
    val iconBitmap: ImageBitmap? = null,
    val packageName: String? = null,
    val activityName: String? = null,
) {
    fun toAssociation(extension: String): DefaultAppAssociation = DefaultAppAssociation(
        extension = extension,
        handlerId = handlerId,
        handlerLabel = label,
        packageName = packageName,
        activityName = activityName,
    )
}

internal fun resolveFileExtension(node: VfsNode): String =
    node.name.substringAfterLast('.', "")
        .trim()
        .removePrefix(".")
        .lowercase(Locale.ROOT)

internal fun resolveMimeType(node: VfsNode): String {
    val rawMime = node.mimeType.trim()
    if (rawMime.isNotBlank() && rawMime != "*/*" && rawMime != "application/octet-stream") {
        return rawMime
    }
    val ext = resolveFileExtension(node)
    val mapped = if (ext.isNotEmpty()) {
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
    } else {
        null
    }
    if (!mapped.isNullOrBlank()) return mapped
    return when (node.category) {
        FileCategory.IMAGE -> "image/*"
        FileCategory.VIDEO -> "video/*"
        FileCategory.AUDIO -> "audio/*"
        FileCategory.TEXT -> "text/plain"
        else -> rawMime.ifBlank { "*/*" }
    }
}

private fun categoryWildcardMime(category: FileCategory): String? = when (category) {
    FileCategory.IMAGE -> "image/*"
    FileCategory.VIDEO -> "video/*"
    FileCategory.AUDIO -> "audio/*"
    FileCategory.TEXT -> "text/*"
    else -> null
}

internal fun buildOpenWithOptions(context: Context, node: VfsNode): List<OpenWithOption> {
    val options = mutableListOf<OpenWithOption>()

    when (node.category) {
        FileCategory.IMAGE -> options += OpenWithOption(
            handlerId = HANDLER_INTERNAL_IMAGE,
            label = "Monolith-Bildbetrachter",
            iconVector = Icons.Outlined.Image,
        )
        FileCategory.VIDEO -> options += OpenWithOption(
            handlerId = HANDLER_INTERNAL_VIDEO,
            label = "Monolith-Videoplayer",
            iconVector = Icons.Outlined.Movie,
        )
        FileCategory.AUDIO -> options += OpenWithOption(
            handlerId = HANDLER_INTERNAL_AUDIO,
            label = "Monolith-Musikplayer",
            iconVector = Icons.Outlined.MusicNote,
        )
        FileCategory.TEXT -> options += OpenWithOption(
            handlerId = HANDLER_INTERNAL_TEXT,
            label = "Monolith-Texteditor",
            iconVector = Icons.Outlined.Description,
        )
        FileCategory.ARCHIVE -> options += OpenWithOption(
            handlerId = HANDLER_INTERNAL_ARCHIVE,
            label = "Monolith-Archivmanager",
            iconVector = Icons.Outlined.FolderZip,
        )
        else -> Unit
    }

    val pm = context.packageManager
    val mimeType = resolveMimeType(node)
    val queryUri = if (node.path.startsWith("gdrive://") || node.path.startsWith("smb://")) {
        Uri.Builder()
            .scheme("content")
            .authority("${context.packageName}.fileprovider")
            .appendPath(node.name.ifBlank { "file" })
            .build()
    } else {
        runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(node.path))
        }.getOrElse { Uri.fromFile(File(node.path)) }
    }

    val mimeQueries = buildList {
        add(mimeType)
        val wildcard = categoryWildcardMime(node.category)
        if (wildcard != null && wildcard != mimeType) {
            add(wildcard)
        }
    }

    val seen = HashSet<String>()
    val externalOptions = mutableListOf<OpenWithOption>()
    for (queryMime in mimeQueries) {
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(queryUri, queryMime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val resolved = pm.queryIntentActivities(viewIntent, PackageManager.MATCH_DEFAULT_ONLY)
        resolved.forEach { info ->
            val act = info.activityInfo ?: return@forEach
            if (act.packageName == context.packageName) return@forEach
            val key = "${act.packageName}/${act.name}"
            if (!seen.add(key)) return@forEach
            val label = info.loadLabel(pm)?.toString()?.trim().orEmpty().ifBlank { act.packageName }
            val iconBmp = runCatching { info.loadIcon(pm).toBitmap(width = 72, height = 72).asImageBitmap() }.getOrNull()
            externalOptions += OpenWithOption(
                handlerId = "pkg:$key",
                label = label,
                iconBitmap = iconBmp,
                packageName = act.packageName,
                activityName = act.name,
            )
        }
    }
    externalOptions.sortBy { it.label.lowercase(Locale.ROOT) }
    options += externalOptions

    if (node.category != FileCategory.TEXT && options.isEmpty()) {
        options += OpenWithOption(
            handlerId = HANDLER_INTERNAL_TEXT,
            label = "Monolith-Texteditor",
            iconVector = Icons.Outlined.Description,
        )
    }

    return options
}

@Composable
internal fun OpenWithDialog(
    node: VfsNode,
    onDismiss: () -> Unit,
    onSelect: (option: OpenWithOption, always: Boolean) -> Unit,
) {
    val context = LocalContext.current
    val options = remember(node.path) { buildOpenWithOptions(context, node) }
    var selected by remember(node.path, options) { mutableStateOf(options.firstOrNull()) }
    val ext = remember(node.path) { resolveFileExtension(node) }

    MonolithDialog(
        onDismissRequest = onDismiss,
        title = "Öffnen mit",
    ) {
        Text(
            text = node.name,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        if (options.isEmpty()) {
            Text(
                text = "Keine passende App für diesen Dateityp gefunden.",
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                options.forEach { option ->
                    val isSelected = selected?.handlerId == option.handlerId
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = CutCornerShape(8.dp),
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                        border = BorderStroke(
                            width = if (isSelected) 1.3.dp else 1.dp,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            },
                        ),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selected = option }
                                .padding(horizontal = 10.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = { selected = option },
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            when {
                                option.iconBitmap != null -> Image(
                                    bitmap = option.iconBitmap,
                                    contentDescription = null,
                                    modifier = Modifier.size(28.dp),
                                )
                                option.iconVector != null -> Icon(
                                    imageVector = option.iconVector,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(28.dp),
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = option.label,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }

        val alwaysEnabled = selected != null && ext.isNotEmpty()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = onDismiss,
                shape = LocalMonolithButtonShape.current,
            ) {
                Text("Abbrechen")
            }
            Spacer(modifier = Modifier.weight(1f))
            TextButton(
                onClick = {
                    val choice = selected
                    if (choice != null) onSelect(choice, false) else onDismiss()
                },
                enabled = selected != null,
                shape = LocalMonolithButtonShape.current,
            ) {
                Text("Einmal")
            }
            OutlinedButton(
                onClick = {
                    val choice = selected ?: return@OutlinedButton
                    onSelect(choice, true)
                },
                enabled = alwaysEnabled,
                shape = LocalMonolithButtonShape.current,
                border = BorderStroke(
                    1.dp,
                    if (alwaysEnabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    },
                ),
            ) {
                Text("Immer")
            }
        }
    }
}
