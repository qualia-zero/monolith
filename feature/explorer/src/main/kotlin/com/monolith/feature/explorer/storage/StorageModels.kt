package com.monolith.feature.explorer.storage

import com.monolith.core.model.FileCategory

data class StorageVolumeStats(
    val rootPath: String,
    val label: String,
    val totalCapacityBytes: Long,
    val usedCapacityBytes: Long,
    val freeCapacityBytes: Long,
)

data class CategoryBreakdown(
    val category: FileCategory,
    val label: String,
    val totalBytes: Long,
    val fileCount: Int,
    val fractionOfScanned: Float,
)

data class AnalyzedFolderItem(
    val path: String,
    val relativePath: String,
    val name: String,
    val totalBytes: Long,
    val ownFilesBytes: Long,
    val fileCount: Int,
    val subfolderCount: Int,
    val dominantExtensions: List<String>,
    val fractionOfScanned: Float,
)

data class AnalyzedFileItem(
    val path: String,
    val relativePath: String,
    val name: String,
    val sizeBytes: Long,
    val lastModifiedMs: Long,
    val category: FileCategory,
    val mimeType: String,
    val isDuplicateCandidate: Boolean = false,
)

data class DuplicateGroup(
    val key: String,
    val fileName: String,
    val eachSizeBytes: Long,
    val files: List<AnalyzedFileItem>,
) {
    val reclaimableBytes: Long
        get() = eachSizeBytes * (files.size - 1).coerceAtLeast(0)
}

data class StorageScanResult(
    val rootPath: String,
    val volumeStats: StorageVolumeStats,
    val scannedBytes: Long,
    val totalFilesCount: Int,
    val totalFoldersCount: Int,
    val categoryBreakdowns: List<CategoryBreakdown>,
    val disjointTopFolders: List<AnalyzedFolderItem>,
    val largestFiles: List<AnalyzedFileItem>,
    val duplicateGroups: List<DuplicateGroup>,
    val largeFilesOver100MbBytes: Long,
    val largeFilesOver100MbCount: Int,
    val archiveAndApkBytes: Long,
    val archiveAndApkCount: Int,
    val tempAndCacheBytes: Long,
    val tempAndCacheCount: Int,
    val reclaimableDuplicatesBytes: Long,
)
