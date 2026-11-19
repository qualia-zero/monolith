package com.monolith.feature.explorer.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

class AppManagerRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `sanitizeFileName replaces invalid chars trims and falls back to app`() {
        assertEquals("My App_ Pro (v2)", AppManagerRepository.sanitizeFileName("My App: Pro (v2)"))
        assertEquals("a_b_c_d_e_f_g_h_i", AppManagerRepository.sanitizeFileName("a\\b/c:d*e?f\"g<h>i"))
        assertEquals("trimmed", AppManagerRepository.sanitizeFileName("  ..trimmed..  "))
        assertEquals("app", AppManagerRepository.sanitizeFileName(""))
        assertEquals("app", AppManagerRepository.sanitizeFileName("   "))
        assertEquals("app", AppManagerRepository.sanitizeFileName("..."))
        assertEquals("app", AppManagerRepository.sanitizeFileName("///"))
    }

    @Test
    fun `filterAndSortApps handles all filter tabs search queries and sort orders`() {
        val alphaUser = makeApp(
            packageName = "com.example.alpha",
            appName = "Alpha Notes",
            isSystemApp = false,
            lastUpdateTime = 1_000L,
            apkSizeBytes = 100L,
            appBytes = 200L,
            dataBytes = 50L,
            cacheBytes = 10L,
            hasUsageStats = true,
        )
        val betaUser = makeApp(
            packageName = "org.beta.player",
            appName = "Beta Player",
            isSystemApp = false,
            lastUpdateTime = 3_000L,
            apkSizeBytes = 500L,
            cacheBytes = 0L,
            hasUsageStats = false,
        )
        val systemSettings = makeApp(
            packageName = "com.android.settings",
            appName = "System Settings",
            isSystemApp = true,
            isUpdatedSystemApp = true,
            lastUpdateTime = 2_000L,
            apkSizeBytes = 300L,
            appBytes = 300L,
            dataBytes = 100L,
            cacheBytes = 80L,
            hasUsageStats = true,
        )
        val allApps = listOf(betaUser, systemSettings, alphaUser)

        // Filter tabs
        assertEquals(
            listOf("Alpha Notes", "Beta Player"),
            AppManagerRepository.filterAndSortApps(allApps, AppFilterTab.USER_INSTALLED, AppSortOrder.NAME, "")
                .map { it.appName },
        )
        assertEquals(
            listOf("Alpha Notes", "Beta Player", "System Settings"),
            AppManagerRepository.filterAndSortApps(allApps, AppFilterTab.ALL, AppSortOrder.NAME, "")
                .map { it.appName },
        )
        assertEquals(
            listOf("System Settings"),
            AppManagerRepository.filterAndSortApps(allApps, AppFilterTab.SYSTEM, AppSortOrder.NAME, "")
                .map { it.appName },
        )

        // Search by appName and packageName
        assertEquals(
            listOf("Beta Player"),
            AppManagerRepository.filterAndSortApps(allApps, AppFilterTab.ALL, AppSortOrder.NAME, "  beta  ")
                .map { it.appName },
        )
        assertEquals(
            listOf("System Settings"),
            AppManagerRepository.filterAndSortApps(allApps, AppFilterTab.ALL, AppSortOrder.NAME, "com.android.")
                .map { it.appName },
        )

        // Sort orders:
        // SIZE_DESC: Beta (500), System Settings (300+100+80=480), Alpha (200+50+10=260)
        assertEquals(
            listOf("Beta Player", "System Settings", "Alpha Notes"),
            AppManagerRepository.filterAndSortApps(allApps, AppFilterTab.ALL, AppSortOrder.SIZE_DESC, "")
                .map { it.appName },
        )
        // CACHE_DESC: System Settings (80), Alpha (10), Beta (0)
        assertEquals(
            listOf("System Settings", "Alpha Notes", "Beta Player"),
            AppManagerRepository.filterAndSortApps(allApps, AppFilterTab.ALL, AppSortOrder.CACHE_DESC, "")
                .map { it.appName },
        )
        // UPDATED_DESC: Beta (3000), System Settings (2000), Alpha (1000)
        assertEquals(
            listOf("Beta Player", "System Settings", "Alpha Notes"),
            AppManagerRepository.filterAndSortApps(allApps, AppFilterTab.ALL, AppSortOrder.UPDATED_DESC, "")
                .map { it.appName },
        )
    }

    @Test
    fun `exportApp copies single apk and avoids overwriting existing files`() {
        val sourceDir = tempFolder.newFolder("source_single")
        val targetDir = tempFolder.newFolder("export_single")
        val baseApk = File(sourceDir, "base.apk").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        }
        val app = makeApp(
            packageName = "com.example.single",
            appName = "Single: App",
            versionName = "1.2.3",
            sourceDir = baseApk.absolutePath,
            splitSourceDirs = emptyList(),
        )

        val firstResult = AppManagerRepository.exportApp(app, targetDir)
        assertTrue(firstResult.isSuccess)
        val firstFile = firstResult.getOrThrow()
        assertEquals("Single_ App_1.2.3.apk", firstFile.name)
        assertEquals(listOf<Byte>(1, 2, 3, 4, 5), firstFile.readBytes().toList())

        val secondResult = AppManagerRepository.exportApp(app, targetDir)
        assertTrue(secondResult.isSuccess)
        val secondFile = secondResult.getOrThrow()
        assertEquals("Single_ App_1.2.3_1.apk", secondFile.name)
    }

    @Test
    fun `exportApp packages split apks into apks zip archive`() {
        val sourceDir = tempFolder.newFolder("source_split")
        val targetDir = tempFolder.newFolder("export_split")
        val baseApk = File(sourceDir, "base.apk").apply {
            writeText("base-apk-content")
        }
        val armSplit = File(sourceDir, "split_config.arm64_v8a.apk").apply {
            writeText("arm64-split-content")
        }
        val langSplit = File(sourceDir, "split_config.de.apk").apply {
            writeText("de-split-content")
        }

        val app = makeApp(
            packageName = "com.example.split",
            appName = "Split App",
            versionName = "2.0",
            sourceDir = baseApk.absolutePath,
            splitSourceDirs = listOf(armSplit.absolutePath, langSplit.absolutePath),
        )

        val result = AppManagerRepository.exportApp(app, targetDir)
        assertTrue(result.isSuccess)
        val archiveFile = result.getOrThrow()
        assertEquals("Split App_2.0.apks", archiveFile.name)
        assertTrue(archiveFile.exists())

        ZipFile(archiveFile).use { zip ->
            val entryNames = zip.entries().asSequence().map { it.name }.toSet()
            assertEquals(
                setOf("base.apk", "split_config.arm64_v8a.apk", "split_config.de.apk"),
                entryNames,
            )
            assertEquals(
                "base-apk-content",
                zip.getInputStream(zip.getEntry("base.apk")).bufferedReader().readText(),
            )
            assertEquals(
                "arm64-split-content",
                zip.getInputStream(zip.getEntry("split_config.arm64_v8a.apk")).bufferedReader().readText(),
            )
            assertEquals(
                "de-split-content",
                zip.getInputStream(zip.getEntry("split_config.de.apk")).bufferedReader().readText(),
            )
        }
    }

    @Test
    fun `exportApp returns failure when base apk is missing`() {
        val targetDir = tempFolder.newFolder("export_missing")
        val app = makeApp(
            packageName = "com.example.missing",
            appName = "Missing",
            sourceDir = File(tempFolder.root, "does_not_exist.apk").absolutePath,
        )

        val result = AppManagerRepository.exportApp(app, targetDir)
        assertTrue(result.isFailure)
    }

    @Test
    fun `formatAuditReportText formats summary and runtime permissions`() {
        val grantedCam = AppPermissionEntry(
            name = "android.permission.CAMERA",
            shortName = "CAMERA",
            label = "Kamera",
            category = PermissionCategory.RUNTIME,
            isGranted = true,
        )
        val deniedLoc = AppPermissionEntry(
            name = "android.permission.ACCESS_FINE_LOCATION",
            shortName = "ACCESS_FINE_LOCATION",
            label = "Genauer Standort",
            category = PermissionCategory.RUNTIME,
            isGranted = false,
        )
        val report = BatchAuditReport(
            generatedAtMs = 1_700_000_000_000L,
            entries = listOf(
                BatchAppAuditEntry(
                    packageName = "com.example.cam",
                    appName = "CamApp",
                    grantedRuntimePermissions = listOf(grantedCam),
                    deniedRuntimePermissions = listOf(deniedLoc),
                    totalPermissionCount = 5,
                    notificationStatus = NotificationPermissionStatus.GRANTED,
                ),
            ),
        )

        val formatted = AppManagerRepository.formatAuditReportText(report)
        assertTrue(formatted.contains("Monolith App Audit Report"))
        assertTrue(formatted.contains("Apps audited: 1"))
        assertTrue(formatted.contains("CamApp (com.example.cam)"))
        assertTrue(formatted.contains("Notifications: Erteilt"))
        assertTrue(formatted.contains("Total permissions: 5"))
        assertTrue(formatted.contains("Granted runtime (1): CAMERA"))
        assertTrue(formatted.contains("Denied runtime (1): ACCESS_FINE_LOCATION"))
    }

    private fun makeApp(
        packageName: String,
        appName: String,
        versionName: String = "1.0",
        versionCode: Long = 1L,
        isSystemApp: Boolean = false,
        isUpdatedSystemApp: Boolean = false,
        isEnabled: Boolean = true,
        firstInstallTime: Long = 100L,
        lastUpdateTime: Long = 200L,
        sourceDir: String = "/data/app/base.apk",
        splitSourceDirs: List<String> = emptyList(),
        apkSizeBytes: Long = 1024L,
        appBytes: Long = 0L,
        dataBytes: Long = 0L,
        cacheBytes: Long = 0L,
        hasUsageStats: Boolean = false,
    ): ManagedAppInfo = ManagedAppInfo(
        packageName = packageName,
        appName = appName,
        versionName = versionName,
        versionCode = versionCode,
        isSystemApp = isSystemApp,
        isUpdatedSystemApp = isUpdatedSystemApp,
        isEnabled = isEnabled,
        firstInstallTime = firstInstallTime,
        lastUpdateTime = lastUpdateTime,
        sourceDir = sourceDir,
        splitSourceDirs = splitSourceDirs,
        apkSizeBytes = apkSizeBytes,
        appBytes = appBytes,
        dataBytes = dataBytes,
        cacheBytes = cacheBytes,
        hasUsageStats = hasUsageStats,
    )
}
