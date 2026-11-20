package com.monolith.feature.explorer.archive

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ArchiveEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `zip create inspect add rename delete extract roundtrip`() = runBlocking {
        val srcDir = tempFolder.newFolder("src_zip")
        val nestedDir = File(srcDir, "docs").apply { mkdirs() }
        val fileA = File(srcDir, "hello.txt").apply { writeText("Monolith Archive Test") }
        val fileB = File(nestedDir, "notes.md").apply { writeText("# Notes\nLine 2") }
        val extraFile = File(tempFolder.newFolder("extra"), "extra.txt").apply { writeText("Added later") }

        val outDir = tempFolder.newFolder("out_zip")
        val createdGroup = ArchiveEngine.createArchive(
            PackArchiveRequest(
                sourcePaths = listOf(fileA.absolutePath, nestedDir.absolutePath),
                outputDirectory = outDir.absolutePath,
                archiveBaseName = "test_archive",
                format = ArchiveFormat.ZIP,
                compressionLevel = ArchiveCompressionLevel.NORMAL,
            ),
        ).getOrThrow()

        assertTrue(createdGroup.primaryFile.exists())
        assertEquals(ArchiveFormat.ZIP, createdGroup.format)
        assertFalse(createdGroup.isMultiVolume)

        // Add entry
        ArchiveEngine.addEntriesToArchive(
            archiveFile = createdGroup.primaryFile,
            pathsToAdd = listOf(extraFile.absolutePath),
            targetInternalDir = "docs",
        ).getOrThrow()

        // Inspect entries
        val summaryAfterAdd = ArchiveEngine.inspectArchive(createdGroup.primaryFile).getOrThrow()
        val paths = summaryAfterAdd.entries.map { it.path }.toSet()
        assertTrue("hello.txt" in paths)
        assertTrue("docs/notes.md" in paths)
        assertTrue("docs/extra.txt" in paths)

        // Rename entry
        ArchiveEngine.renameEntryInArchive(
            archiveFile = createdGroup.primaryFile,
            oldEntryPath = "docs/extra.txt",
            newName = "renamed.txt",
        ).getOrThrow()

        // Delete entry
        ArchiveEngine.deleteEntriesFromArchive(
            archiveFile = createdGroup.primaryFile,
            entryPaths = setOf("hello.txt"),
        ).getOrThrow()

        val verifyMsg = ArchiveEngine.verifyArchive(createdGroup.primaryFile).getOrThrow()
        assertTrue(verifyMsg.isNotBlank())

        // Extract
        val extractDir = tempFolder.newFolder("extract_zip")
        ArchiveEngine.extractArchive(
            file = createdGroup.primaryFile,
            targetDir = extractDir,
            createSubfolder = false,
            conflictPolicy = ExtractConflictPolicy.OVERWRITE,
        ).getOrThrow()

        assertFalse(File(extractDir, "hello.txt").exists())
        assertEquals("# Notes\nLine 2", File(extractDir, "docs/notes.md").readText())
        assertEquals("Added later", File(extractDir, "docs/renamed.txt").readText())
    }

    @Test
    fun `encrypted aes256 zip requires password and extracts with password`() = runBlocking {
        val srcFile = tempFolder.newFile("secret.txt").apply { writeText("Top Secret Monolith Payload") }
        val outDir = tempFolder.newFolder("out_enc_zip")

        val group = ArchiveEngine.createArchive(
            PackArchiveRequest(
                sourcePaths = listOf(srcFile.absolutePath),
                outputDirectory = outDir.absolutePath,
                archiveBaseName = "locked",
                format = ArchiveFormat.ZIP,
                encryptionMethod = ArchiveEncryptionMethod.AES_256,
                password = "monolith-pass-123",
            ),
        ).getOrThrow()

        val summary = ArchiveEngine.inspectArchive(group.primaryFile).getOrThrow()
        assertTrue(summary.isEncrypted)

        val failExtractDir = tempFolder.newFolder("fail_enc_zip")
        val failResult = ArchiveEngine.extractArchive(group.primaryFile, failExtractDir, password = "wrong")
        assertTrue(failResult.isFailure)

        val okExtractDir = tempFolder.newFolder("ok_enc_zip")
        ArchiveEngine.extractArchive(
            file = group.primaryFile,
            targetDir = okExtractDir,
            password = "monolith-pass-123",
        ).getOrThrow()

        assertEquals("Top Secret Monolith Payload", File(okExtractDir, "secret.txt").readText())
    }

    @Test
    fun `split zip creates z01 parts groups them and merges cleanly`() = runBlocking {
        val srcDir = tempFolder.newFolder("src_split_zip")
        val payload = ByteArray(180 * 1024) { (it * 31 % 251).toByte() }
        val bigFile = File(srcDir, "payload.bin").apply { writeBytes(payload) }
        val outDir = tempFolder.newFolder("out_split_zip")

        val group = ArchiveEngine.createArchive(
            PackArchiveRequest(
                sourcePaths = listOf(bigFile.absolutePath),
                outputDirectory = outDir.absolutePath,
                archiveBaseName = "split_bundle",
                format = ArchiveFormat.ZIP,
                compressionLevel = ArchiveCompressionLevel.STORE,
                splitVolumeBytes = 70L * 1024L,
            ),
        ).getOrThrow()

        assertTrue("Expected multi-volume split zip", group.isMultiVolume)
        assertFalse(group.isMissingParts)
        assertTrue(group.parts.any { it.name.endsWith(".z01") })

        // Resolving from .z01 resolves the whole group
        val z01 = group.parts.first { it.name.endsWith(".z01") }
        val resolvedFromPart = ArchiveEngine.resolveVolumeGroup(z01)
        assertEquals(group.parts.size, resolvedFromPart.parts.size)

        // Extract directly from split archive
        val extractDir = tempFolder.newFolder("extract_split_zip")
        ArchiveEngine.extractArchive(z01, extractDir).getOrThrow()
        assertArrayEquals(payload, File(extractDir, "payload.bin").readBytes())

        // Merge split archive into single standalone zip
        val mergedOut = File(tempFolder.newFolder("merged"), "merged.zip")
        ArchiveEngine.mergeSplitArchive(z01, mergedOut).getOrThrow()
        val mergedGroup = ArchiveEngine.resolveVolumeGroup(mergedOut)
        assertFalse(mergedGroup.isMultiVolume)
        val mergedSummary = ArchiveEngine.inspectArchive(mergedOut).getOrThrow()
        assertEquals(1, mergedSummary.fileCount)
    }

    @Test
    fun `sevenz encrypted and numeric split volume roundtrip`() = runBlocking {
        val srcDir = tempFolder.newFolder("src_7z")
        val data = ByteArray(160 * 1024) { (it % 199).toByte() }
        val file1 = File(srcDir, "data.bin").apply { writeBytes(data) }
        val file2 = File(srcDir, "info.txt").apply { writeText("7z info text") }
        val outDir = tempFolder.newFolder("out_7z")

        val group = ArchiveEngine.createArchive(
            PackArchiveRequest(
                sourcePaths = listOf(file1.absolutePath, file2.absolutePath),
                outputDirectory = outDir.absolutePath,
                archiveBaseName = "bundle7z",
                format = ArchiveFormat.SEVEN_Z,
                compressionLevel = ArchiveCompressionLevel.STORE,
                encryptionMethod = ArchiveEncryptionMethod.AES_256,
                password = "7z-password",
                splitVolumeBytes = 64L * 1024L,
                solidArchive = true,
            ),
        ).getOrThrow()

        assertTrue(group.isMultiVolume)
        assertFalse(group.isMissingParts)
        assertEquals(ArchiveFormat.SEVEN_Z, group.format)
        assertTrue(group.parts.first().name.endsWith(".7z.001"))

        val part2 = group.parts[1]
        val discovered = ArchiveEngine.discoverArchiveGroups(outDir, recursiveDepth = 1)
        assertEquals(1, discovered.size)

        // Inspect with password from second volume part
        val summary = ArchiveEngine.inspectArchive(part2, password = "7z-password").getOrThrow()
        assertTrue(summary.isEncrypted)
        assertEquals(2, summary.fileCount)

        // Extract with password
        val extractDir = tempFolder.newFolder("extract_7z")
        ArchiveEngine.extractArchive(part2, extractDir, password = "7z-password").getOrThrow()
        assertArrayEquals(data, File(extractDir, "data.bin").readBytes())
        assertEquals("7z info text", File(extractDir, "info.txt").readText())
    }

    @Test
    fun `tar gz create inspect and extract roundtrip`() = runBlocking {
        val srcDir = tempFolder.newFolder("src_targz")
        File(srcDir, "alpha.txt").writeText("Alpha")
        val sub = File(srcDir, "sub").apply { mkdirs() }
        File(sub, "beta.txt").writeText("Beta")
        val outDir = tempFolder.newFolder("out_targz")

        val group = ArchiveEngine.createArchive(
            PackArchiveRequest(
                sourcePaths = listOf(srcDir.absolutePath),
                outputDirectory = outDir.absolutePath,
                archiveBaseName = "my_backup",
                format = ArchiveFormat.TAR_GZ,
            ),
        ).getOrThrow()

        assertTrue(group.primaryFile.name.endsWith(".tar.gz"))
        val extractDir = tempFolder.newFolder("extract_targz")
        ArchiveEngine.extractArchive(group.primaryFile, extractDir).getOrThrow()

        assertEquals("Alpha", File(extractDir, "src_targz/alpha.txt").readText())
        assertEquals("Beta", File(extractDir, "src_targz/sub/beta.txt").readText())
    }

    @Test
    fun `discoverArchiveGroupsWithInfo finds nested archives and skips android folder`() = runBlocking {
        val root = tempFolder.newFolder("scan_root")
        val deepDir = File(root, "a/b/c/d").apply { mkdirs() }
        val androidDir = File(root, "Android/data/pkg").apply { mkdirs() }
        val sampleFile = tempFolder.newFile("sample.txt").apply { writeText("hello") }

        val deepGroup = ArchiveEngine.createArchive(
            PackArchiveRequest(
                sourcePaths = listOf(sampleFile.absolutePath),
                outputDirectory = deepDir.absolutePath,
                archiveBaseName = "deep_archive",
                format = ArchiveFormat.ZIP,
            ),
        ).getOrThrow()

        ArchiveEngine.createArchive(
            PackArchiveRequest(
                sourcePaths = listOf(sampleFile.absolutePath),
                outputDirectory = androidDir.absolutePath,
                archiveBaseName = "ignored_android",
                format = ArchiveFormat.ZIP,
            ),
        ).getOrThrow()

        val result = ArchiveEngine.discoverArchiveGroupsWithInfo(root)
        assertFalse(result.reachedScanLimit)
        val foundNames = result.groups.map { it.displayName }
        assertTrue(deepGroup.displayName in foundNames)
        assertFalse("ignored_android.zip" in foundNames)
    }

    @Test
    fun `extractSingleEntryToCache extracts single entry for preview`() = runBlocking {
        val srcDir = tempFolder.newFolder("src_preview")
        File(srcDir, "readme.txt").writeText("Preview content")
        File(srcDir, "other.txt").writeText("Other content")
        val outDir = tempFolder.newFolder("out_preview")
        val cacheDir = tempFolder.newFolder("cache_preview")

        val group = ArchiveEngine.createArchive(
            PackArchiveRequest(
                sourcePaths = listOf(srcDir.absolutePath),
                outputDirectory = outDir.absolutePath,
                archiveBaseName = "preview_test",
                format = ArchiveFormat.ZIP,
            ),
        ).getOrThrow()

        val previewFile = ArchiveEngine.extractSingleEntryToCache(
            archiveFile = group.primaryFile,
            entryPath = "src_preview/readme.txt",
            cacheRootDir = cacheDir,
        ).getOrThrow()

        assertTrue(previewFile.exists())
        assertEquals("Preview content", previewFile.readText())
    }
}
