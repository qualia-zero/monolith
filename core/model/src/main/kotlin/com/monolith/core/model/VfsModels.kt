package com.monolith.core.model

enum class LocationType {
    LOCAL,
    SDCARD,
    USB,
    ARCHIVE,
    SMB,
    FTP,
    SFTP,
    WEBDAV,
    GOOGLE_DRIVE,
    APPS,
    STORAGE_ANALYSIS,
    RECYCLE_BIN,
}

enum class FileCategory {
    DIRECTORY,
    VIDEO,
    AUDIO,
    IMAGE,
    ARCHIVE,
    DOCUMENT,
    APK,
    TEXT,
    OTHER,
}

data class VfsLocation(
    val type: LocationType,
    val path: String,
    val displayName: String,
    val rootId: String = type.name,
)

data class VfsNode(
    val id: String,
    val name: String,
    val path: String,
    val parentPath: String?,
    val locationType: LocationType,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModifiedMs: Long,
    val mimeType: String,
    val category: FileCategory,
    val isHidden: Boolean = name.startsWith("."),
    val childCount: Int? = null,
    val thumbnailUrl: String? = null,
)

enum class SortBy {
    NAME,
    SIZE,
    DATE_MODIFIED,
    TYPE,
}

enum class SortOrder {
    ASCENDING,
    DESCENDING,
}

enum class ViewMode {
    LIST,
    GRID,
}

enum class TransferOperation {
    COPY,
    MOVE,
    DELETE,
    RECYCLE,
    EXTRACT,
    COMPRESS,
}

data class TransferProgress(
    val taskId: Long,
    val operation: TransferOperation,
    val currentFileName: String,
    val processedItems: Int,
    val totalItems: Int,
    val bytesProcessed: Long,
    val totalBytes: Long,
    val bytesPerSecond: Long,
    val isFinished: Boolean = false,
    val isCancelled: Boolean = false,
    val errorMessage: String? = null,
)
