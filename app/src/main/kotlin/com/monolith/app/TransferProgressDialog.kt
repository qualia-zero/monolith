package com.monolith.app

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.monolith.core.model.TransferOperation
import com.monolith.core.model.TransferProgress
import com.monolith.core.ui.components.MonolithDialog
import com.monolith.core.ui.theme.LocalMonolithButtonShape
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TransferProgressDialog(
    progress: TransferProgress,
    activeTasks: List<TransferProgress> = emptyList(),
    onSelectTask: (Long) -> Unit = {},
    onDismiss: () -> Unit,
    onCancel: () -> Unit = {},
) {
    val title = when (progress.operation) {
        TransferOperation.DOWNLOAD -> when {
            progress.isCancelled -> "Download abgebrochen."
            progress.errorMessage != null -> "Download fehlgeschlagen."
            progress.isFinished -> "Download abgeschlossen."
            else -> "Dateien herunterladen…"
        }
        TransferOperation.MOVE -> when {
            progress.isCancelled -> "Verschieben abgebrochen."
            progress.errorMessage != null -> "Verschieben fehlgeschlagen."
            progress.isFinished -> "Verschieben abgeschlossen."
            else -> "Dateien verschieben…"
        }
        TransferOperation.EXTRACT -> when {
            progress.isCancelled -> "Entpacken abgebrochen."
            progress.errorMessage != null -> "Entpacken fehlgeschlagen."
            progress.isFinished -> "Entpacken abgeschlossen."
            else -> "Archiv entpacken…"
        }
        TransferOperation.COMPRESS -> when {
            progress.isCancelled -> "Packen abgebrochen."
            progress.errorMessage != null -> "Packen fehlgeschlagen."
            progress.isFinished -> "Packen abgeschlossen."
            else -> "Archiv packen…"
        }
        else -> when {
            progress.isCancelled -> "Kopieren abgebrochen."
            progress.errorMessage != null -> "Kopieren fehlgeschlagen."
            progress.isFinished -> "Kopieren abgeschlossen."
            else -> "Dateien kopieren…"
        }
    }

    val targetFraction = when {
        progress.isFinished && progress.errorMessage == null -> 1f
        progress.totalBytes > 0L -> (progress.bytesProcessed.toFloat() / progress.totalBytes.toFloat()).coerceIn(0f, 1f)
        progress.totalItems > 1 -> (progress.processedItems.toFloat() / progress.totalItems.toFloat()).coerceIn(0f, 1f)
        else -> null
    }

    val animatedFraction by animateFloatAsState(
        targetValue = targetFraction ?: 0f,
        label = "transfer_progress",
    )

    MonolithDialog(
        onDismissRequest = onDismiss,
        title = title,
        confirmLabel = if (progress.isFinished) "Schließen" else "Abbrechen",
        onConfirmClick = if (progress.isFinished) onDismiss else onCancel,
    ) {
        if (activeTasks.size > 1) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                activeTasks.forEachIndexed { index, task ->
                    val queued = task.currentFileName == "In Warteschlange…"
                    val chipLabel = if (queued) "#${index + 1} (Warteschlange)" else "#${index + 1}"
                    FilterChip(
                        selected = task.taskId == progress.taskId,
                        shape = LocalMonolithButtonShape.current,
                        onClick = { onSelectTask(task.taskId) },
                        label = { Text(chipLabel, maxLines = 1, softWrap = false) },
                    )
                }
            }
        }

        Text(
            text = progress.currentFileName.ifBlank { "Wird vorbereitet…" },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        if (targetFraction != null) {
            LinearProgressIndicator(
                progress = { animatedFraction },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
            )
        }

        val statusLine = buildString {
            val itemTotal = progress.totalItems.coerceAtLeast(1); append("${progress.processedItems}/$itemTotal ${if (itemTotal == 1) "Element" else "Elemente"}")
            if (progress.totalBytes > 0L) {
                append(" · ")
                append(formatBytes(progress.bytesProcessed))
                append(" / ")
                append(formatBytes(progress.totalBytes))
            }
            if (!progress.isFinished && progress.bytesPerSecond > 0L) {
                append(" · ")
                append(formatBytes(progress.bytesPerSecond))
                append("/s")
            }
        }
        Text(
            text = statusLine,
            style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )

        if (progress.errorMessage != null) {
            Text(
                text = progress.errorMessage.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unitIndex = -1
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex++
    }
    return String.format(Locale.getDefault(), "%.1f %s", value, units[unitIndex])
}
