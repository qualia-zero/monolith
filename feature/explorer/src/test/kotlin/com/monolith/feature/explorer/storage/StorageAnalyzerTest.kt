package com.monolith.feature.explorer.storage

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale

class StorageAnalyzerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `rootFolder and directFolders aggregate sizes counts and sort descending by size`() = runBlocking {
        val root = tempFolder.newFolder("storage_root")

        val dcimDir = File(root, "DCIM").apply { mkdirs() }
        val cameraDir = File(dcimDir, "Camera").apply { mkdirs() }
        val screenshotsDir = File(dcimDir, "Screenshots").apply { mkdirs() }
        createSparseSizedFile(File(cameraDir, "IMG_001.jpg"), 30L * 1024 * 1024)
        createSparseSizedFile(File(screenshotsDir, "SCR_001.png"), 10L * 1024 * 1024)

        val downloadsDir = File(root, "Downloads").apply { mkdirs() }
        val docsDir = File(downloadsDir, "docs").apply { mkdirs() }
        createSparseSizedFile(File(downloadsDir, "ubuntu.iso"), 50L * 1024 * 1024)
        createSparseSizedFile(File(downloadsDir, "notes.txt"), 4096L)
        createSparseSizedFile(File(docsDir, "manual.pdf"), 8192L)

        createSparseSizedFile(File(root, "root_large.bin"), 2L * 1024 * 1024)
        createSparseSizedFile(File(root, "root_small.txt"), 1024L)

        val result = StorageAnalyzer.scanStorage(
            rootPath = root.absolutePath,
            volumeLabel = "Test",
            maxResults = 50,
        )

        val expectedRootOwnBytes = 2L * 1024 * 1024 + 1024L
        val expectedDcimBytes = 40L * 1024 * 1024
        val expectedDownloadsBytes = 50L * 1024 * 1024 + 4096L + 8192L
        val expectedTotalBytes = expectedRootOwnBytes + expectedDcimBytes + expectedDownloadsBytes

        val rootFolder = result.rootFolder
        assertEquals(root.absolutePath, rootFolder.path)
        assertEquals("", rootFolder.relativePath)
        assertEquals(expectedTotalBytes, rootFolder.totalBytes)
        assertEquals(expectedRootOwnBytes, rootFolder.ownFilesBytes)
        assertEquals(7, rootFolder.fileCount)
        assertEquals(5, rootFolder.subfolderCount)

        // Direct folders sorted descending by totalBytes
        assertEquals(listOf("Downloads", "DCIM"), rootFolder.directFolders.map { it.name })
        // Direct files sorted descending by sizeBytes
        assertEquals(listOf("root_large.bin", "root_small.txt"), rootFolder.directFiles.map { it.name })

        val downloadsNode = rootFolder.directFolders[0]
        assertEquals(expectedDownloadsBytes, downloadsNode.totalBytes)
        assertEquals(50L * 1024 * 1024 + 4096L, downloadsNode.ownFilesBytes)
        assertEquals(3, downloadsNode.fileCount)
        assertEquals(1, downloadsNode.subfolderCount)
        assertEquals(listOf("docs"), downloadsNode.directFolders.map { it.name })
        assertEquals(listOf("ubuntu.iso", "notes.txt"), downloadsNode.directFiles.map { it.name })

        val dcimNode = rootFolder.directFolders[1]
        assertEquals(expectedDcimBytes, dcimNode.totalBytes)
        assertEquals(0L, dcimNode.ownFilesBytes)
        assertEquals(2, dcimNode.fileCount)
        assertEquals(2, dcimNode.subfolderCount)
        assertEquals(listOf("Camera", "Screenshots"), dcimNode.directFolders.map { it.name })
    }

    @Test
    fun `folderByPath and findFolder support lookup for root and nested subfolders`() = runBlocking {
        val root = tempFolder.newFolder("lookup_root")
        val mediaDir = File(root, "Android/media/com.example").apply { mkdirs() }
        val emptyDir = File(root, "EmptyDir").apply { mkdirs() }
        createSparseSizedFile(File(mediaDir, "voice.ogg"), 256L * 1024)

        val result = StorageAnalyzer.scanStorage(
            rootPath = root.absolutePath,
            volumeLabel = "Test",
        )

        assertEquals(result.rootFolder, result.findFolder(root.absolutePath))

        val androidNode = result.findFolder(File(root, "Android").absolutePath)
        assertNotNull(androidNode)
        assertEquals("Android", androidNode!!.relativePath)
        assertEquals(256L * 1024, androidNode.totalBytes)
        assertEquals(2, androidNode.subfolderCount)

        val appNode = result.findFolder(mediaDir.absolutePath)
        assertNotNull(appNode)
        assertEquals("Android/media/com.example", appNode!!.relativePath)
        assertEquals(1, appNode.directFiles.size)
        assertEquals("voice.ogg", appNode.directFiles.first().name)

        val emptyNode = result.findFolder(emptyDir.absolutePath)
        assertNotNull(emptyNode)
        assertEquals(0L, emptyNode!!.totalBytes)
        assertEquals(0, emptyNode.fileCount)

        assertNull(result.findFolder(File(root, "DoesNotExist").absolutePath))
    }

    @Test
    fun `computeShareFraction and formatSharePercent calculate subfolder shares accurately`() = runBlocking {
        assertEquals(0f, computeShareFraction(0L, 100L), 0.0001f)
        assertEquals(0f, computeShareFraction(50L, 0L), 0.0001f)
        assertEquals(1f, computeShareFraction(200L, 100L), 0.0001f)

        assertEquals("0 %", formatSharePercent(0L, 100L, Locale.GERMAN))
        assertEquals("0 %", formatSharePercent(50L, 0L, Locale.GERMAN))
        assertEquals("< 0,1 %", formatSharePercent(500L, 1_000_000L, Locale.GERMAN))
        assertEquals("100,0 %", formatSharePercent(200L, 100L, Locale.GERMAN))

        val root = tempFolder.newFolder("share_root")
        val dcimDir = File(root, "DCIM").apply { mkdirs() }
        val cameraDir = File(dcimDir, "Camera").apply { mkdirs() }
        val screenshotsDir = File(dcimDir, "Screenshots").apply { mkdirs() }
        val otherDir = File(root, "Movies").apply { mkdirs() }

        createSparseSizedFile(File(cameraDir, "IMG_001.jpg"), 30L * 1024 * 1024)
        createSparseSizedFile(File(screenshotsDir, "SCR_001.png"), 10L * 1024 * 1024)
        createSparseSizedFile(File(otherDir, "film.mkv"), 60L * 1024 * 1024)

        val result = StorageAnalyzer.scanStorage(
            rootPath = root.absolutePath,
            volumeLabel = "Test",
        )

        val dcimNode = result.findFolder(dcimDir.absolutePath)!!
        val cameraNode = result.findFolder(cameraDir.absolutePath)!!
        val screenshotsNode = result.findFolder(screenshotsDir.absolutePath)!!

        // Inside root (100 MB total), DCIM is 40%
        assertEquals(0.4f, computeShareFraction(dcimNode.totalBytes, result.rootFolder.totalBytes), 0.0001f)
        assertEquals("40,0 %", formatSharePercent(dcimNode.totalBytes, result.rootFolder.totalBytes, Locale.GERMAN))

        // Drilled down inside DCIM (40 MB total), Camera is 75% and Screenshots is 25%
        assertEquals(0.75f, computeShareFraction(cameraNode.totalBytes, dcimNode.totalBytes), 0.0001f)
        assertEquals("75,0 %", formatSharePercent(cameraNode.totalBytes, dcimNode.totalBytes, Locale.GERMAN))
        assertEquals(0.25f, computeShareFraction(screenshotsNode.totalBytes, dcimNode.totalBytes), 0.0001f)
        assertEquals("25,0 %", formatSharePercent(screenshotsNode.totalBytes, dcimNode.totalBytes, Locale.GERMAN))
    }

    @Test
    fun `duplicate detection verifies file content fingerprint and avoids false positives`() = runBlocking {
        val root = tempFolder.newFolder("dup_root")
        val dirA = File(root, "A").apply { mkdirs() }
        val dirB = File(root, "B").apply { mkdirs() }

        val size = 700 * 1024
        val sameBytes = ByteArray(size) { (it % 251).toByte() }
        val differentBytes = ByteArray(size) { ((it + 17) % 251).toByte() }

        File(dirA, "photo_copy.jpg").writeBytes(sameBytes)
        File(dirB, "photo_backup.jpg").writeBytes(sameBytes)
        File(dirB, "unrelated_same_size.bin").writeBytes(differentBytes)

        val result = StorageAnalyzer.scanStorage(root.absolutePath, "Test")
        assertEquals(1, result.duplicateGroups.size)
        val group = result.duplicateGroups.first()
        assertEquals(2, group.files.size)
        assertEquals(size.toLong(), group.reclaimableBytes)

        val dirANode = result.findFolder(dirA.absolutePath)!!
        assertTrue(dirANode.directFiles.single().isDuplicateCandidate)

        val dirBNode = result.findFolder(dirB.absolutePath)!!
        val backupFile = dirBNode.directFiles.first { it.name == "photo_backup.jpg" }
        val unrelatedFile = dirBNode.directFiles.first { it.name == "unrelated_same_size.bin" }
        assertTrue(backupFile.isDuplicateCandidate)
        assertFalse(unrelatedFile.isDuplicateCandidate)
    }

    private fun createSparseSizedFile(file: File, sizeBytes: Long) {
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(sizeBytes)
        }
    }
}
