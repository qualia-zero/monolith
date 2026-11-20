package com.monolith.feature.explorer.apps

import android.Manifest
import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Process
import android.os.storage.StorageManager
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object AppManagerRepository {

    private val invalidFileNameChars = Regex("[\\\\/:*?\"<>|\\x00-\\x1F]")

    fun hasUsageStatsPermission(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName,
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName,
                )
            }
        }.getOrDefault(AppOpsManager.MODE_ERRORED)

        return when (mode) {
            AppOpsManager.MODE_ALLOWED -> true
            AppOpsManager.MODE_DEFAULT ->
                context.checkCallingOrSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS) ==
                    PackageManager.PERMISSION_GRANTED
            else -> false
        }
    }

    suspend fun loadInstalledApps(context: Context): List<ManagedAppInfo> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val flags = PackageManager.MATCH_DISABLED_COMPONENTS
        val applications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(flags)
        }

        val usageStatsGranted = hasUsageStatsPermission(context)
        val storageStatsManager = if (usageStatsGranted) {
            context.getSystemService(Context.STORAGE_STATS_SERVICE) as? StorageStatsManager
        } else {
            null
        }
        val userHandle = Process.myUserHandle()

        applications.map { appInfo ->
            val pkgInfo = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getPackageInfo(
                        appInfo.packageName,
                        PackageManager.PackageInfoFlags.of(flags.toLong()),
                    )
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(appInfo.packageName, flags)
                }
            }.getOrNull()

            val appName = runCatching { pm.getApplicationLabel(appInfo).toString() }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: appInfo.packageName

            val versionName = pkgInfo?.versionName.orEmpty()
            val versionCode = pkgInfo?.let {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    it.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    it.versionCode.toLong()
                }
            } ?: 0L

            val isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val isUpdatedSystemApp = (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            val sourceDir = appInfo.sourceDir.orEmpty()
            val splitSourceDirs = appInfo.splitSourceDirs
                ?.filter { it.isNotBlank() }
                ?: emptyList()

            val baseApkSize = sourceDir.takeIf { it.isNotBlank() }
                ?.let { File(it) }
                ?.takeIf { it.isFile }
                ?.length() ?: 0L
            val splitApkSize = splitSourceDirs.sumOf { path ->
                File(path).takeIf { it.isFile }?.length() ?: 0L
            }
            val apkSizeBytes = baseApkSize + splitApkSize

            val stats = if (usageStatsGranted && storageStatsManager != null) {
                runCatching {
                    storageStatsManager.queryStatsForPackage(
                        StorageManager.UUID_DEFAULT,
                        appInfo.packageName,
                        userHandle,
                    )
                }.getOrNull()
            } else {
                null
            }

            ManagedAppInfo(
                packageName = appInfo.packageName,
                appName = appName,
                versionName = versionName,
                versionCode = versionCode,
                isSystemApp = isSystemApp,
                isUpdatedSystemApp = isUpdatedSystemApp,
                isEnabled = appInfo.enabled,
                firstInstallTime = pkgInfo?.firstInstallTime ?: 0L,
                lastUpdateTime = pkgInfo?.lastUpdateTime ?: 0L,
                sourceDir = sourceDir,
                splitSourceDirs = splitSourceDirs,
                apkSizeBytes = apkSizeBytes,
                appBytes = stats?.appBytes ?: 0L,
                dataBytes = stats?.dataBytes ?: 0L,
                cacheBytes = stats?.cacheBytes ?: 0L,
                hasUsageStats = stats != null,
            )
        }
    }

    suspend fun loadAppDetails(context: Context, base: ManagedAppInfo): ManagedAppInfo = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val flags = PackageManager.GET_PERMISSIONS or PackageManager.MATCH_DISABLED_COMPONENTS
        val pkgInfo = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(
                    base.packageName,
                    PackageManager.PackageInfoFlags.of(flags.toLong()),
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(base.packageName, flags)
            }
        }.getOrNull()

        val requestedPermissions = pkgInfo?.requestedPermissions ?: emptyArray()
        val requestedFlags = pkgInfo?.requestedPermissionsFlags ?: IntArray(0)

        val permissions = requestedPermissions.mapIndexed { index, permissionName ->
            val isGranted = index < requestedFlags.size &&
                (requestedFlags[index] and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
            val shortName = permissionName.substringAfterLast('.')
            val permInfo = runCatching { pm.getPermissionInfo(permissionName, 0) }.getOrNull()
            val category = resolvePermissionCategory(permInfo)
            val label = permInfo?.loadLabel(pm)?.toString()?.takeIf { it.isNotBlank() } ?: shortName

            AppPermissionEntry(
                name = permissionName,
                shortName = shortName,
                label = label,
                category = category,
                isGranted = isGranted,
            )
        }.sortedWith(
            compareBy<AppPermissionEntry> { it.category.ordinal }
                .thenByDescending { it.isGranted }
                .thenBy { it.label.lowercase(Locale.ROOT) },
        )

        val notificationStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val postNotifications = permissions.firstOrNull {
                it.name == Manifest.permission.POST_NOTIFICATIONS
            }
            if (postNotifications?.isGranted == true) {
                NotificationPermissionStatus.GRANTED
            } else {
                NotificationPermissionStatus.DENIED
            }
        } else {
            NotificationPermissionStatus.LEGACY_NOT_REQUIRED
        }

        base.copy(
            permissions = permissions,
            notificationSummary = AppNotificationSummary(permissionStatus = notificationStatus),
        )
    }

    private fun resolvePermissionCategory(permInfo: PermissionInfo?): PermissionCategory {
        if (permInfo == null) return PermissionCategory.SPECIAL
        val baseProtection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            permInfo.protection
        } else {
            @Suppress("DEPRECATION")
            permInfo.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE
        }
        return when (baseProtection) {
            PermissionInfo.PROTECTION_DANGEROUS -> PermissionCategory.RUNTIME
            PermissionInfo.PROTECTION_NORMAL -> PermissionCategory.NORMAL
            else -> PermissionCategory.SPECIAL
        }
    }

    fun defaultExportDirectory(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Monolith APKs")

    fun sanitizeFileName(raw: String): String {
        val cleaned = raw
            .replace(invalidFileNameChars, "_")
            .trim()
            .trim('.')
            .trim()
        if (cleaned.isEmpty() || cleaned.all { it == '_' }) {
            return "app"
        }
        return cleaned
    }

    fun exportApp(app: ManagedAppInfo, targetDir: File): Result<File> = runCatching {
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            throw IOException("Cannot create export directory: ${targetDir.absolutePath}")
        }

        val baseApk = File(app.sourceDir)
        if (!baseApk.exists() || !baseApk.isFile) {
            throw FileNotFoundException("Base APK not found: ${app.sourceDir}")
        }

        val safeName = sanitizeFileName(app.appName)
        val rawVersion = app.versionName.ifBlank { app.versionCode.toString() }
        val safeVersion = sanitizeFileName(rawVersion)
        val baseFileName = "${safeName}_${safeVersion}"

        if (app.splitSourceDirs.isEmpty()) {
            val dest = uniqueDestinationFile(targetDir, baseFileName, "apk")
            baseApk.copyTo(dest, overwrite = false)
            dest
        } else {
            val dest = uniqueDestinationFile(targetDir, baseFileName, "apks")
            ZipOutputStream(BufferedOutputStream(FileOutputStream(dest))).use { zos ->
                writeZipEntry(zos, "base.apk", baseApk)
                val usedNames = mutableSetOf("base.apk")
                app.splitSourceDirs.forEachIndexed { index, splitPath ->
                    val splitFile = File(splitPath)
                    if (!splitFile.exists() || !splitFile.isFile) {
                        throw FileNotFoundException("Split APK not found: $splitPath")
                    }
                    var entryName = splitFile.name.ifBlank { "split_$index.apk" }
                    if (!usedNames.add(entryName)) {
                        entryName = "split_${index}_$entryName"
                        usedNames.add(entryName)
                    }
                    writeZipEntry(zos, entryName, splitFile)
                }
            }
            dest
        }
    }

    private fun writeZipEntry(zos: ZipOutputStream, entryName: String, sourceFile: File) {
        zos.putNextEntry(ZipEntry(entryName))
        sourceFile.inputStream().use { input ->
            input.copyTo(zos)
        }
        zos.closeEntry()
    }

    private fun uniqueDestinationFile(dir: File, baseName: String, extension: String): File {
        val initial = File(dir, "$baseName.$extension")
        if (!initial.exists()) return initial
        var counter = 1
        while (true) {
            val candidate = File(dir, "${baseName}_$counter.$extension")
            if (!candidate.exists()) return candidate
            counter++
        }
    }

    suspend fun clearAccessibleTempFiles(
        context: Context,
        apps: List<ManagedAppInfo>,
    ): TempCleanupResult = withContext(Dispatchers.IO) {
        var freedBytes = 0L
        val cleanedPackages = mutableListOf<String>()
        val fallbackPackages = mutableListOf<String>()
        val ownPackage = context.packageName

        for (app in apps) {
            if (app.packageName == ownPackage) {
                var ownFreed = 0L
                context.cacheDir?.let { ownFreed += deleteDirectoryContents(it) }
                context.externalCacheDir?.let { ownFreed += deleteDirectoryContents(it) }
                freedBytes += ownFreed
                cleanedPackages += app.packageName
            } else {
                val externalCache = File("/storage/emulated/0/Android/data/${app.packageName}/cache")
                if (externalCache.exists() && externalCache.isDirectory && externalCache.canWrite()) {
                    freedBytes += deleteDirectoryContents(externalCache)
                    cleanedPackages += app.packageName
                } else {
                    fallbackPackages += app.packageName
                }
            }
        }

        TempCleanupResult(
            freedBytes = freedBytes,
            cleanedPackages = cleanedPackages,
            systemSettingsFallbackPackages = fallbackPackages,
        )
    }

    private fun deleteDirectoryContents(dir: File): Long {
        if (!dir.exists() || !dir.isDirectory) return 0L
        var freed = 0L
        val children = dir.listFiles() ?: return 0L
        for (child in children) {
            freed += deleteRecursivelyCountingBytes(child)
        }
        return freed
    }

    private fun deleteRecursivelyCountingBytes(file: File): Long {
        if (!file.exists()) return 0L
        var freed = 0L
        if (file.isDirectory) {
            file.listFiles()?.forEach { child ->
                freed += deleteRecursivelyCountingBytes(child)
            }
            file.delete()
        } else {
            val size = file.length()
            if (file.delete()) {
                freed += size
            }
        }
        return freed
    }

    suspend fun buildBatchAuditReport(
        context: Context,
        apps: List<ManagedAppInfo>,
    ): BatchAuditReport = withContext(Dispatchers.IO) {
        val entries = apps.map { app ->
            val detailed = if (app.permissions != null && app.notificationSummary != null) {
                app
            } else {
                loadAppDetails(context, app)
            }
            val perms = detailed.permissions.orEmpty()
            val runtimePerms = perms.filter { it.category == PermissionCategory.RUNTIME }
            BatchAppAuditEntry(
                packageName = detailed.packageName,
                appName = detailed.appName,
                grantedRuntimePermissions = runtimePerms.filter { it.isGranted },
                deniedRuntimePermissions = runtimePerms.filter { !it.isGranted },
                totalPermissionCount = perms.size,
                notificationStatus = detailed.notificationSummary?.permissionStatus
                    ?: NotificationPermissionStatus.LEGACY_NOT_REQUIRED,
            )
        }
        BatchAuditReport(
            generatedAtMs = System.currentTimeMillis(),
            entries = entries,
        )
    }

    fun formatAuditReportText(report: BatchAuditReport): String {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.GERMANY)
            .format(Date(report.generatedAtMs))
        return buildString {
            appendLine("Monolith App-Prüfbericht")
            appendLine("Erstellt: $timestamp")
            appendLine("Geprüfte Apps: ${report.entries.size}")
            appendLine("========================================")
            for (entry in report.entries) {
                appendLine()
                appendLine("${entry.appName} (${entry.packageName})")
                appendLine("  Benachrichtigungen: ${entry.notificationStatus.label}")
                appendLine("  Berechtigungen gesamt: ${entry.totalPermissionCount}")
                val grantedList = if (entry.grantedRuntimePermissions.isEmpty()) {
                    "Keine"
                } else {
                    entry.grantedRuntimePermissions.joinToString(", ") { it.shortName }
                }
                val deniedList = if (entry.deniedRuntimePermissions.isEmpty()) {
                    "Keine"
                } else {
                    entry.deniedRuntimePermissions.joinToString(", ") { it.shortName }
                }
                appendLine("  Erteilte Laufzeitberechtigungen (${entry.grantedRuntimePermissions.size}): $grantedList")
                appendLine("  Verweigerte Laufzeitberechtigungen (${entry.deniedRuntimePermissions.size}): $deniedList")
            }
        }.trimEnd()
    }

    fun exportAuditReport(report: BatchAuditReport, targetDir: File): Result<File> = runCatching {
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            throw IOException("Exportordner konnte nicht erstellt werden: ${targetDir.absolutePath}")
        }
        val fileStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            .format(Date(report.generatedAtMs))
        val dest = uniqueDestinationFile(targetDir, "monolith_app_audit_$fileStamp", "txt")
        dest.writeText(formatAuditReportText(report), Charsets.UTF_8)
        dest
    }

    fun buildUninstallIntent(packageName: String): Intent =
        Intent(Intent.ACTION_DELETE, Uri.fromParts("package", packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun buildAppDetailsIntent(packageName: String): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun buildAppStorageSettingsIntent(packageName: String): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)).apply {
            putExtra(":settings:fragment_args_key", "storage_settings")
        }

    fun buildClearAppCacheIntent(): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(StorageManager.ACTION_CLEAR_APP_CACHE)
        } else {
            null
        }

    fun buildNotificationSettingsIntent(packageName: String): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun buildUsageAccessIntent(context: Context): Intent =
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    fun filterAndSortApps(
        apps: List<ManagedAppInfo>,
        filter: AppFilterTab,
        sort: AppSortOrder,
        query: String,
    ): List<ManagedAppInfo> {
        val filteredByTab = when (filter) {
            AppFilterTab.USER_INSTALLED -> apps.filter { !it.isSystemApp }
            AppFilterTab.ALL -> apps
            AppFilterTab.SYSTEM -> apps.filter { it.isSystemApp }
        }

        val trimmedQuery = query.trim()
        val filteredByQuery = if (trimmedQuery.isEmpty()) {
            filteredByTab
        } else {
            filteredByTab.filter {
                it.appName.contains(trimmedQuery, ignoreCase = true) ||
                    it.packageName.contains(trimmedQuery, ignoreCase = true)
            }
        }

        return when (sort) {
            AppSortOrder.NAME -> filteredByQuery.sortedWith(
                compareBy<ManagedAppInfo> { it.appName.lowercase(Locale.ROOT) }
                    .thenBy { it.packageName },
            )
            AppSortOrder.SIZE_DESC -> filteredByQuery.sortedWith(
                compareByDescending<ManagedAppInfo> { it.totalDisplayBytes }
                    .thenBy { it.appName.lowercase(Locale.ROOT) },
            )
            AppSortOrder.CACHE_DESC -> filteredByQuery.sortedWith(
                compareByDescending<ManagedAppInfo> { it.cacheBytes }
                    .thenBy { it.appName.lowercase(Locale.ROOT) },
            )
            AppSortOrder.UPDATED_DESC -> filteredByQuery.sortedWith(
                compareByDescending<ManagedAppInfo> { it.lastUpdateTime }
                    .thenBy { it.appName.lowercase(Locale.ROOT) },
            )
        }
    }
}
