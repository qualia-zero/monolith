package com.monolith.app

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.monolith.core.model.TransferOperation
import com.monolith.core.model.TransferProgress
import java.util.Locale

@Composable
fun TransferProgressDialog(
    progress: TransferProgress,
    onDismiss: () -> Unit,
) {
    val title = when (progress.operation) {
        TransferOperation.MOVE -> when {
            progress.errorMessage != null -> "Verschieben fehlgeschlagen"
            progress.isFinished -> "Verschieben abgeschlossen"
            else -> "Dateien verschieben"
        }
        TransferOperation.DOWNLOAD -> when {
            progress.errorMessage != null -> "Download fehlgeschlagen"
            progress.isFinished -> "Download abgeschlossen"
            else -> "Dateien herunterladen"
        }
        else -> when {
            progress.errorMessage != null -> "Kopieren fehlgeschlagen"
            progress.isFinished -> "Kopieren abgeschlossen"
            else -> "Dateien kopieren"
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

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        ),
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 18.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = progress.currentFileName.ifBlank { "Wird vorbereitet…" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(14.dp))

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

                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${progress.processedItems}/${progress.totalItems.coerceAtLeast(1)} Elemente",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val byteSummary = buildString {
                        if (progress.totalBytes > 0L) {
                            append(formatBytes(progress.bytesProcessed))
                            append(" / ")
                            append(formatBytes(progress.totalBytes))
                        }
                        if (!progress.isFinished && progress.bytesPerSecond > 0L) {
                            if (isNotEmpty()) append(" · ")
                            append(formatBytes(progress.bytesPerSecond))
                            append("/s")
                        }
                    }
                    if (byteSummary.isNotEmpty()) {
                        Text(
                            text = byteSummary,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (progress.errorMessage != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = progress.errorMessage.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(if (progress.isFinished) "Schließen" else "Im Hintergrund")
                    }
                }
            }
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
