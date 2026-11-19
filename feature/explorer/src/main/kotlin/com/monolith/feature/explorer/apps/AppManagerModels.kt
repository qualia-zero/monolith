package com.monolith.feature.explorer.apps

enum class AppFilterTab(val label: String) {
    USER_INSTALLED("Installiert"),
    ALL("Alle Apps"),
    SYSTEM("System"),
}

enum class AppSortOrder(val label: String) {
    NAME("Name"),
    SIZE_DESC("Größe"),
    CACHE_DESC("Cache"),
    UPDATED_DESC("Aktualisiert"),
}

enum class PermissionCategory {
    RUNTIME,
    NORMAL,
    SPECIAL,
}

data class AppPermissionEntry(
    val name: String,
    val shortName: String,
    val label: String,
    val category: PermissionCategory,
    val isGranted: Boolean,
)

enum class NotificationPermissionStatus(val label: String) {
    GRANTED("Erteilt"),
    DENIED("Verweigert"),
    LEGACY_NOT_REQUIRED("Standard (Android < 13)"),
}

data class AppNotificationSummary(
    val permissionStatus: NotificationPermissionStatus,
)

data class ManagedAppInfo(
    val packageName: String,
    val appName: String,
    val versionName: String,
    val versionCode: Long,
    val isSystemApp: Boolean,
    val isUpdatedSystemApp: Boolean,
    val isEnabled: Boolean,
    val firstInstallTime: Long,
    val lastUpdateTime: Long,
    val sourceDir: String,
    val splitSourceDirs: List<String>,
    val apkSizeBytes: Long,
    val appBytes: Long,
    val dataBytes: Long,
    val cacheBytes: Long,
    val hasUsageStats: Boolean,
    val permissions: List<AppPermissionEntry>? = null,
    val notificationSummary: AppNotificationSummary? = null,
) {
    val totalDisplayBytes: Long
        get() = if (hasUsageStats) {
            (appBytes + dataBytes + cacheBytes).coerceAtLeast(apkSizeBytes)
        } else {
            apkSizeBytes
        }

    val canUninstall: Boolean
        get() = !isSystemApp || isUpdatedSystemApp
}

data class TempCleanupResult(
    val freedBytes: Long,
    val cleanedPackages: List<String>,
    val systemSettingsFallbackPackages: List<String>,
)

data class BatchAppAuditEntry(
    val packageName: String,
    val appName: String,
    val grantedRuntimePermissions: List<AppPermissionEntry>,
    val deniedRuntimePermissions: List<AppPermissionEntry>,
    val totalPermissionCount: Int,
    val notificationStatus: NotificationPermissionStatus,
)

data class BatchAuditReport(
    val generatedAtMs: Long,
    val entries: List<BatchAppAuditEntry>,
)
