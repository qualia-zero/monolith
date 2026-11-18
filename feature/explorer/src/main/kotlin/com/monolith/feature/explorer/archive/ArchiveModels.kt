package com.monolith.feature.explorer.archive

import java.io.File
import java.io.IOException
import java.util.Locale

enum class ArchiveFormat(
    val label: String,
    val extension: String,
    val canCreate: Boolean,
    val supportsEncryption: Boolean,
    val supportsSplit: Boolean,
    val supportsSolid: Boolean,
) {
    ZIP("ZIP", "zip", canCreate = true, supportsEncryption = true, supportsSplit = true, supportsSolid = false),
    SEVEN_Z("7z", "7z", canCreate = true, supportsEncryption = true, supportsSplit = true, supportsSolid = true),
    RAR("RAR", "rar", canCreate = false, supportsEncryption = true, supportsSplit = true, supportsSolid = true),
    TAR_GZ("TAR.GZ", "tar.gz", canCreate = true, supportsEncryption = false, supportsSplit = true, supportsSolid = true),
    TAR_XZ("TAR.XZ", "tar.xz", canCreate = true, supportsEncryption = false, supportsSplit = true, supportsSolid = true),
}

enum class ArchiveCompressionLevel(val label: String) {
    STORE("Speichern (0%)"),
    FAST("Schnell"),
    NORMAL("Standard"),
    MAXIMUM("Maximal"),
}

enum class ArchiveEncryptionMethod(val label: String) {
    NONE("Keine"),
    AES_256("AES-256 (Stark)"),
    ZIP_STANDARD("ZipCrypto (Kompatibel)"),
}

enum class SplitVolumePreset(
    val label: String,
    val sizeBytes: Long?,
) {
    NONE("Einzelarchiv (Kein Split)", null),
    MB_10("10 MB Teile", 10L * 1024 * 1024),
    MB_50("50 MB Teile", 50L * 1024 * 1024),
    MB_100("100 MB Teile", 100L * 1024 * 1024),
    MB_500("500 MB Teile", 500L * 1024 * 1024),
    GB_1("1 GB Teile", 1024L * 1024 * 1024),
}

data class ArchiveVolumeGroup(
    val primaryFile: File,
    val displayName: String,
    val format: ArchiveFormat,
    val parts: List<File>,
    val totalSizeBytes: Long,
    val isMissingParts: Boolean,
) {
    val isMultiVolume: Boolean get() = parts.size > 1
}

data class ArchiveEntryItem(
    val path: String,
    val name: String,
    val parentPath: String,
    val isDirectory: Boolean,
    val uncompressedSize: Long,
    val compressedSize: Long,
    val lastModifiedMs: Long,
    val isEncrypted: Boolean,
    val crc32: Long? = null,
)

data class ArchiveSummary(
    val group: ArchiveVolumeGroup,
    val entries: List<ArchiveEntryItem>,
    val isEncrypted: Boolean,
    val requiresPasswordToList: Boolean,
    val totalUncompressedBytes: Long,
    val totalCompressedBytes: Long,
    val fileCount: Int,
    val folderCount: Int,
)

data class PackArchiveRequest(
    val sourcePaths: List<String>,
    val outputDirectory: String,
    val archiveBaseName: String,
    val format: ArchiveFormat = ArchiveFormat.ZIP,
    val compressionLevel: ArchiveCompressionLevel = ArchiveCompressionLevel.NORMAL,
    val encryptionMethod: ArchiveEncryptionMethod = ArchiveEncryptionMethod.NONE,
    val password: String = "",
    val splitVolumeBytes: Long? = null,
    val solidArchive: Boolean = true,
)

enum class ExtractConflictPolicy(val label: String) {
    RENAME("Automatisch umbenennen"),
    OVERWRITE("Überschreiben"),
    SKIP("Überspringen"),
}

data class ArchiveOperationProgress(
    val title: String,
    val currentItem: String,
    val processedBytes: Long,
    val totalBytes: Long,
    val isFinished: Boolean = false,
    val errorMessage: String? = null,
)

class ArchivePasswordException(
    message: String,
    val isWrongPassword: Boolean = false,
    cause: Throwable? = null,
) : IOException(message, cause)

internal fun formatArchiveBytes(bytes: Long): String {
    val safe = bytes.coerceAtLeast(0L)
    if (safe < 1024L) return "$safe B"
    val kb = safe / 1024.0
    if (kb < 1024.0) return String.format(Locale.GERMANY, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return String.format(Locale.GERMANY, "%.1f MB", mb)
    return String.format(Locale.GERMANY, "%.2f GB", mb / 1024.0)
}
