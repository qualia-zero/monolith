package com.monolith.app

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.text.format.Formatter
import android.webkit.MimeTypeMap
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import com.monolith.core.common.MimeNormalizer
import com.monolith.core.ui.components.MonolithDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

private const val INCOMING_CACHE_DIR = "incoming_shared"
private const val STALE_CACHE_MAX_AGE_MS = 24L * 60L * 60L * 1000L

data class IncomingSharedFile(
    val file: File,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val sourceUri: Uri,
    val isTempCache: Boolean,
)

data class IncomingIntentPayload(
    val action: String,
    val files: List<IncomingSharedFile>,
)

suspend fun resolveIncomingIntent(
    context: Context,
    intent: Intent?,
): IncomingIntentPayload? = withContext(Dispatchers.IO) {
    if (intent == null) return@withContext null
    val action = intent.action ?: return@withContext null

    val uris = extractUrisFromIntent(action, intent)
    if (uris.isEmpty()) return@withContext null

    cleanupStaleIncomingCache(context.cacheDir)

    val resolved = uris.mapNotNull { uri ->
        runCatching { resolveSingleUri(context, uri) }.getOrNull()
    }
    if (resolved.isEmpty()) return@withContext null

    IncomingIntentPayload(
        action = action,
        files = resolved,
    )
}

private fun extractUrisFromIntent(action: String, intent: Intent): List<Uri> {
    val result = LinkedHashSet<Uri>()

    when (action) {
        Intent.ACTION_VIEW -> {
            intent.data?.let { result.add(it) }
            collectClipDataUris(intent.clipData, result)
        }
        Intent.ACTION_SEND -> {
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                ?.let { result.add(it) }
            collectClipDataUris(intent.clipData, result)
        }
        Intent.ACTION_SEND_MULTIPLE -> {
            IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                ?.forEach { result.add(it) }
            collectClipDataUris(intent.clipData, result)
        }
    }

    return result.toList()
}

private fun collectClipDataUris(clipData: ClipData?, out: MutableSet<Uri>) {
    if (clipData == null) return
    for (i in 0 until clipData.itemCount) {
        clipData.getItemAt(i)?.uri?.let { out.add(it) }
    }
}

private fun resolveSingleUri(context: Context, uri: Uri): IncomingSharedFile? {
    return when (uri.scheme?.lowercase()) {
        "file" -> {
            val path = uri.path ?: return null
            val file = File(path)
            if (!file.exists() || !file.isFile) return null
            val displayName = file.name.ifBlank { "shared_file" }
            val systemMime = context.contentResolver.getType(uri)
            val mimeType = MimeNormalizer.normalizeMime(displayName, systemMime)
            IncomingSharedFile(
                file = file,
                displayName = displayName,
                mimeType = mimeType,
                sizeBytes = file.length(),
                sourceUri = uri,
                isTempCache = false,
            )
        }
        "content" -> {
            val resolver = context.contentResolver
            val systemMime = resolver.getType(uri)
            val (queriedName, queriedSize) = queryContentMetadata(context, uri)

            val rawName = queriedName?.takeIf { it.isNotBlank() }
                ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
                ?: buildFallbackFileName(systemMime)

            val safeName = sanitizeFileName(rawName)
            val mimeType = MimeNormalizer.normalizeMime(safeName, systemMime)

            val directFile = tryResolvePrimaryExternalStorageFile(context, uri)
            if (directFile != null) {
                return IncomingSharedFile(
                    file = directFile,
                    displayName = safeName,
                    mimeType = mimeType,
                    sizeBytes = if (queriedSize >= 0L) queriedSize else directFile.length(),
                    sourceUri = uri,
                    isTempCache = false,
                )
            }

            val sessionDir = File(
                File(context.cacheDir, INCOMING_CACHE_DIR),
                System.currentTimeMillis().toString(),
            )
            if (!sessionDir.exists()) {
                sessionDir.mkdirs()
            }
            val targetFile = resolveUniqueFile(sessionDir, safeName)

            resolver.openInputStream(uri)?.use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            } ?: return null

            val finalSize = if (queriedSize >= 0L) queriedSize else targetFile.length()
            IncomingSharedFile(
                file = targetFile,
                displayName = safeName,
                mimeType = mimeType,
                sizeBytes = finalSize,
                sourceUri = uri,
                isTempCache = true,
            )
        }
        else -> null
    }
}

private fun queryContentMetadata(context: Context, uri: Uri): Pair<String?, Long> {
    var displayName: String? = null
    var sizeBytes = -1L
    runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                    displayName = cursor.getString(nameIndex)
                }
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    sizeBytes = cursor.getLong(sizeIndex)
                }
            }
        }
    }
    return displayName to sizeBytes
}

private fun tryResolvePrimaryExternalStorageFile(context: Context, uri: Uri): File? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || !Environment.isExternalStorageManager()) {
        return null
    }
    if (uri.authority != "com.android.externalstorage.documents") return null
    if (!DocumentsContract.isDocumentUri(context, uri)) return null

    val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
    val colonIndex = docId.indexOf(':')
    if (colonIndex <= 0) return null

    val volumeId = docId.substring(0, colonIndex)
    if (!volumeId.equals("primary", ignoreCase = true)) return null

    val relativePath = docId.substring(colonIndex + 1).trimStart('/')
    if (relativePath.isEmpty()) return null

    val root = Environment.getExternalStorageDirectory()
    val candidate = File(root, relativePath)
    val canonicalRoot = runCatching { root.canonicalFile }.getOrNull() ?: return null
    val canonicalCandidate = runCatching { candidate.canonicalFile }.getOrNull() ?: return null
    if (!canonicalCandidate.path.startsWith(canonicalRoot.path + File.separator)) {
        return null
    }
    return if (canonicalCandidate.exists() && canonicalCandidate.isFile && canonicalCandidate.canRead()) {
        canonicalCandidate
    } else {
        null
    }
}

private fun cleanupStaleIncomingCache(cacheDir: File) {
    val root = File(cacheDir, INCOMING_CACHE_DIR)
    if (!root.exists() || !root.isDirectory) return
    val cutoff = System.currentTimeMillis() - STALE_CACHE_MAX_AGE_MS
    root.listFiles()?.forEach { child ->
        if (child.lastModified() < cutoff) {
            child.deleteRecursively()
        }
    }
}

private fun buildFallbackFileName(mimeType: String?): String {
    val ext = mimeType?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
    val base = "shared_${System.currentTimeMillis()}"
    return if (!ext.isNullOrBlank()) "$base.$ext" else base
}

private fun sanitizeFileName(rawName: String): String {
    val cleaned = rawName
        .replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1F]"), "_")
        .trim()
    return if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") {
        "shared_file"
    } else {
        cleaned
    }
}

private fun resolveUniqueFile(directory: File, fileName: String): File {
    val direct = File(directory, fileName)
    if (!direct.exists()) return direct

    val dotIndex = fileName.lastIndexOf('.')
    val hasExtension = dotIndex > 0 && dotIndex < fileName.length - 1
    val baseName = if (hasExtension) fileName.substring(0, dotIndex) else fileName
    val extension = if (hasExtension) fileName.substring(dotIndex) else ""

    var counter = 1
    while (true) {
        val candidate = File(directory, "$baseName ($counter)$extension")
        if (!candidate.exists()) return candidate
        counter++
    }
}

suspend fun copyIncomingFilesToDirectory(
    files: List<IncomingSharedFile>,
    targetDir: File,
): Result<Int> = withContext(Dispatchers.IO) {
    runCatching {
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            error("Zielordner konnte nicht erstellt werden: ${targetDir.absolutePath}")
        }
        require(targetDir.isDirectory) {
            "Zielpfad ist kein Ordner: ${targetDir.absolutePath}"
        }

        var copiedCount = 0
        for (shared in files) {
            val safeName = sanitizeFileName(shared.displayName)
            val destination = resolveUniqueFile(targetDir, safeName)
            shared.file.inputStream().use { input ->
                destination.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            copiedCount++
        }
        copiedCount
    }
}

@Composable
fun IncomingShareDialog(
    files: List<IncomingSharedFile>,
    onSaveToFolder: () -> Unit,
    onOpenFiles: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val totalSize = files.sumOf { it.sizeBytes.coerceAtLeast(0L) }
    val formattedTotalSize = Formatter.formatShortFileSize(context, totalSize)
    val title = if (files.size == 1) {
        "Geteilte Datei"
    } else {
        "${files.size} geteilte Dateien"
    }

    MonolithDialog(
        onDismissRequest = onDismiss,
        title = title,
        dismissLabel = "Abbrechen",
        onDismissClick = onDismiss,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val previewFiles = files.take(5)
            previewFiles.forEach { item ->
                Text(
                    text = item.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (files.size > previewFiles.size) {
                Text(
                    text = "… und ${files.size - previewFiles.size} weitere",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = if (files.size == 1) {
                    "$formattedTotalSize • ${files.first().mimeType}"
                } else {
                    "Gesamt: $formattedTotalSize"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IncomingShareActionCard(
                label = "In Ordner speichern",
                icon = Icons.Outlined.CreateNewFolder,
                onClick = onSaveToFolder,
            )
            IncomingShareActionCard(
                label = "Öffnen",
                icon = Icons.AutoMirrored.Outlined.OpenInNew,
                onClick = onOpenFiles,
            )
        }
    }
}

@Composable
private fun IncomingShareActionCard(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = CutCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}
