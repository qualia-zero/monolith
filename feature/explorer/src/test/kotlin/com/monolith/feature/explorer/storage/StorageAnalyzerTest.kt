package com.monolith.feature.explorer.storage

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

class StorageAnalyzerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `disjointTopFolders forms strict antichain with no ancestor and descendant pair`() = runBlocking {
        val root = tempFolder.newFolder("storage_root")

        // Deep wrapper chain: Android -> media -> com.whatsapp -> WhatsApp Video
        val whatsappVideoDir = File(root, "Android/media/com.whatsapp/WhatsApp Video").apply { mkdirs() }
        createSparseSizedFile(File(whatsappVideoDir, "clip1.mp4"), 35L * 1024 * 1024)
        createSparseSizedFile(File(whatsappVideoDir, "clip2.mp4"), 25L * 1024 * 1024)
        createSparseSizedFile(File(root, "Android/readme.txt"), 512L)

        // Multi-branch container: DCIM -> Camera & Screenshots
        val cameraDir = File(root, "DCIM/Camera").apply { mkdirs() }
        val screenshotsDir = File(root, "DCIM/Screenshots").apply { mkdirs() }
        createSparseSizedFile(File(cameraDir, "IMG_001.jpg"), 28L * 1024 * 1024)
        createSparseSizedFile(File(screenshotsDir, "SCR_001.png"), 18L * 1024 * 1024)

        // Consolidated folder dominated by own direct files: Downloads
        val downloadsDir = File(root, "Downloads").apply { mkdirs() }
        createSparseSizedFile(File(downloadsDir, "ubuntu.iso"), 50L * 1024 * 1024)
        val tinySub = File(downloadsDir, "docs").apply { mkdirs() }
        createSparseSizedFile(File(tinySub, "notes.txt"), 4096L)

        val result = StorageAnalyzer.scanStorage(
            rootPath = root.absolutePath,
            volumeLabel = "Test",
            maxResults = 50,
        )

        val folders = result.disjointTopFolders
        assertTrue("Expected non-empty folders", folders.isNotEmpty())

        // Strict antichain invariant: no folder may be an ancestor or descendant of any other folder
        for (i in folders.indices) {
            for (j in folders.indices) {
                if (i == j) continue
                val aPrefix = folders[i].path.trimEnd('/') + "/"
                val bPrefix = folders[j].path.trimEnd('/') + "/"
                assertFalse(
                    "Folder ${folders[j].relativePath} must not be a descendant of ${folders[i].relativePath}",
                    bPrefix.startsWith(aPrefix),
                )
            }
        }

        val relPaths = folders.map { it.relativePath }.toSet()
        assertTrue("Expected WhatsApp Video unwrapped", "Android/media/com.whatsapp/WhatsApp Video" in relPaths)
        assertFalse("Wrapper Android must not be listed", "Android" in relPaths)
        assertFalse("Wrapper Android/media must not be listed", "Android/media" in relPaths)
        assertFalse("Wrapper com.whatsapp must not be listed", "Android/media/com.whatsapp" in relPaths)
        assertTrue("Expected DCIM/Camera unwrapped", "DCIM/Camera" in relPaths)
        assertTrue("Expected DCIM/Screenshots unwrapped", "DCIM/Screenshots" in relPaths)
        assertFalse("Container DCIM must not be listed alongside children", "DCIM" in relPaths)
        assertTrue("Expected consolidated Downloads kept", "Downloads" in relPaths)
        assertFalse("Tiny child Downloads/docs must not duplicate Downloads", "Downloads/docs" in relPaths)
    }

    @Test
    fun `computeDisjointTopFolders handles direct tree nodes and preserves antichain`() {
        val root = DirNode(path = "/root", relativePath = "", name = "root")
        val movies = DirNode(path = "/root/Movies", relativePath = "Movies", name = "Movies")
        val action = DirNode(path = "/root/Movies/Action", relativePath = "Movies/Action", name = "Action")
        val sub = DirNode(path = "/root/Movies/Action/2026", relativePath = "Movies/Action/2026", name = "2026", ownFilesBytes = 200L * 1024 * 1024, totalBytes = 200L * 1024 * 1024, ownFilesCount = 4, totalFileCount = 4)
        action.children.add(sub)
        action.totalBytes = sub.totalBytes
        action.totalFileCount = sub.totalFileCount
        movies.children.add(action)
        movies.totalBytes = action.totalBytes
        movies.totalFileCount = action.totalFileCount
        root.children.add(movies)
        root.totalBytes = movies.totalBytes
        root.totalFileCount = movies.totalFileCount

        val top = StorageAnalyzer.computeDisjointTopFolders(root, maxCount = 10)
        assertEquals(listOf("Movies/Action/2026"), top.map { it.relativePath })
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

        val (digest, estimatedTokens) = StorageAiAdvisor.buildCompactDigest(result)
        assertTrue(digest.length <= 1780)
        assertTrue(estimatedTokens in 1..550)
        assertTrue(digest.contains("Duplikate(1):"))
    }

    private fun createSparseSizedFile(file: File, sizeBytes: Long) {
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(sizeBytes)
        }
    }
}
