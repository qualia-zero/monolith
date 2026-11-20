package com.monolith.feature.explorer.archive

import com.github.junrar.Archive
import com.github.junrar.exception.InitDeciphererFailedException
import com.github.junrar.exception.RarException
import com.github.junrar.exception.UnsupportedRarV5Exception
import com.github.junrar.rarfile.FileHeader as RarFileHeader
import com.github.junrar.volume.FileVolume
import com.github.junrar.volume.FileVolumeManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import net.lingala.zip4j.io.outputstream.SplitOutputStream
import net.lingala.zip4j.io.outputstream.ZipOutputStream
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionLevel
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.apache.commons.compress.PasswordRequiredException
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.SevenZMethod
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipParameters
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import org.apache.commons.compress.utils.MultiReadOnlySeekableByteChannel
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.UUID
import java.util.zip.Deflater

object ArchiveEngine {

    private const val BUFFER_SIZE = 64 * 1024
    private const val MIN_ZIP_SPLIT_BYTES = 65536L

    private val numericSplitRegex = Regex("^(.+)\\.(\\d{3,4})$")
    private val zipSplitPartRegex = Regex("^(.+)\\.z(\\d{2,})$", RegexOption.IGNORE_CASE)
    private val rarPartRegex = Regex("^(.+)\\.part(\\d+)\\.rar$", RegexOption.IGNORE_CASE)
    private val rarLegacyPartRegex = Regex("^(.+)\\.r(\\d{2,})$", RegexOption.IGNORE_CASE)

    private enum class TarCodec {
        PLAIN,
        GZIP,
        XZ,
        BZIP2,
    }

    private data class SourceTreeItem(
        val file: File,
        val archivePath: String,
    )

    fun isSupportedArchiveFile(file: File): Boolean {
        if (!file.isFile) return false
        val lower = file.name.lowercase(Locale.ROOT)
        if (hasDirectArchiveExtension(lower)) return true
        if (zipSplitPartRegex.matches(lower) || rarLegacyPartRegex.matches(lower) || rarPartRegex.matches(lower)) {
            return true
        }
        val numMatch = numericSplitRegex.matchEntire(lower)
        if (numMatch != null) {
            val base = numMatch.groupValues[1]
            if (hasDirectArchiveExtension(base)) return true
            if (numMatch.groupValues[2].toIntOrNull() == 1 && detectFormatFromMagic(file) != null) return true
        }
        return false
    }

    fun canBinaryMerge(group: ArchiveVolumeGroup): Boolean {
        if (!group.isMultiVolume || group.isMissingParts) return false
        val firstLower = group.parts.first().name.lowercase(Locale.ROOT)
        return zipSplitPartRegex.matches(firstLower) || numericSplitRegex.matches(firstLower)
    }

    fun resolveVolumeGroup(file: File): ArchiveVolumeGroup {
        val parent = file.parentFile
        val siblings = if (parent != null && parent.isDirectory) {
            parent.listFiles()?.filter { it.isFile }.orEmpty()
        } else {
            emptyList()
        }
        val allCandidates = if (siblings.any { it.absolutePath == file.absolutePath }) {
            siblings
        } else {
            siblings + file
        }
        val name = file.name
        val lower = name.lowercase(Locale.ROOT)

        // 1. Numeric binary splits: <base>.<ext>.001, .002, ...
        numericSplitRegex.matchEntire(name)?.let { match ->
            val baseWithExt = match.groupValues[1]
            val partRegex = Regex("^${Regex.escape(baseWithExt)}\\.(\\d{3,4})$", RegexOption.IGNORE_CASE)
            val matchedParts = allCandidates
                .mapNotNull { candidate ->
                    val m = partRegex.matchEntire(candidate.name) ?: return@mapNotNull null
                    val idx = m.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                    idx to candidate
                }
                .sortedBy { it.first }
            val parts = matchedParts.map { it.second }.ifEmpty { listOf(file) }
            val indices = matchedParts.map { it.first }
            val hasGaps = indices.isEmpty() || indices.first() != 1 || hasSequenceGaps(indices, startFrom = 1)
            val primary = parts.first()
            val format = detectFormatByName(baseWithExt)
                ?: detectFormatFromMagic(primary)
                ?: ArchiveFormat.SEVEN_Z
            return ArchiveVolumeGroup(
                primaryFile = primary,
                displayName = baseWithExt,
                format = format,
                parts = parts,
                totalSizeBytes = parts.sumOf { it.length().coerceAtLeast(0L) },
                isMissingParts = hasGaps,
            )
        }

        // 2. Modern RAR multi-volume: <base>.part1.rar, <base>.part2.rar, ...
        rarPartRegex.matchEntire(name)?.let { match ->
            val baseName = match.groupValues[1]
            val partRegex = Regex("^${Regex.escape(baseName)}\\.part(\\d+)\\.rar$", RegexOption.IGNORE_CASE)
            val matchedParts = allCandidates
                .mapNotNull { candidate ->
                    val m = partRegex.matchEntire(candidate.name) ?: return@mapNotNull null
                    val idx = m.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                    idx to candidate
                }
                .sortedBy { it.first }
            val parts = matchedParts.map { it.second }.ifEmpty { listOf(file) }
            val indices = matchedParts.map { it.first }
            val hasGaps = indices.isEmpty() || indices.first() != 1 || hasSequenceGaps(indices, startFrom = 1)
            return ArchiveVolumeGroup(
                primaryFile = parts.first(),
                displayName = "$baseName.rar",
                format = ArchiveFormat.RAR,
                parts = parts,
                totalSizeBytes = parts.sumOf { it.length().coerceAtLeast(0L) },
                isMissingParts = hasGaps,
            )
        }

        // 3. ZIP split archive: <base>.zip + <base>.z01, .z02, ...
        val zipSplitBase = when {
            zipSplitPartRegex.matches(lower) -> zipSplitPartRegex.matchEntire(name)!!.groupValues[1]
            lower.endsWith(".zip") -> name.dropLast(4)
            else -> null
        }
        if (zipSplitBase != null) {
            val zPartRegex = Regex("^${Regex.escape(zipSplitBase)}\\.z(\\d{2,})$", RegexOption.IGNORE_CASE)
            val zParts = allCandidates
                .mapNotNull { candidate ->
                    val m = zPartRegex.matchEntire(candidate.name) ?: return@mapNotNull null
                    val idx = m.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                    idx to candidate
                }
                .sortedBy { it.first }
            val mainZip = allCandidates.firstOrNull { it.name.equals("$zipSplitBase.zip", ignoreCase = true) }
            if (zParts.isNotEmpty()) {
                val zIndices = zParts.map { it.first }
                val orderedParts = zParts.map { it.second } + listOfNotNull(mainZip)
                val missing = mainZip == null || zIndices.first() != 1 || hasSequenceGaps(zIndices, startFrom = 1)
                return ArchiveVolumeGroup(
                    primaryFile = mainZip ?: zParts.first().second,
                    displayName = mainZip?.name ?: "$zipSplitBase.zip",
                    format = ArchiveFormat.ZIP,
                    parts = orderedParts,
                    totalSizeBytes = orderedParts.sumOf { it.length().coerceAtLeast(0L) },
                    isMissingParts = missing,
                )
            }
        }

        // 4. Legacy RAR multi-volume: <base>.rar + <base>.r00, .r01, ...
        val rarLegacyBase = when {
            rarLegacyPartRegex.matches(lower) -> rarLegacyPartRegex.matchEntire(name)!!.groupValues[1]
            lower.endsWith(".rar") -> name.dropLast(4)
            else -> null
        }
        if (rarLegacyBase != null) {
            val rPartRegex = Regex("^${Regex.escape(rarLegacyBase)}\\.r(\\d{2,})$", RegexOption.IGNORE_CASE)
            val rParts = allCandidates
                .mapNotNull { candidate ->
                    val m = rPartRegex.matchEntire(candidate.name) ?: return@mapNotNull null
                    val idx = m.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                    idx to candidate
                }
                .sortedBy { it.first }
            val mainRar = allCandidates.firstOrNull { it.name.equals("$rarLegacyBase.rar", ignoreCase = true) }
            if (rParts.isNotEmpty()) {
                val rIndices = rParts.map { it.first }
                val orderedParts = listOfNotNull(mainRar) + rParts.map { it.second }
                val missing = mainRar == null || rIndices.first() != 0 || hasSequenceGaps(rIndices, startFrom = 0)
                return ArchiveVolumeGroup(
                    primaryFile = mainRar ?: rParts.first().second,
                    displayName = mainRar?.name ?: "$rarLegacyBase.rar",
                    format = ArchiveFormat.RAR,
                    parts = orderedParts,
                    totalSizeBytes = orderedParts.sumOf { it.length().coerceAtLeast(0L) },
                    isMissingParts = missing,
                )
            }
        }

        // 5. Single archive file (also check if <name>.001 exists alongside, in case user opens unrelated file)
        val format = detectFormatByName(name)
            ?: detectFormatFromMagic(file)
            ?: ArchiveFormat.ZIP
        return ArchiveVolumeGroup(
            primaryFile = file,
            displayName = name,
            format = format,
            parts = listOf(file),
            totalSizeBytes = file.length().coerceAtLeast(0L),
            isMissingParts = false,
        )
    }

    fun discoverArchiveGroups(
        directory: File,
        recursiveDepth: Int = 5,
    ): List<ArchiveVolumeGroup> = discoverArchiveGroupsWithInfo(
        directory = directory,
        recursiveDepth = recursiveDepth,
    ).groups

    fun discoverArchiveGroupsWithInfo(
        directory: File,
        recursiveDepth: Int = 5,
        maxScannedEntries: Int = 20_000,
    ): ArchiveDiscoveryResult {
        if (!directory.exists() || !directory.isDirectory) {
            return ArchiveDiscoveryResult(groups = emptyList(), reachedScanLimit = false)
        }
        val seenGroups = LinkedHashMap<String, ArchiveVolumeGroup>()
        val visitedDirs = HashSet<String>()
        var scannedCount = 0
        var reachedLimit = false
        val skipDirNames = setOf("android", ".git", "node_modules", ".thumbnails")

        fun walk(dir: File, depth: Int) {
            if (scannedCount >= maxScannedEntries) {
                reachedLimit = true
                return
            }
            val canonical = runCatching { dir.canonicalPath }.getOrNull() ?: return
            if (!visitedDirs.add(canonical)) return
            val children = dir.listFiles() ?: return
            for (child in children) {
                if (scannedCount >= maxScannedEntries) {
                    reachedLimit = true
                    break
                }
                scannedCount++
                val childName = child.name
                if (childName.startsWith(".")) continue
                if (child.isDirectory) {
                    if (depth < recursiveDepth && childName.lowercase(Locale.ROOT) !in skipDirNames) {
                        walk(child, depth + 1)
                    }
                } else if (isSupportedArchiveFile(child)) {
                    val group = resolveVolumeGroup(child)
                    val key = runCatching { group.primaryFile.canonicalPath }
                        .getOrDefault(group.primaryFile.absolutePath)
                    seenGroups.putIfAbsent(key, group)
                }
            }
        }

        walk(directory, 0)
        return ArchiveDiscoveryResult(
            groups = seenGroups.values.sortedByDescending { it.primaryFile.lastModified() },
            reachedScanLimit = reachedLimit,
        )
    }

    suspend fun inspectArchive(
        file: File,
        password: String? = null,
    ): Result<ArchiveSummary> = withContext(Dispatchers.IO) {
        try {
            if (!file.exists()) {
                return@withContext Result.failure(IOException("Archivdatei nicht gefunden: ${file.name}"))
            }
            val group = resolveVolumeGroup(file)
            if (group.isMissingParts) {
                return@withContext Result.failure(
                    IOException("Unvollständiges Split-Archiv (${group.displayName}): Mindestens ein Teilarchiv fehlt."),
                )
            }

            val summary = when (group.format) {
                ArchiveFormat.ZIP -> inspectZip(group, password)
                ArchiveFormat.SEVEN_Z -> inspectSevenZ(group, password)
                ArchiveFormat.RAR -> inspectRar(group, password)
                ArchiveFormat.TAR_GZ, ArchiveFormat.TAR_XZ -> inspectTar(group)
            }
            Result.success(summary)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun createArchive(
        request: PackArchiveRequest,
        onProgress: (ArchiveOperationProgress) -> Unit = {},
    ): Result<ArchiveVolumeGroup> = withContext(Dispatchers.IO) {
        try {
            if (!request.format.canCreate) {
                return@withContext Result.failure(
                    IllegalArgumentException("Erstellen von ${request.format.label}-Archiven wird nicht unterstützt."),
                )
            }
            if (request.sourcePaths.isEmpty()) {
                return@withContext Result.failure(
                    IllegalArgumentException("Bitte mindestens eine Datei oder einen Ordner auswählen."),
                )
            }
            val cleanBase = sanitizeBaseName(request.archiveBaseName)
                ?: return@withContext Result.failure(IllegalArgumentException("Ungültiger Archivname."))

            val outputDir = File(request.outputDirectory)
            if (!outputDir.exists() && !outputDir.mkdirs()) {
                return@withContext Result.failure(
                    IOException("Zielordner konnte nicht erstellt werden: ${outputDir.absolutePath}"),
                )
            }
            if (!outputDir.isDirectory) {
                return@withContext Result.failure(
                    IOException("Zielpfad ist kein Ordner: ${outputDir.absolutePath}"),
                )
            }

            val useEncryption = request.format.supportsEncryption &&
                request.encryptionMethod != ArchiveEncryptionMethod.NONE
            if (useEncryption && request.password.isEmpty()) {
                return@withContext Result.failure(
                    IllegalArgumentException("Bitte ein Passwort für die Verschlüsselung eingeben."),
                )
            }
            val passwordChars = if (useEncryption) request.password.toCharArray() else null
            val splitBytes = if (request.format.supportsSplit) request.splitVolumeBytes else null
            if (splitBytes != null && splitBytes < MIN_ZIP_SPLIT_BYTES) {
                return@withContext Result.failure(
                    IllegalArgumentException("Split-Größe muss mindestens 64 KB betragen."),
                )
            }

            val uniqueBase = resolveUniqueArchiveBaseName(
                outputDir = outputDir,
                baseName = cleanBase,
                format = request.format,
                isSplit = splitBytes != null,
            )
            val finalFileName = "$uniqueBase.${request.format.extension}"
            val finalTargetFile = File(outputDir, finalFileName)
            val tempArchiveFile = File(outputDir, ".monolith_pack_${UUID.randomUUID()}.${request.format.extension}")

            onProgress(
                ArchiveOperationProgress(
                    title = "Quellen werden erfasst…",
                    currentItem = "",
                    processedBytes = 0L,
                    totalBytes = 0L,
                ),
            )

            val excludedPaths = setOf(
                runCatching { finalTargetFile.canonicalPath }.getOrDefault(finalTargetFile.absolutePath),
                runCatching { tempArchiveFile.canonicalPath }.getOrDefault(tempArchiveFile.absolutePath),
            )
            val items = collectSourceTreeItems(request.sourcePaths, excludedPaths)
            if (items.isEmpty()) {
                return@withContext Result.failure(
                    IOException("Keine packbaren Dateien oder Ordner gefunden."),
                )
            }
            val totalBytes = items.sumOf { if (it.file.isFile) it.file.length().coerceAtLeast(0L) else 0L }
                .coerceAtLeast(1L)

            val createdGroup = when (request.format) {
                ArchiveFormat.ZIP -> createZipArchive(
                    items = items,
                    totalBytes = totalBytes,
                    outputDir = outputDir,
                    finalZipFile = finalTargetFile,
                    tempZipFile = tempArchiveFile,
                    compressionLevel = request.compressionLevel,
                    encryptionMethod = request.encryptionMethod,
                    passwordChars = passwordChars,
                    splitBytes = splitBytes,
                    onProgress = onProgress,
                )

                ArchiveFormat.SEVEN_Z -> createSevenZArchive(
                    items = items,
                    totalBytes = totalBytes,
                    outputDir = outputDir,
                    finalArchiveFile = finalTargetFile,
                    tempArchiveFile = tempArchiveFile,
                    compressionLevel = request.compressionLevel,
                    passwordChars = passwordChars,
                    encryptFilenames = request.encryptFilenames,
                    splitBytes = splitBytes,
                    onProgress = onProgress,
                )

                ArchiveFormat.TAR_GZ, ArchiveFormat.TAR_XZ -> createTarArchive(
                    items = items,
                    totalBytes = totalBytes,
                    outputDir = outputDir,
                    finalArchiveFile = finalTargetFile,
                    tempArchiveFile = tempArchiveFile,
                    format = request.format,
                    compressionLevel = request.compressionLevel,
                    splitBytes = splitBytes,
                    onProgress = onProgress,
                )

                ArchiveFormat.RAR -> throw IllegalStateException("RAR creation is unsupported")
            }

            onProgress(
                ArchiveOperationProgress(
                    title = "Archiv erstellt",
                    currentItem = createdGroup.displayName,
                    processedBytes = totalBytes,
                    totalBytes = totalBytes,
                    isFinished = true,
                ),
            )
            Result.success(createdGroup)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun extractArchive(
        file: File,
        targetDir: File,
        password: String? = null,
        selectedEntryPaths: Set<String>? = null,
        createSubfolder: Boolean = false,
        conflictPolicy: ExtractConflictPolicy = ExtractConflictPolicy.RENAME,
        deleteAfterExtract: Boolean = false,
        onProgress: (ArchiveOperationProgress) -> Unit = {},
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            if (!file.exists()) {
                return@withContext Result.failure(IOException("Archiv nicht gefunden: ${file.name}"))
            }
            val group = resolveVolumeGroup(file)
            if (group.isMissingParts) {
                return@withContext Result.failure(
                    IOException("Unvollständiges Split-Archiv (${group.displayName}): Mindestens ein Teil fehlt."),
                )
            }

            val effectiveTargetDir = if (createSubfolder) {
                val folderName = stripArchiveExtensions(group.displayName, stripFallbackDot = true)
                    .replace('/', '_')
                    .replace('\\', '_')
                    .ifBlank { "Entpackt" }
                File(targetDir, folderName)
            } else {
                targetDir
            }
            if (!effectiveTargetDir.exists() && !effectiveTargetDir.mkdirs()) {
                return@withContext Result.failure(
                    IOException("Zielordner konnte nicht erstellt werden: ${effectiveTargetDir.absolutePath}"),
                )
            }
            if (!effectiveTargetDir.isDirectory) {
                return@withContext Result.failure(
                    IOException("Zielpfad ist kein Ordner: ${effectiveTargetDir.absolutePath}"),
                )
            }

            val normalizedSelection = selectedEntryPaths
                ?.map { normalizeEntryPath(it) }
                ?.filter { it.isNotEmpty() }
                ?.toSet()
                ?.takeIf { it.isNotEmpty() }

            when (group.format) {
                ArchiveFormat.ZIP -> extractZip(
                    group = group,
                    targetDir = effectiveTargetDir,
                    password = password,
                    selectedPaths = normalizedSelection,
                    conflictPolicy = conflictPolicy,
                    onProgress = onProgress,
                )

                ArchiveFormat.SEVEN_Z -> extractSevenZ(
                    group = group,
                    targetDir = effectiveTargetDir,
                    password = password,
                    selectedPaths = normalizedSelection,
                    conflictPolicy = conflictPolicy,
                    onProgress = onProgress,
                )

                ArchiveFormat.RAR -> extractRar(
                    group = group,
                    targetDir = effectiveTargetDir,
                    password = password,
                    selectedPaths = normalizedSelection,
                    conflictPolicy = conflictPolicy,
                    onProgress = onProgress,
                )

                ArchiveFormat.TAR_GZ, ArchiveFormat.TAR_XZ -> extractTar(
                    group = group,
                    targetDir = effectiveTargetDir,
                    selectedPaths = normalizedSelection,
                    conflictPolicy = conflictPolicy,
                    onProgress = onProgress,
                )
            }

            if (deleteAfterExtract) {
                for (part in group.parts) {
                    if (part.exists()) {
                        part.delete()
                    }
                }
            }

            onProgress(
                ArchiveOperationProgress(
                    title = "Entpacken abgeschlossen",
                    currentItem = effectiveTargetDir.name,
                    processedBytes = 1L,
                    totalBytes = 1L,
                    isFinished = true,
                ),
            )
            Result.success(effectiveTargetDir)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Extracts a single archive entry into a scoped cache folder for direct preview/open.
     */
    suspend fun extractSingleEntryToCache(
        archiveFile: File,
        entryPath: String,
        cacheRootDir: File,
        password: String? = null,
    ): Result<File> = withContext(Dispatchers.IO) {
        val normalizedPath = entryPath.trim().trim('/').replace('\\', '/')
        if (normalizedPath.isEmpty()) {
            return@withContext Result.failure(IOException("Ungültiger Archiveintrag."))
        }
        val archiveKey = runCatching { archiveFile.canonicalPath }
            .getOrDefault(archiveFile.absolutePath)
            .hashCode()
            .toUInt()
            .toString(16)
        val previewDir = File(cacheRootDir, "archive_preview/$archiveKey").apply { mkdirs() }
        val expectedFile = File(previewDir, normalizedPath)
        val extractResult = extractArchive(
            file = archiveFile,
            targetDir = previewDir,
            password = password,
            selectedEntryPaths = setOf(normalizedPath),
            createSubfolder = false,
            conflictPolicy = ExtractConflictPolicy.OVERWRITE,
            deleteAfterExtract = false,
        )
        extractResult.fold(
            onSuccess = {
                if (expectedFile.exists() && expectedFile.isFile) {
                    Result.success(expectedFile)
                } else {
                    Result.failure(IOException("Vorschaudatei konnte nicht gefunden werden: ${expectedFile.name}"))
                }
            },
            onFailure = { Result.failure(it) },
        )
    }

    suspend fun addEntriesToArchive(
        archiveFile: File,
        pathsToAdd: List<String>,
        targetInternalDir: String = "",
        password: String? = null,
        onProgress: (ArchiveOperationProgress) -> Unit = {},
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (pathsToAdd.isEmpty()) {
                return@withContext Result.failure(IllegalArgumentException("Keine Dateien zum Hinzufügen ausgewählt."))
            }
            val group = resolveVolumeGroup(archiveFile)
            if (group.isMultiVolume) {
                return@withContext Result.failure(
                    IllegalStateException("Split-Archive können nicht direkt bearbeitet werden. Bitte zuerst zusammenführen."),
                )
            }
            if (!group.format.canCreate) {
                return@withContext Result.failure(
                    IllegalStateException("${group.format.label}-Archive unterstützen kein nachträgliches Hinzufügen."),
                )
            }
            val internalPrefix = normalizeEntryPath(targetInternalDir)

            when (group.format) {
                ArchiveFormat.ZIP -> addEntriesToZip(
                    archiveFile = group.primaryFile,
                    pathsToAdd = pathsToAdd,
                    internalPrefix = internalPrefix,
                    password = password,
                    onProgress = onProgress,
                )

                ArchiveFormat.SEVEN_Z -> rebuildSevenZArchive(
                    archiveFile = group.primaryFile,
                    password = password,
                    pathsToAdd = pathsToAdd,
                    addPrefix = internalPrefix,
                    onProgress = onProgress,
                )

                ArchiveFormat.TAR_GZ, ArchiveFormat.TAR_XZ -> rebuildTarArchive(
                    archiveFile = group.primaryFile,
                    group = group,
                    pathsToAdd = pathsToAdd,
                    addPrefix = internalPrefix,
                    onProgress = onProgress,
                )

                ArchiveFormat.RAR -> throw IllegalStateException("RAR modification unsupported")
            }

            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteEntriesFromArchive(
        archiveFile: File,
        entryPaths: Set<String>,
        password: String? = null,
        onProgress: (ArchiveOperationProgress) -> Unit = {},
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (entryPaths.isEmpty()) {
                return@withContext Result.success(Unit)
            }
            val group = resolveVolumeGroup(archiveFile)
            if (group.isMultiVolume) {
                return@withContext Result.failure(
                    IllegalStateException("Split-Archive können nicht direkt bearbeitet werden. Bitte zuerst zusammenführen."),
                )
            }
            if (!group.format.canCreate) {
                return@withContext Result.failure(
                    IllegalStateException("${group.format.label}-Archive unterstützen kein Löschen von Einträgen."),
                )
            }
            val normalizedTargets = entryPaths.map { normalizeEntryPath(it) }.filter { it.isNotEmpty() }.toSet()

            when (group.format) {
                ArchiveFormat.ZIP -> deleteEntriesFromZip(
                    archiveFile = group.primaryFile,
                    normalizedTargets = normalizedTargets,
                    password = password,
                    onProgress = onProgress,
                )

                ArchiveFormat.SEVEN_Z -> rebuildSevenZArchive(
                    archiveFile = group.primaryFile,
                    password = password,
                    deleteTargets = normalizedTargets,
                    onProgress = onProgress,
                )

                ArchiveFormat.TAR_GZ, ArchiveFormat.TAR_XZ -> rebuildTarArchive(
                    archiveFile = group.primaryFile,
                    group = group,
                    deleteTargets = normalizedTargets,
                    onProgress = onProgress,
                )

                ArchiveFormat.RAR -> throw IllegalStateException("RAR modification unsupported")
            }

            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun renameEntryInArchive(
        archiveFile: File,
        oldEntryPath: String,
        newName: String,
        password: String? = null,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val cleanName = newName.trim()
            if (cleanName.isEmpty() || cleanName == "." || cleanName == ".." ||
                cleanName.contains('/') || cleanName.contains('\\')
            ) {
                return@withContext Result.failure(IllegalArgumentException("Ungültiger Eintragsname: $newName"))
            }
            val group = resolveVolumeGroup(archiveFile)
            if (group.isMultiVolume) {
                return@withContext Result.failure(
                    IllegalStateException("Split-Archive können nicht direkt bearbeitet werden. Bitte zuerst zusammenführen."),
                )
            }
            if (!group.format.canCreate) {
                return@withContext Result.failure(
                    IllegalStateException("${group.format.label}-Archive unterstützen kein Umbenennen."),
                )
            }
            val oldNorm = normalizeEntryPath(oldEntryPath)
            if (oldNorm.isEmpty()) {
                return@withContext Result.failure(IllegalArgumentException("Ungültiger Quellpfad."))
            }
            val parent = oldNorm.substringBeforeLast('/', "")
            val newNorm = if (parent.isEmpty()) cleanName else "$parent/$cleanName"
            if (oldNorm == newNorm) {
                return@withContext Result.success(Unit)
            }

            when (group.format) {
                ArchiveFormat.ZIP -> renameInZip(group.primaryFile, oldNorm, newNorm, password)
                ArchiveFormat.SEVEN_Z -> rebuildSevenZArchive(
                    archiveFile = group.primaryFile,
                    password = password,
                    renameFrom = oldNorm,
                    renameTo = newNorm,
                )
                ArchiveFormat.TAR_GZ, ArchiveFormat.TAR_XZ -> rebuildTarArchive(
                    archiveFile = group.primaryFile,
                    group = group,
                    renameFrom = oldNorm,
                    renameTo = newNorm,
                )
                ArchiveFormat.RAR -> throw IllegalStateException("RAR modification unsupported")
            }

            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun mergeSplitArchive(
        archiveFile: File,
        outputFile: File,
        onProgress: (ArchiveOperationProgress) -> Unit = {},
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val group = resolveVolumeGroup(archiveFile)
            if (!group.isMultiVolume) {
                return@withContext Result.failure(
                    IllegalArgumentException("Dies ist bereits ein einzelnes Archiv."),
                )
            }
            if (group.isMissingParts) {
                return@withContext Result.failure(
                    IOException("Unvollständiges Split-Archiv: Mindestens ein Teilarchiv fehlt."),
                )
            }
            if (!canBinaryMerge(group)) {
                return@withContext Result.failure(
                    IllegalStateException(
                        "RAR-Teilarchive besitzen eigene Volume-Header und können nicht binär zusammengefügt werden — sie können direkt entpackt oder konvertiert werden.",
                    ),
                )
            }

            outputFile.parentFile?.let { parent ->
                if (!parent.exists() && !parent.mkdirs()) {
                    return@withContext Result.failure(
                        IOException("Zielordner konnte nicht erstellt werden: ${parent.absolutePath}"),
                    )
                }
            }
            val targetOut = resolveUniqueFile(outputFile)
            val tempOut = File(targetOut.parentFile ?: outputFile.parentFile, ".monolith_merge_${UUID.randomUUID()}")
            val totalBytes = group.totalSizeBytes.coerceAtLeast(1L)

            try {
                val firstLower = group.parts.first().name.lowercase(Locale.ROOT)
                if (zipSplitPartRegex.matches(firstLower)) {
                    onProgress(
                        ArchiveOperationProgress(
                            title = "ZIP-Teile werden zusammengeführt…",
                            currentItem = group.displayName,
                            processedBytes = 0L,
                            totalBytes = totalBytes,
                        ),
                    )
                    ZipFile(group.primaryFile).mergeSplitFiles(tempOut)
                    onProgress(
                        ArchiveOperationProgress(
                            title = "ZIP-Teile werden zusammengeführt…",
                            currentItem = targetOut.name,
                            processedBytes = totalBytes,
                            totalBytes = totalBytes,
                        ),
                    )
                } else {
                    var processed = 0L
                    val buf = ByteArray(BUFFER_SIZE)
                    BufferedOutputStream(FileOutputStream(tempOut), BUFFER_SIZE).use { out ->
                        for (part in group.parts) {
                            currentCoroutineContext().ensureActive()
                            FileInputStream(part).use { input ->
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val read = input.read(buf)
                                    if (read == -1) break
                                    out.write(buf, 0, read)
                                    processed += read
                                    onProgress(
                                        ArchiveOperationProgress(
                                            title = "Teile werden zusammengeführt…",
                                            currentItem = part.name,
                                            processedBytes = processed,
                                            totalBytes = totalBytes,
                                        ),
                                    )
                                }
                            }
                        }
                        out.flush()
                    }
                }
                if (targetOut.exists() && !targetOut.delete()) {
                    throw IOException("Zieldatei konnte nicht überschrieben werden: ${targetOut.name}")
                }
                if (!tempOut.renameTo(targetOut)) {
                    throw IOException("Zusammengeführte Datei konnte nicht gespeichert werden.")
                }
                onProgress(
                    ArchiveOperationProgress(
                        title = "Zusammenführen abgeschlossen",
                        currentItem = targetOut.name,
                        processedBytes = totalBytes,
                        totalBytes = totalBytes,
                        isFinished = true,
                    ),
                )
                Result.success(targetOut)
            } finally {
                if (tempOut.exists()) tempOut.delete()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun verifyArchive(
        archiveFile: File,
        password: String? = null,
        onProgress: (ArchiveOperationProgress) -> Unit = {},
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (!archiveFile.exists()) {
                return@withContext Result.failure(IOException("Archiv nicht gefunden: ${archiveFile.name}"))
            }
            val group = resolveVolumeGroup(archiveFile)
            if (group.isMissingParts) {
                return@withContext Result.failure(
                    IOException("Unvollständiges Split-Archiv: Mindestens ein Teilarchiv fehlt."),
                )
            }

            val (checkedFiles, checkedBytes) = when (group.format) {
                ArchiveFormat.ZIP -> verifyZip(group, password, onProgress)
                ArchiveFormat.SEVEN_Z -> verifySevenZ(group, password, onProgress)
                ArchiveFormat.RAR -> verifyRar(group, password, onProgress)
                ArchiveFormat.TAR_GZ, ArchiveFormat.TAR_XZ -> verifyTar(group, onProgress)
            }

            onProgress(
                ArchiveOperationProgress(
                    title = "Prüfung abgeschlossen",
                    currentItem = group.displayName,
                    processedBytes = checkedBytes.coerceAtLeast(1L),
                    totalBytes = checkedBytes.coerceAtLeast(1L),
                    isFinished = true,
                ),
            )
            Result.success(
                "Integritätsprüfung bestanden: $checkedFiles Datei(en) (${formatArchiveBytes(checkedBytes)}) fehlerfrei verifiziert.",
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun convertToFormat(
        archiveFile: File,
        targetFormat: ArchiveFormat,
        password: String? = null,
        newPassword: String? = null,
        onProgress: (ArchiveOperationProgress) -> Unit = {},
    ): Result<File> = withContext(Dispatchers.IO) {
        if (!targetFormat.canCreate) {
            return@withContext Result.failure(
                IllegalArgumentException("Zielformat ${targetFormat.label} unterstützt kein Packen."),
            )
        }
        val group = resolveVolumeGroup(archiveFile)
        val outputDir = group.primaryFile.parentFile ?: archiveFile.parentFile
            ?: return@withContext Result.failure(IOException("Ausgabeordner konnte nicht ermittelt werden."))

        val tempDir = File(System.getProperty("java.io.tmpdir"), "monolith_convert_${UUID.randomUUID()}")
        if (!tempDir.mkdirs()) {
            return@withContext Result.failure(IOException("Temporärer Arbeitsordner konnte nicht erstellt werden."))
        }
        try {
            val extractResult = extractArchive(
                file = group.primaryFile,
                targetDir = tempDir,
                password = password,
                createSubfolder = false,
                conflictPolicy = ExtractConflictPolicy.OVERWRITE,
                deleteAfterExtract = false,
                onProgress = { progress ->
                    onProgress(
                        progress.copy(
                            title = "Konvertierung (1/2 · Entpacken)…",
                        ),
                    )
                },
            )
            extractResult.getOrElse { return@withContext Result.failure(it) }

            val extractedRoots = tempDir.listFiles()?.toList().orEmpty()
            if (extractedRoots.isEmpty()) {
                return@withContext Result.failure(
                    IOException("Das Quellarchiv enthält keine Einträge zur Konvertierung."),
                )
            }

            val baseName = stripArchiveExtensions(group.displayName, stripFallbackDot = true).ifBlank { "Archiv" }
            val useNewPassword = !newPassword.isNullOrEmpty() && targetFormat.supportsEncryption
            val packRequest = PackArchiveRequest(
                sourcePaths = extractedRoots.map { it.absolutePath },
                outputDirectory = outputDir.absolutePath,
                archiveBaseName = baseName,
                format = targetFormat,
                compressionLevel = ArchiveCompressionLevel.NORMAL,
                encryptionMethod = if (useNewPassword) ArchiveEncryptionMethod.AES_256 else ArchiveEncryptionMethod.NONE,
                password = newPassword.orEmpty(),
                splitVolumeBytes = null,
                solidArchive = targetFormat.supportsSolid,
            )
            val created = createArchive(
                request = packRequest,
                onProgress = { progress ->
                    onProgress(
                        progress.copy(
                            title = "Konvertierung (2/2 · ${targetFormat.label} packen)…",
                        ),
                    )
                },
            ).getOrElse { return@withContext Result.failure(it) }

            Result.success(created.primaryFile)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    // -------------------------------------------------------------------------
    // ZIP Implementation
    // -------------------------------------------------------------------------

    private suspend fun <T> withZipTargetFile(
        group: ArchiveVolumeGroup,
        block: suspend (File) -> T,
    ): T {
        val isNumericSplit = group.isMultiVolume &&
            numericSplitRegex.matches(group.parts.first().name)
        if (!isNumericSplit) {
            return block(group.primaryFile)
        }
        val tempZip = File(System.getProperty("java.io.tmpdir"), "monolith_zip_${UUID.randomUUID()}.zip")
        try {
            val buf = ByteArray(BUFFER_SIZE)
            BufferedOutputStream(FileOutputStream(tempZip), BUFFER_SIZE).use { out ->
                for (part in group.parts) {
                    currentCoroutineContext().ensureActive()
                    FileInputStream(part).use { input ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buf)
                            if (read == -1) break
                            out.write(buf, 0, read)
                        }
                    }
                }
                out.flush()
            }
            return block(tempZip)
        } finally {
            tempZip.delete()
        }
    }

    private suspend fun inspectZip(
        group: ArchiveVolumeGroup,
        password: String?,
    ): ArchiveSummary = withZipTargetFile(group) { zipTarget ->
        val zipFile = createZipFileInstance(zipTarget, password)
        val headers = try {
            zipFile.fileHeaders
        } catch (e: ZipException) {
            throw mapZipException(e, hasPassword = !password.isNullOrEmpty())
        }
        var anyEncrypted = runCatching { zipFile.isEncrypted }.getOrDefault(false)
        val rawItems = headers.mapNotNull { header ->
            val normPath = normalizeEntryPath(header.fileName)
            if (normPath.isEmpty()) return@mapNotNull null
            if (header.isEncrypted) anyEncrypted = true
            ArchiveEntryItem(
                path = normPath,
                name = normPath.substringAfterLast('/'),
                parentPath = normPath.substringBeforeLast('/', ""),
                isDirectory = header.isDirectory,
                uncompressedSize = header.uncompressedSize.coerceAtLeast(0L),
                compressedSize = header.compressedSize.coerceAtLeast(0L),
                lastModifiedMs = header.lastModifiedTimeEpoch.coerceAtLeast(0L),
                isEncrypted = header.isEncrypted,
                crc32 = header.crc.takeIf { it != 0L },
            )
        }
        buildArchiveSummary(
            group = group,
            rawItems = rawItems,
            isEncrypted = anyEncrypted,
            requiresPasswordToList = false,
        )
    }

    private suspend fun createZipArchive(
        items: List<SourceTreeItem>,
        totalBytes: Long,
        outputDir: File,
        finalZipFile: File,
        tempZipFile: File,
        compressionLevel: ArchiveCompressionLevel,
        encryptionMethod: ArchiveEncryptionMethod,
        passwordChars: CharArray?,
        splitBytes: Long?,
        onProgress: (ArchiveOperationProgress) -> Unit,
    ): ArchiveVolumeGroup {
        val baseParams = ZipParameters().apply {
            when (compressionLevel) {
                ArchiveCompressionLevel.STORE -> {
                    this.compressionMethod = CompressionMethod.STORE
                    this.compressionLevel = CompressionLevel.NO_COMPRESSION
                }
                ArchiveCompressionLevel.FAST -> {
                    this.compressionMethod = CompressionMethod.DEFLATE
                    this.compressionLevel = CompressionLevel.FAST
                }
                ArchiveCompressionLevel.NORMAL -> {
                    this.compressionMethod = CompressionMethod.DEFLATE
                    this.compressionLevel = CompressionLevel.NORMAL
                }
                ArchiveCompressionLevel.MAXIMUM -> {
                    this.compressionMethod = CompressionMethod.DEFLATE
                    this.compressionLevel = CompressionLevel.MAXIMUM
                }
            }
            if (passwordChars != null && passwordChars.isNotEmpty() &&
                encryptionMethod != ArchiveEncryptionMethod.NONE
            ) {
                isEncryptFiles = true
                when (encryptionMethod) {
                    ArchiveEncryptionMethod.AES_256 -> {
                        this.encryptionMethod = EncryptionMethod.AES
                        aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
                    }
                    ArchiveEncryptionMethod.ZIP_STANDARD -> {
                        this.encryptionMethod = EncryptionMethod.ZIP_STANDARD
                    }
                    ArchiveEncryptionMethod.NONE -> Unit
                }
            }
        }

        val targetForWrite = if (splitBytes != null) finalZipFile else tempZipFile
        val baseWithoutExt = finalZipFile.nameWithoutExtension
        val createdSplitPrefix = Regex("^${Regex.escape(baseWithoutExt)}\\.z\\d{2,}$", RegexOption.IGNORE_CASE)

        val buf = ByteArray(BUFFER_SIZE)
        var processedBytes = 0L

        try {
            val rawOut: OutputStream = if (splitBytes != null) {
                SplitOutputStream(targetForWrite, maxOf(splitBytes, MIN_ZIP_SPLIT_BYTES))
            } else {
                BufferedOutputStream(FileOutputStream(targetForWrite), BUFFER_SIZE)
            }
            ZipOutputStream(rawOut, passwordChars).use { zos ->
                for (item in items) {
                    currentCoroutineContext().ensureActive()
                    val isDir = item.file.isDirectory
                    val entryParams = ZipParameters(baseParams).apply {
                        fileNameInZip = if (isDir) "${item.archivePath.trimEnd('/')}/" else item.archivePath
                        lastModifiedFileTime = item.file.lastModified()
                        entrySize = if (isDir) 0L else item.file.length()
                        if (isDir) {
                            isEncryptFiles = false
                            this.compressionMethod = CompressionMethod.STORE
                        }
                    }
                    zos.putNextEntry(entryParams)
                    if (!isDir) {
                        FileInputStream(item.file).use { input ->
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = input.read(buf)
                                if (read == -1) break
                                zos.write(buf, 0, read)
                                processedBytes += read
                                onProgress(
                                    ArchiveOperationProgress(
                                        title = "ZIP-Archiv wird erstellt…",
                                        currentItem = item.file.name,
                                        processedBytes = processedBytes,
                                        totalBytes = totalBytes,
                                    ),
                                )
                            }
                        }
                    } else {
                        onProgress(
                            ArchiveOperationProgress(
                                title = "ZIP-Archiv wird erstellt…",
                                currentItem = item.file.name,
                                processedBytes = processedBytes,
                                totalBytes = totalBytes,
                            ),
                        )
                    }
                    zos.closeEntry()
                }
            }

            if (splitBytes == null) {
                if (finalZipFile.exists() && !finalZipFile.delete()) {
                    throw IOException("Zieldatei konnte nicht überschrieben werden: ${finalZipFile.name}")
                }
                if (!tempZipFile.renameTo(finalZipFile)) {
                    throw IOException("Archiv konnte nicht gespeichert werden: ${finalZipFile.name}")
                }
            }

            return resolveVolumeGroup(finalZipFile)
        } catch (t: Throwable) {
            if (tempZipFile.exists()) tempZipFile.delete()
            if (splitBytes != null) {
                if (finalZipFile.exists()) finalZipFile.delete()
                outputDir.listFiles()?.forEach { candidate ->
                    if (createdSplitPrefix.matches(candidate.name)) {
                        candidate.delete()
                    }
                }
            }
            throw t
        }
    }

    private suspend fun extractZip(
        group: ArchiveVolumeGroup,
        targetDir: File,
        password: String?,
        selectedPaths: Set<String>?,
        conflictPolicy: ExtractConflictPolicy,
        onProgress: (ArchiveOperationProgress) -> Unit,
    ) = withZipTargetFile(group) { zipTarget ->
        val zipFile = createZipFileInstance(zipTarget, password)
        val headers = try {
            zipFile.fileHeaders
        } catch (e: ZipException) {
            throw mapZipException(e, hasPassword = !password.isNullOrEmpty())
        }
        val selectedHeaders = headers.filter { header ->
            val norm = normalizeEntryPath(header.fileName)
            norm.isNotEmpty() && matchesSelection(norm, selectedPaths)
        }
        if (selectedHeaders.any { it.isEncrypted } && password.isNullOrEmpty()) {
            throw ArchivePasswordException(
                "Dieses ZIP-Archiv ist verschlüsselt. Bitte Passwort eingeben.",
                isWrongPassword = false,
            )
        }
        val totalBytes = selectedHeaders
            .sumOf { if (it.isDirectory) 0L else it.uncompressedSize.coerceAtLeast(0L) }
            .coerceAtLeast(1L)
        var processedBytes = 0L
        val buf = ByteArray(BUFFER_SIZE)

        for (header in selectedHeaders) {
            currentCoroutineContext().ensureActive()
            val norm = normalizeEntryPath(header.fileName)
            val safeTarget = resolveSafeExtractTarget(targetDir, norm)
            if (header.isDirectory) {
                if (!safeTarget.exists() && !safeTarget.mkdirs()) {
                    throw IOException("Ordner konnte nicht erstellt werden: ${safeTarget.name}")
                }
                continue
            }
            val destFile = resolveExtractFileConflict(safeTarget, conflictPolicy) ?: run {
                processedBytes += header.uncompressedSize.coerceAtLeast(0L)
                onProgress(
                    ArchiveOperationProgress(
                        title = "ZIP wird entpackt…",
                        currentItem = safeTarget.name,
                        processedBytes = processedBytes,
                        totalBytes = totalBytes,
                    ),
                )
                continue
            }
            destFile.parentFile?.mkdirs()
            val tempOut = File(destFile.parentFile, ".monolith_ext_${UUID.randomUUID()}")
            try {
                zipFile.getInputStream(header).use { input ->
                    BufferedOutputStream(FileOutputStream(tempOut), BUFFER_SIZE).use { out ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buf)
                            if (read == -1) break
                            out.write(buf, 0, read)
                            processedBytes += read
                            onProgress(
                                ArchiveOperationProgress(
                                    title = "ZIP wird entpackt…",
                                    currentItem = destFile.name,
                                    processedBytes = processedBytes,
                                    totalBytes = totalBytes,
                                ),
                            )
                        }
                        out.flush()
                    }
                }
                if (destFile.exists() && !destFile.delete()) {
                    throw IOException("Vorhandene Datei konnte nicht ersetzt werden: ${destFile.name}")
                }
                if (!tempOut.renameTo(destFile)) {
                    throw IOException("Entpackte Datei konnte nicht gespeichert werden: ${destFile.name}")
                }
                if (header.lastModifiedTimeEpoch > 0L) {
                    runCatching { destFile.setLastModified(header.lastModifiedTimeEpoch) }
                }
            } catch (e: ZipException) {
                throw mapZipException(e, hasPassword = !password.isNullOrEmpty())
            } catch (e: IOException) {
                if (header.isEncrypted && isLikelyZipPasswordIoError(e)) {
                    throw ArchivePasswordException(
                        "Falsches Passwort für ZIP-Archiv.",
                        isWrongPassword = true,
                        cause = e,
                    )
                }
                throw e
            } finally {
                if (tempOut.exists()) tempOut.delete()
            }
        }
    }

    private suspend fun verifyZip(
        group: ArchiveVolumeGroup,
        password: String?,
        onProgress: (ArchiveOperationProgress) -> Unit,
    ): Pair<Int, Long> = withZipTargetFile(group) { zipTarget ->
        val zipFile = createZipFileInstance(zipTarget, password)
        val headers = try {
            zipFile.fileHeaders
        } catch (e: ZipException) {
            throw mapZipException(e, hasPassword = !password.isNullOrEmpty())
        }
        val fileHeaders = headers.filter { !it.isDirectory }
        if (fileHeaders.any { it.isEncrypted } && password.isNullOrEmpty()) {
            throw ArchivePasswordException(
                "Passwort zum Prüfen des verschlüsselten ZIP-Archivs erforderlich.",
                isWrongPassword = false,
            )
        }
        val totalBytes = fileHeaders.sumOf { it.uncompressedSize.coerceAtLeast(0L) }.coerceAtLeast(1L)
        var processed = 0L
        val buf = ByteArray(BUFFER_SIZE)
        for (header in fileHeaders) {
            currentCoroutineContext().ensureActive()
            try {
                zipFile.getInputStream(header).use { input ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buf)
                        if (read == -1) break
                        processed += read
                        onProgress(
                            ArchiveOperationProgress(
                                title = "ZIP-Integrität wird geprüft…",
                                currentItem = header.fileName.substringAfterLast('/'),
                                processedBytes = processed,
                                totalBytes = totalBytes,
                            ),
                        )
                    }
                }
            } catch (e: ZipException) {
                throw mapZipException(e, hasPassword = !password.isNullOrEmpty())
            } catch (e: IOException) {
                if (header.isEncrypted && isLikelyZipPasswordIoError(e)) {
                    throw ArchivePasswordException(
                        "Falsches Passwort für ZIP-Archiv.",
                        isWrongPassword = true,
                        cause = e,
                    )
                }
                throw e
            }
        }
        fileHeaders.size to processed
    }

    private suspend fun addEntriesToZip(
        archiveFile: File,
        pathsToAdd: List<String>,
        internalPrefix: String,
        password: String?,
        onProgress: (ArchiveOperationProgress) -> Unit,
    ) {
        val zipFile = createZipFileInstance(archiveFile, password)
        val headers = zipFile.fileHeaders
        val existingEncrypted = headers.firstOrNull { it.isEncrypted }
        if (existingEncrypted != null && password.isNullOrEmpty()) {
            throw ArchivePasswordException(
                "Passwort erforderlich, um verschlüsselte Einträge hinzuzufügen.",
                isWrongPassword = false,
            )
        }

        val params = ZipParameters().apply {
            compressionMethod = CompressionMethod.DEFLATE
            compressionLevel = CompressionLevel.NORMAL
            isIncludeRootFolder = true
            if (internalPrefix.isNotEmpty()) {
                rootFolderNameInZip = internalPrefix
            }
            if (!password.isNullOrEmpty()) {
                isEncryptFiles = true
                if (existingEncrypted?.encryptionMethod == EncryptionMethod.ZIP_STANDARD) {
                    encryptionMethod = EncryptionMethod.ZIP_STANDARD
                } else {
                    encryptionMethod = EncryptionMethod.AES
                    aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
                }
            }
        }

        val files = pathsToAdd.map { File(it) }.filter { it.exists() }
        val total = files.size.toLong().coerceAtLeast(1L)
        files.forEachIndexed { index, source ->
            currentCoroutineContext().ensureActive()
            onProgress(
                ArchiveOperationProgress(
                    title = "Einträge werden hinzugefügt…",
                    currentItem = source.name,
                    processedBytes = index.toLong(),
                    totalBytes = total,
                ),
            )
            if (source.isDirectory) {
                zipFile.addFolder(source, ZipParameters(params))
            } else {
                zipFile.addFile(source, ZipParameters(params))
            }
        }
    }

    private fun deleteEntriesFromZip(
        archiveFile: File,
        normalizedTargets: Set<String>,
        password: String?,
        onProgress: (ArchiveOperationProgress) -> Unit,
    ) {
        val zipFile = createZipFileInstance(archiveFile, password)
        val toRemove = zipFile.fileHeaders
            .filter { header ->
                val norm = normalizeEntryPath(header.fileName)
                norm.isNotEmpty() && matchesSelection(norm, normalizedTargets)
            }
            .map { it.fileName }
        if (toRemove.isEmpty()) return
        onProgress(
            ArchiveOperationProgress(
                title = "Einträge werden entfernt…",
                currentItem = toRemove.first().substringAfterLast('/'),
                processedBytes = 0L,
                totalBytes = toRemove.size.toLong(),
            ),
        )
        zipFile.removeFiles(toRemove)
    }

    private fun renameInZip(
        archiveFile: File,
        oldNorm: String,
        newNorm: String,
        password: String?,
    ) {
        val zipFile = createZipFileInstance(archiveFile, password)
        val headers = zipFile.fileHeaders
        val existingNorms = headers.map { normalizeEntryPath(it.fileName) }.toSet()
        if (newNorm in existingNorms) {
            throw IOException("Ein Eintrag mit dem Namen „${newNorm.substringAfterLast('/')}“ existiert bereits.")
        }

        val renameMap = LinkedHashMap<String, String>()
        for (header in headers) {
            val norm = normalizeEntryPath(header.fileName)
            when {
                norm == oldNorm -> {
                    renameMap[header.fileName] = if (header.isDirectory) "$newNorm/" else newNorm
                }
                norm.startsWith("$oldNorm/") -> {
                    val suffix = norm.removePrefix("$oldNorm/")
                    val updated = "$newNorm/$suffix"
                    renameMap[header.fileName] = if (header.isDirectory) "$updated/" else updated
                }
            }
        }
        if (renameMap.isEmpty()) {
            throw IOException("Eintrag im Archiv nicht gefunden: $oldNorm")
        }
        zipFile.renameFiles(renameMap)
    }

    private fun createZipFileInstance(file: File, password: String?): ZipFile {
        return if (!password.isNullOrEmpty()) {
            ZipFile(file, password.toCharArray())
        } else {
            ZipFile(file)
        }
    }

    private fun mapZipException(e: ZipException, hasPassword: Boolean): Throwable {
        val msg = e.message.orEmpty().lowercase(Locale.ROOT)
        return when {
            e.type == ZipException.Type.WRONG_PASSWORD -> ArchivePasswordException(
                message = if (hasPassword) "Falsches Passwort für ZIP-Archiv." else "Passwort für ZIP-Archiv erforderlich.",
                isWrongPassword = hasPassword,
                cause = e,
            )
            e.type == ZipException.Type.CHECKSUM_MISMATCH && msg.contains("password") -> ArchivePasswordException(
                message = "Falsches Passwort für ZIP-Archiv.",
                isWrongPassword = true,
                cause = e,
            )
            msg.contains("password") || msg.contains("wrong password") || msg.contains("empty or null password") -> {
                ArchivePasswordException(
                    message = if (hasPassword) "Falsches Passwort für ZIP-Archiv." else "Passwort für ZIP-Archiv erforderlich.",
                    isWrongPassword = hasPassword,
                    cause = e,
                )
            }
            else -> e
        }
    }

    private fun isLikelyZipPasswordIoError(e: IOException): Boolean {
        val msg = e.message.orEmpty().lowercase(Locale.ROOT)
        return msg.contains("password") || msg.contains("hmac") || msg.contains("aes") || msg.contains("wrong")
    }

    // -------------------------------------------------------------------------
    // 7z Implementation
    // -------------------------------------------------------------------------

    private fun openSevenZFile(group: ArchiveVolumeGroup, password: String?): SevenZFile {
        val channel = if (group.parts.size == 1) {
            MultiReadOnlySeekableByteChannel.forFiles(group.primaryFile)
        } else {
            MultiReadOnlySeekableByteChannel.forFiles(*group.parts.toTypedArray())
        }
        return try {
            val builder = SevenZFile.builder().setSeekableByteChannel(channel)
            if (!password.isNullOrEmpty()) {
                builder.setPassword(password.toCharArray())
            }
            builder.get()
        } catch (t: Throwable) {
            runCatching { channel.close() }
            throw t
        }
    }

    private fun inspectSevenZ(
        group: ArchiveVolumeGroup,
        password: String?,
    ): ArchiveSummary {
        val sevenZ = try {
            openSevenZFile(group, password)
        } catch (e: PasswordRequiredException) {
            if (password.isNullOrEmpty()) {
                return ArchiveSummary(
                    group = group,
                    entries = emptyList(),
                    isEncrypted = true,
                    requiresPasswordToList = true,
                    totalUncompressedBytes = 0L,
                    totalCompressedBytes = group.totalSizeBytes,
                    fileCount = 0,
                    folderCount = 0,
                )
            }
            throw ArchivePasswordException("Falsches Passwort für 7z-Archiv.", isWrongPassword = true, cause = e)
        } catch (e: IOException) {
            if (isSevenZHeaderPasswordFailure(e, hasPassword = !password.isNullOrEmpty())) {
                if (password.isNullOrEmpty()) {
                    return ArchiveSummary(
                        group = group,
                        entries = emptyList(),
                        isEncrypted = true,
                        requiresPasswordToList = true,
                        totalUncompressedBytes = 0L,
                        totalCompressedBytes = group.totalSizeBytes,
                        fileCount = 0,
                        folderCount = 0,
                    )
                }
                throw ArchivePasswordException("Falsches Passwort für 7z-Archiv.", isWrongPassword = true, cause = e)
            }
            throw e
        }

        sevenZ.use { sz ->
            val allEntries = sz.entries.toList()
            var anyEncrypted = false
            val encryptedPaths = HashSet<String>()
            try {
                while (true) {
                    val entry = sz.nextEntry ?: break
                    if (isSevenZEntryEncrypted(entry)) {
                        anyEncrypted = true
                        val normPath = normalizeEntryPath(entry.name.orEmpty())
                        if (normPath.isNotEmpty()) encryptedPaths.add(normPath)
                    }
                }
            } catch (_: PasswordRequiredException) {
                anyEncrypted = true
            } catch (e: IOException) {
                if (password.isNullOrEmpty() && isSevenZHeaderPasswordFailure(e, hasPassword = false)) {
                    anyEncrypted = true
                }
            }

            val rawItems = allEntries.mapNotNull { entry ->
                if (entry.isAntiItem) return@mapNotNull null
                val normPath = normalizeEntryPath(entry.name.orEmpty())
                if (normPath.isEmpty()) return@mapNotNull null
                val entryEncrypted = isSevenZEntryEncrypted(entry) ||
                    normPath in encryptedPaths ||
                    (anyEncrypted && !entry.isDirectory)
                if (entryEncrypted) anyEncrypted = true
                val lastModMs = runCatching {
                    if (entry.hasLastModifiedDate) entry.lastModifiedDate.time else 0L
                }.getOrDefault(0L)
                val crc = runCatching {
                    if (entry.hasCrc) entry.crcValue else null
                }.getOrNull()
                ArchiveEntryItem(
                    path = normPath,
                    name = normPath.substringAfterLast('/'),
                    parentPath = normPath.substringBeforeLast('/', ""),
                    isDirectory = entry.isDirectory,
                    uncompressedSize = entry.size.coerceAtLeast(0L),
                    compressedSize = 0L,
                    lastModifiedMs = lastModMs,
                    isEncrypted = entryEncrypted,
                    crc32 = crc,
                )
            }
            return buildArchiveSummary(
                group = group,
                rawItems = rawItems,
                isEncrypted = anyEncrypted,
                requiresPasswordToList = false,
            )
        }
    }

    private suspend fun createSevenZArchive(
        items: List<SourceTreeItem>,
        totalBytes: Long,
        outputDir: File,
        finalArchiveFile: File,
        tempArchiveFile: File,
        compressionLevel: ArchiveCompressionLevel,
        passwordChars: CharArray?,
        encryptFilenames: Boolean,
        splitBytes: Long?,
        onProgress: (ArchiveOperationProgress) -> Unit,
    ): ArchiveVolumeGroup {
        val method = when (compressionLevel) {
            ArchiveCompressionLevel.STORE -> SevenZMethod.COPY
            ArchiveCompressionLevel.FAST -> SevenZMethod.DEFLATE
            ArchiveCompressionLevel.NORMAL, ArchiveCompressionLevel.MAXIMUM -> SevenZMethod.LZMA2
        }
        val buf = ByteArray(BUFFER_SIZE)
        var processedBytes = 0L

        try {
            val out = if (passwordChars != null && passwordChars.isNotEmpty()) {
                SevenZOutputFile(tempArchiveFile, passwordChars)
            } else {
                SevenZOutputFile(tempArchiveFile)
            }
            out.use { szOut ->
                szOut.setContentCompression(method)
                for (item in items) {
                    currentCoroutineContext().ensureActive()
                    val isDir = item.file.isDirectory
                    val entryName = if (isDir) "${item.archivePath.trimEnd('/')}/" else item.archivePath
                    val entry = szOut.createArchiveEntry(item.file, entryName)
                    entry.isDirectory = isDir
                    szOut.putArchiveEntry(entry)
                    if (!isDir) {
                        FileInputStream(item.file).use { input ->
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = input.read(buf)
                                if (read == -1) break
                                szOut.write(buf, 0, read)
                                processedBytes += read
                                onProgress(
                                    ArchiveOperationProgress(
                                        title = "7z-Archiv wird erstellt…",
                                        currentItem = item.file.name,
                                        processedBytes = processedBytes,
                                        totalBytes = totalBytes,
                                    ),
                                )
                            }
                        }
                    } else {
                        onProgress(
                            ArchiveOperationProgress(
                                title = "7z-Archiv wird erstellt…",
                                currentItem = item.file.name,
                                processedBytes = processedBytes,
                                totalBytes = totalBytes,
                            ),
                        )
                    }
                    szOut.closeArchiveEntry()
                }
                szOut.finish()
            }

            if (encryptFilenames && passwordChars != null && passwordChars.isNotEmpty()) {
                encryptSevenZHeader(tempArchiveFile, passwordChars)
            }

            if (splitBytes != null) {
                val firstPart = splitBinaryFile(
                    sourceFile = tempArchiveFile,
                    outputDir = outputDir,
                    baseArchiveName = finalArchiveFile.name,
                    splitVolumeBytes = splitBytes,
                    onProgress = onProgress,
                )
                tempArchiveFile.delete()
                return resolveVolumeGroup(firstPart)
            } else {
                if (finalArchiveFile.exists() && !finalArchiveFile.delete()) {
                    throw IOException("Zieldatei konnte nicht überschrieben werden: ${finalArchiveFile.name}")
                }
                if (!tempArchiveFile.renameTo(finalArchiveFile)) {
                    throw IOException("7z-Archiv konnte nicht gespeichert werden: ${finalArchiveFile.name}")
                }
                return resolveVolumeGroup(finalArchiveFile)
            }
        } finally {
            if (tempArchiveFile.exists()) tempArchiveFile.delete()
        }
    }

    /**
     * Encrypts a 7z archive's plaintext header into a standard kEncodedHeader (0x17)
     * using AES256SHA256 so filenames and folder structure require a password to list.
     */
    private fun encryptSevenZHeader(
        sevenZFile: File,
        passwordChars: CharArray,
    ) {
        java.io.RandomAccessFile(sevenZFile, "rw").use { raf ->
            if (raf.length() < 32L) return
            val sigHeader = ByteArray(32)
            raf.seek(0L)
            raf.readFully(sigHeader)
            val sigBuf = java.nio.ByteBuffer.wrap(sigHeader).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val nextHeaderOffset = sigBuf.getLong(12)
            val nextHeaderSize = sigBuf.getLong(20).toInt()
            if (nextHeaderOffset < 0L || nextHeaderSize <= 0) return

            val headerFilePos = 32L + nextHeaderOffset
            val plainHeader = ByteArray(nextHeaderSize)
            raf.seek(headerFilePos)
            raf.readFully(plainHeader)
            // Already encoded/encrypted
            if (plainHeader.firstOrNull() == 0x17.toByte()) return

            val iv = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
            val numCyclesPower = 19
            val pwBytes = String(passwordChars).toByteArray(Charsets.UTF_16LE)
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val counter = ByteArray(8)
            val totalCycles = 1 shl numCyclesPower
            for (i in 0 until totalCycles) {
                digest.update(pwBytes)
                var v = i.toLong()
                for (b in 0 until 8) {
                    counter[b] = (v and 0xFFL).toByte()
                    v = v ushr 8
                }
                digest.update(counter)
            }
            val key = digest.digest()

            val paddedLen = ((plainHeader.size + 15) / 16) * 16
            val paddedHeader = plainHeader.copyOf(paddedLen)
            val cipher = javax.crypto.Cipher.getInstance("AES/CBC/NoPadding")
            cipher.init(
                javax.crypto.Cipher.ENCRYPT_MODE,
                javax.crypto.spec.SecretKeySpec(key, "AES"),
                javax.crypto.spec.IvParameterSpec(iv),
            )
            val encryptedHeader = cipher.doFinal(paddedHeader)

            fun writeSevenZUint64(out: java.io.ByteArrayOutputStream, value: Long) {
                var mask = 0x80
                var firstByte = 0
                var bytesNeeded = 0
                for (i in 0 until 8) {
                    if (value < (1L shl (7 * (i + 1)))) {
                        firstByte = (value ushr (8 * i)).toInt() or (mask xor 0xFF)
                        //Wait: standard 7z encoding:
                        break
                    }
                    mask = mask ushr 1
                    bytesNeeded++
                }
                if (bytesNeeded == 8) {
                    out.write(0xFF)
                    for (i in 0 until 8) {
                        out.write(((value ushr (8 * i)) and 0xFFL).toInt())
                    }
                } else {
                    var prefixMask = 0
                    for (j in 0 until bytesNeeded) {
                        prefixMask = prefixMask or (0x80 ushr j)
                    }
                    val topBits = ((value ushr (8 * bytesNeeded)) and ((0x7F ushr bytesNeeded).toLong())).toInt()
                    out.write(prefixMask or topBits)
                    for (i in 0 until bytesNeeded) {
                        out.write(((value ushr (8 * i)) and 0xFFL).toInt())
                    }
                }
            }

            val encodedMeta = java.io.ByteArrayOutputStream().apply {
                write(0x17) // kEncodedHeader
                write(0x06) // kPackInfo
                writeSevenZUint64(this, nextHeaderOffset)
                writeSevenZUint64(this, 1L) // numPackStreams
                write(0x09) // kSize
                writeSevenZUint64(this, encryptedHeader.size.toLong())
                write(0x00) // kEnd kPackInfo

                write(0x07) // kUnpackInfo
                write(0x0B) // kFolder
                writeSevenZUint64(this, 1L) // numFolders
                write(0x00) // external = 0
                writeSevenZUint64(this, 1L) // numCoders = 1
                // Coder AES256SHA256: idSize=4 | hasAttributes=0x20 => 0x24
                write(0x24)
                write(byteArrayOf(0x06, 0xF1.toByte(), 0x07, 0x01))
                writeSevenZUint64(this, 18L) // propsSize = 2 + 16
                write(numCyclesPower or 0x40) // ivPresent=1 (bit 6), saltPresent=0 (bit 7)
                write(15) // ivSize - 1
                write(iv)
                write(0x0C) // kCodersUnpackSize
                writeSevenZUint64(this, plainHeader.size.toLong())
                write(0x00) // kEnd kUnpackInfo
                write(0x00) // kEnd StreamsInfo
            }.toByteArray()

            // Write encrypted header + encoded header metadata
            raf.seek(headerFilePos)
            raf.write(encryptedHeader)
            raf.write(encodedMeta)
            raf.setLength(headerFilePos + encryptedHeader.size + encodedMeta.size)

            val newNextHeaderOffset = nextHeaderOffset + encryptedHeader.size
            val newNextHeaderSize = encodedMeta.size.toLong()
            val headerCrc = java.util.zip.CRC32().apply { update(encodedMeta) }.value
            sigBuf.putLong(12, newNextHeaderOffset)
            sigBuf.putLong(20, newNextHeaderSize)
            sigBuf.putInt(28, headerCrc.toInt())
            val startCrc = java.util.zip.CRC32().apply { update(sigHeader, 12, 20) }.value
            sigBuf.putInt(8, startCrc.toInt())

            raf.seek(0L)
            raf.write(sigHeader)
        }
    }

    private suspend fun extractSevenZ(
        group: ArchiveVolumeGroup,
        targetDir: File,
        password: String?,
        selectedPaths: Set<String>?,
        conflictPolicy: ExtractConflictPolicy,
        onProgress: (ArchiveOperationProgress) -> Unit,
    ) {
        val sevenZ = try {
            openSevenZFile(group, password)
        } catch (e: PasswordRequiredException) {
            throw ArchivePasswordException(
                message = if (password.isNullOrEmpty()) "Passwort für 7z-Archiv erforderlich." else "Falsches Passwort für 7z-Archiv.",
                isWrongPassword = !password.isNullOrEmpty(),
                cause = e,
            )
        } catch (e: IOException) {
            if (isSevenZHeaderPasswordFailure(e, hasPassword = !password.isNullOrEmpty())) {
                throw ArchivePasswordException(
                    message = if (password.isNullOrEmpty()) "Passwort für 7z-Archiv erforderlich." else "Falsches Passwort für 7z-Archiv.",
                    isWrongPassword = !password.isNullOrEmpty(),
                    cause = e,
                )
            }
            throw e
        }

        sevenZ.use { sz ->
            val allEntries = sz.entries.filter { !it.isAntiItem }
            val totalBytes = allEntries
                .filter { entry ->
                    val norm = normalizeEntryPath(entry.name.orEmpty())
                    !entry.isDirectory && norm.isNotEmpty() && matchesSelection(norm, selectedPaths)
                }
                .sumOf { it.size.coerceAtLeast(0L) }
                .coerceAtLeast(1L)

            var processedBytes = 0L
            val buf = ByteArray(BUFFER_SIZE)

            while (true) {
                currentCoroutineContext().ensureActive()
                val entry = sz.nextEntry ?: break
                if (entry.isAntiItem) continue
                val norm = normalizeEntryPath(entry.name.orEmpty())
                if (norm.isEmpty() || !matchesSelection(norm, selectedPaths)) continue

                val safeTarget = resolveSafeExtractTarget(targetDir, norm)
                if (entry.isDirectory) {
                    if (!safeTarget.exists() && !safeTarget.mkdirs()) {
                        throw IOException("Ordner konnte nicht erstellt werden: ${safeTarget.name}")
                    }
                    continue
                }
                val entryEncrypted = isSevenZEntryEncrypted(entry)
                if (entryEncrypted && password.isNullOrEmpty()) {
                    throw ArchivePasswordException(
                        "Dieses 7z-Archiv ist verschlüsselt. Bitte Passwort eingeben.",
                        isWrongPassword = false,
                    )
                }
                val destFile = resolveExtractFileConflict(safeTarget, conflictPolicy) ?: run {
                    processedBytes += entry.size.coerceAtLeast(0L)
                    onProgress(
                        ArchiveOperationProgress(
                            title = "7z wird entpackt…",
                            currentItem = safeTarget.name,
                            processedBytes = processedBytes,
                            totalBytes = totalBytes,
                        ),
                    )
                    continue
                }
                destFile.parentFile?.mkdirs()
                val tempOut = File(destFile.parentFile, ".monolith_ext_${UUID.randomUUID()}")
                try {
                    BufferedOutputStream(FileOutputStream(tempOut), BUFFER_SIZE).use { out ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = sz.read(buf)
                            if (read <= 0) break
                            out.write(buf, 0, read)
                            processedBytes += read
                            onProgress(
                                ArchiveOperationProgress(
                                    title = "7z wird entpackt…",
                                    currentItem = destFile.name,
                                    processedBytes = processedBytes,
                                    totalBytes = totalBytes,
                                ),
                            )
                        }
                        out.flush()
                    }
                    if (destFile.exists() && !destFile.delete()) {
                        throw IOException("Vorhandene Datei konnte nicht ersetzt werden: ${destFile.name}")
                    }
                    if (!tempOut.renameTo(destFile)) {
                        throw IOException("Entpackte Datei konnte nicht gespeichert werden: ${destFile.name}")
                    }
                    runCatching {
                        if (entry.hasLastModifiedDate) {
                            destFile.setLastModified(entry.lastModifiedDate.time)
                        }
                    }
                } catch (e: PasswordRequiredException) {
                    throw ArchivePasswordException(
                        message = if (password.isNullOrEmpty()) "Passwort für 7z-Archiv erforderlich." else "Falsches Passwort für 7z-Archiv.",
                        isWrongPassword = !password.isNullOrEmpty(),
                        cause = e,
                    )
                } catch (e: IOException) {
                    if (entryEncrypted || !password.isNullOrEmpty()) {
                        if (isSevenZDataPasswordFailure(e)) {
                            throw ArchivePasswordException(
                                "Falsches Passwort für 7z-Archiv.",
                                isWrongPassword = true,
                                cause = e,
                            )
                        }
                    }
                    throw e
                } finally {
                    if (tempOut.exists()) tempOut.delete()
                }
            }
        }
    }

    private suspend fun verifySevenZ(
        group: ArchiveVolumeGroup,
        password: String?,
        onProgress: (ArchiveOperationProgress) -> Unit,
    ): Pair<Int, Long> {
        val sevenZ = try {
            openSevenZFile(group, password)
        } catch (e: PasswordRequiredException) {
            throw ArchivePasswordException(
                message = if (password.isNullOrEmpty()) "Passwort für 7z-Archiv erforderlich." else "Falsches Passwort für 7z-Archiv.",
                isWrongPassword = !password.isNullOrEmpty(),
                cause = e,
            )
        } catch (e: IOException) {
            if (isSevenZHeaderPasswordFailure(e, hasPassword = !password.isNullOrEmpty())) {
                throw ArchivePasswordException(
                    message = if (password.isNullOrEmpty()) "Passwort für 7z-Archiv erforderlich." else "Falsches Passwort für 7z-Archiv.",
                    isWrongPassword = !password.isNullOrEmpty(),
                    cause = e,
                )
            }
            throw e
        }

        sevenZ.use { sz ->
            val totalBytes = sz.entries
                .filter { !it.isAntiItem && !it.isDirectory }
                .sumOf { it.size.coerceAtLeast(0L) }
                .coerceAtLeast(1L)
            var fileCount = 0
            var processed = 0L
            val buf = ByteArray(BUFFER_SIZE)
            while (true) {
                currentCoroutineContext().ensureActive()
                val entry = sz.nextEntry ?: break
                if (entry.isAntiItem || entry.isDirectory) continue
                val encrypted = isSevenZEntryEncrypted(entry)
                if (encrypted && password.isNullOrEmpty()) {
                    throw ArchivePasswordException(
                        "Passwort zum Prüfen des verschlüsselten 7z-Archivs erforderlich.",
                        isWrongPassword = false,
                    )
                }
                fileCount++
                try {
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = sz.read(buf)
                        if (read <= 0) break
                        processed += read
                        onProgress(
                            ArchiveOperationProgress(
                                title = "7z-Integrität wird geprüft…",
                                currentItem = entry.name.orEmpty().substringAfterLast('/'),
                                processedBytes = processed,
                                totalBytes = totalBytes,
                            ),
                        )
                    }
                } catch (e: PasswordRequiredException) {
                    throw ArchivePasswordException("Falsches Passwort für 7z-Archiv.", isWrongPassword = true, cause = e)
                } catch (e: IOException) {
                    if ((encrypted || !password.isNullOrEmpty()) && isSevenZDataPasswordFailure(e)) {
                        throw ArchivePasswordException("Falsches Passwort für 7z-Archiv.", isWrongPassword = true, cause = e)
                    }
                    throw e
                }
            }
            return fileCount to processed
        }
    }

    private suspend fun rebuildSevenZArchive(
        archiveFile: File,
        password: String?,
        pathsToAdd: List<String> = emptyList(),
        addPrefix: String = "",
        deleteTargets: Set<String> = emptySet(),
        renameFrom: String? = null,
        renameTo: String? = null,
        onProgress: (ArchiveOperationProgress) -> Unit = {},
    ) {
        val parentDir = archiveFile.parentFile ?: throw IOException("Kein übergeordneter Ordner vorhanden.")
        val tempOut = File(parentDir, ".monolith_7z_${UUID.randomUUID()}.7z")
        val buf = ByteArray(BUFFER_SIZE)

        try {
            val existingEncrypted = run {
                val szCheck = openSevenZFile(resolveVolumeGroup(archiveFile), password)
                szCheck.use { check ->
                    var encrypted = false
                    try {
                        while (true) {
                            val entry = check.nextEntry ?: break
                            if (isSevenZEntryEncrypted(entry)) {
                                encrypted = true
                                break
                            }
                        }
                    } catch (_: PasswordRequiredException) {
                        encrypted = true
                    }
                    encrypted
                }
            }
            if (existingEncrypted && password.isNullOrEmpty()) {
                throw ArchivePasswordException("Passwort für verschlüsseltes 7z-Archiv erforderlich.")
            }

            val outPwd = password?.takeIf { it.isNotEmpty() }?.toCharArray()
            val szIn = openSevenZFile(resolveVolumeGroup(archiveFile), password)
            szIn.use { input ->
                val allExisting = input.entries.filter { !it.isAntiItem }
                if (renameFrom != null && renameTo != null) {
                    val existsTarget = allExisting.any { normalizeEntryPath(it.name.orEmpty()) == renameTo }
                    if (existsTarget) {
                        throw IOException("Ein Eintrag mit dem Namen „${renameTo.substringAfterLast('/')}“ existiert bereits.")
                    }
                }
                val existingBytes = allExisting.sumOf { if (it.isDirectory) 0L else it.size.coerceAtLeast(0L) }
                val addItems = if (pathsToAdd.isNotEmpty()) {
                    collectSourceTreeItems(pathsToAdd, emptySet(), addPrefix)
                } else {
                    emptyList()
                }
                val addBytes = addItems.sumOf { if (it.file.isFile) it.file.length().coerceAtLeast(0L) else 0L }
                val totalBytes = (existingBytes + addBytes).coerceAtLeast(1L)
                var processed = 0L

                val szOut = if (outPwd != null) SevenZOutputFile(tempOut, outPwd) else SevenZOutputFile(tempOut)
                szOut.use { output ->
                    output.setContentCompression(SevenZMethod.LZMA2)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val entry = input.nextEntry ?: break
                        if (entry.isAntiItem) continue
                        val norm = normalizeEntryPath(entry.name.orEmpty())
                        if (norm.isEmpty()) continue
                        if (deleteTargets.isNotEmpty() && matchesSelection(norm, deleteTargets)) {
                            continue
                        }
                        val targetNorm = if (renameFrom != null && renameTo != null) {
                            rewriteRenamedPath(norm, renameFrom, renameTo)
                        } else {
                            norm
                        }
                        val outEntry = SevenZArchiveEntry().apply {
                            name = if (entry.isDirectory) "$targetNorm/" else targetNorm
                            isDirectory = entry.isDirectory
                            runCatching {
                                if (entry.hasLastModifiedDate) lastModifiedDate = entry.lastModifiedDate
                            }
                        }
                        output.putArchiveEntry(outEntry)
                        if (!entry.isDirectory) {
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = input.read(buf)
                                if (read <= 0) break
                                output.write(buf, 0, read)
                                processed += read
                                onProgress(
                                    ArchiveOperationProgress(
                                        title = "7z-Archiv wird aktualisiert…",
                                        currentItem = targetNorm.substringAfterLast('/'),
                                        processedBytes = processed,
                                        totalBytes = totalBytes,
                                    ),
                                )
                            }
                        }
                        output.closeArchiveEntry()
                    }

                    for (item in addItems) {
                        currentCoroutineContext().ensureActive()
                        val isDir = item.file.isDirectory
                        val entryName = if (isDir) "${item.archivePath.trimEnd('/')}/" else item.archivePath
                        val entry = output.createArchiveEntry(item.file, entryName)
                        entry.isDirectory = isDir
                        output.putArchiveEntry(entry)
                        if (!isDir) {
                            FileInputStream(item.file).use { fis ->
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val read = fis.read(buf)
                                    if (read == -1) break
                                    output.write(buf, 0, read)
                                    processed += read
                                    onProgress(
                                        ArchiveOperationProgress(
                                            title = "7z-Archiv wird aktualisiert…",
                                            currentItem = item.file.name,
                                            processedBytes = processed,
                                            totalBytes = totalBytes,
                                        ),
                                    )
                                }
                            }
                        }
                        output.closeArchiveEntry()
                    }
                    output.finish()
                }
            }

            replaceFileAtomically(tempOut, archiveFile)
        } catch (e: PasswordRequiredException) {
            throw ArchivePasswordException(
                message = if (password.isNullOrEmpty()) "Passwort für 7z-Archiv erforderlich." else "Falsches Passwort für 7z-Archiv.",
                isWrongPassword = !password.isNullOrEmpty(),
                cause = e,
            )
        } catch (e: IOException) {
            if (!password.isNullOrEmpty() && isSevenZDataPasswordFailure(e)) {
                throw ArchivePasswordException("Falsches Passwort für 7z-Archiv.", isWrongPassword = true, cause = e)
            }
            throw e
        } finally {
            if (tempOut.exists()) tempOut.delete()
        }
    }

    private fun isSevenZEntryEncrypted(entry: SevenZArchiveEntry): Boolean {
        return entry.contentMethods?.any { it.method == SevenZMethod.AES256SHA256 } == true
    }

    private fun isSevenZHeaderPasswordFailure(e: IOException, hasPassword: Boolean): Boolean {
        val msg = e.message.orEmpty().lowercase(Locale.ROOT)
        return msg.contains("password") ||
            msg.contains("no header") ||
            msg.contains("corrupt") ||
            (hasPassword && (msg.contains("pad") || msg.contains("crc") || msg.contains("lzma")))
    }

    private fun isSevenZDataPasswordFailure(e: IOException): Boolean {
        val msg = e.message.orEmpty().lowercase(Locale.ROOT)
        return msg.contains("password") ||
            msg.contains("corrupt") ||
            msg.contains("crc") ||
            msg.contains("pad") ||
            msg.contains("lzma") ||
            msg.contains("block")
    }

    // -------------------------------------------------------------------------
    // RAR Implementation
    // -------------------------------------------------------------------------

    private suspend fun <T> withRarTargetFile(
        group: ArchiveVolumeGroup,
        block: suspend (File) -> T,
    ): T {
        val isNumericSplit = group.isMultiVolume &&
            numericSplitRegex.matches(group.parts.first().name)
        if (!isNumericSplit) {
            return block(group.primaryFile)
        }
        val tempRar = File(System.getProperty("java.io.tmpdir"), "monolith_rar_${UUID.randomUUID()}.rar")
        try {
            val buf = ByteArray(BUFFER_SIZE)
            BufferedOutputStream(FileOutputStream(tempRar), BUFFER_SIZE).use { out ->
                for (part in group.parts) {
                    currentCoroutineContext().ensureActive()
                    FileInputStream(part).use { input ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buf)
                            if (read == -1) break
                            out.write(buf, 0, read)
                        }
                    }
                }
                out.flush()
            }
            return block(tempRar)
        } finally {
            tempRar.delete()
        }
    }

    private fun openRarArchive(targetFile: File, password: String?): Archive {
        val cleanPwd = password?.takeIf { it.isNotEmpty() }
        return try {
            Archive(FileVolumeManager(targetFile), null, cleanPwd)
        } catch (e: UnsupportedRarV5Exception) {
            throw IOException(
                "RAR5-Archive werden nicht unterstützt (nur RAR4-Archive, inkl. mehrteilig & verschlüsselt).",
                e,
            )
        } catch (e: InitDeciphererFailedException) {
            throw ArchivePasswordException(
                message = if (cleanPwd == null) "Passwort für verschlüsseltes RAR-Archiv erforderlich." else "Falsches Passwort für RAR-Archiv.",
                isWrongPassword = cleanPwd != null,
                cause = e,
            )
        } catch (e: RarException) {
            if (cleanPwd == null) {
                throw ArchivePasswordException("Passwort für RAR-Archiv erforderlich.", isWrongPassword = false, cause = e)
            }
            throw ArchivePasswordException("Falsches Passwort oder beschädigtes RAR-Archiv.", isWrongPassword = true, cause = e)
        } catch (e: NullPointerException) {
            throw ArchivePasswordException(
                message = if (cleanPwd == null) "Passwort für verschlüsseltes RAR-Archiv erforderlich." else "Falsches Passwort für RAR-Archiv.",
                isWrongPassword = cleanPwd != null,
                cause = e,
            )
        }
    }

    private suspend fun inspectRar(
        group: ArchiveVolumeGroup,
        password: String?,
    ): ArchiveSummary = withRarTargetFile(group) { target ->
        val rawItems = LinkedHashMap<String, ArchiveEntryItem>()
        var anyEncrypted = false

        val archive = try {
            openRarArchive(target, password)
        } catch (e: ArchivePasswordException) {
            if (!e.isWrongPassword && password.isNullOrEmpty()) {
                return@withRarTargetFile ArchiveSummary(
                    group = group,
                    entries = emptyList(),
                    isEncrypted = true,
                    requiresPasswordToList = true,
                    totalUncompressedBytes = 0L,
                    totalCompressedBytes = group.totalSizeBytes,
                    fileCount = 0,
                    folderCount = 0,
                )
            }
            throw e
        }

        archive.use { mainArc ->
            if (runCatching { mainArc.isEncrypted || mainArc.isPasswordProtected }.getOrDefault(false)) {
                anyEncrypted = true
            }
            collectRarHeaders(mainArc.fileHeaders, rawItems) { anyEncrypted = true }
        }

        // For multi-volume RAR (.part1.rar, .part2.rar / .r00), inspect remaining volumes for entries starting in later parts
        if (group.isMultiVolume && target.absolutePath == group.primaryFile.absolutePath) {
            for (part in group.parts.drop(1)) {
                currentCoroutineContext().ensureActive()
                runCatching {
                    openRarArchive(part, password).use { volArc ->
                        collectRarHeaders(volArc.fileHeaders, rawItems) { anyEncrypted = true }
                    }
                }
            }
        }

        buildArchiveSummary(
            group = group,
            rawItems = rawItems.values.toList(),
            isEncrypted = anyEncrypted,
            requiresPasswordToList = false,
        )
    }

    private fun collectRarHeaders(
        headers: List<RarFileHeader>,
        targetMap: MutableMap<String, ArchiveEntryItem>,
        onEncrypted: () -> Unit,
    ) {
        for (fh in headers) {
            if (fh.isSplitBefore) continue
            val normPath = normalizeEntryPath(fh.fileName.orEmpty())
            if (normPath.isEmpty()) continue
            if (fh.isEncrypted) onEncrypted()
            if (normPath in targetMap) continue
            val modTimeMs = fh.mTime?.time ?: 0L
            targetMap[normPath] = ArchiveEntryItem(
                path = normPath,
                name = normPath.substringAfterLast('/'),
                parentPath = normPath.substringBeforeLast('/', ""),
                isDirectory = fh.isDirectory,
                uncompressedSize = fh.fullUnpackSize.coerceAtLeast(0L),
                compressedSize = fh.fullPackSize.coerceAtLeast(0L),
                lastModifiedMs = modTimeMs,
                isEncrypted = fh.isEncrypted,
                crc32 = fh.fileCRC.toLong() and 0xFFFFFFFFL,
            )
        }
    }

    private suspend fun extractRar(
        group: ArchiveVolumeGroup,
        targetDir: File,
        password: String?,
        selectedPaths: Set<String>?,
        conflictPolicy: ExtractConflictPolicy,
        onProgress: (ArchiveOperationProgress) -> Unit,
    ) = withRarTargetFile(group) { target ->
        val archive = openRarArchive(target, password)
        archive.use { rar ->
            if (runCatching { rar.isPasswordProtected }.getOrDefault(false) && password.isNullOrEmpty()) {
                throw ArchivePasswordException(
                    "Dieses RAR-Archiv ist verschlüsselt. Bitte Passwort eingeben.",
                    isWrongPassword = false,
                )
            }
            val seenPaths = HashSet<String>()
            val totalEstimated = group.totalSizeBytes.coerceAtLeast(1L)
            var processedBytes = 0L

            while (true) {
                currentCoroutineContext().ensureActive()
                val header = rar.nextFileHeader()
                if (header == null) {
                    if (!advanceRarVolumeIfNeeded(rar, group)) break
                    continue
                }
                if (header.isSplitBefore) continue
                val norm = normalizeEntryPath(header.fileName.orEmpty())
                if (norm.isEmpty() || !seenPaths.add(norm)) continue
                if (!matchesSelection(norm, selectedPaths)) continue

                val safeTarget = resolveSafeExtractTarget(targetDir, norm)
                if (header.isDirectory) {
                    if (!safeTarget.exists() && !safeTarget.mkdirs()) {
                        throw IOException("Ordner konnte nicht erstellt werden: ${safeTarget.name}")
                    }
                    continue
                }
                if (header.isEncrypted && password.isNullOrEmpty()) {
                    throw ArchivePasswordException(
                        "Passwort für RAR-Archiv erforderlich.",
                        isWrongPassword = false,
                    )
                }
                val destFile = resolveExtractFileConflict(safeTarget, conflictPolicy) ?: run {
                    processedBytes += header.fullUnpackSize.coerceAtLeast(0L)
                    onProgress(
                        ArchiveOperationProgress(
                            title = "RAR wird entpackt…",
                            currentItem = safeTarget.name,
                            processedBytes = processedBytes.coerceAtMost(totalEstimated),
                            totalBytes = totalEstimated,
                        ),
                    )
                    continue
                }
                destFile.parentFile?.mkdirs()
                val tempOut = File(destFile.parentFile, ".monolith_ext_${UUID.randomUUID()}")
                try {
                    BufferedOutputStream(FileOutputStream(tempOut), BUFFER_SIZE).use { out ->
                        rar.extractFile(header, out)
                        out.flush()
                    }
                    if (destFile.exists() && !destFile.delete()) {
                        throw IOException("Vorhandene Datei konnte nicht ersetzt werden: ${destFile.name}")
                    }
                    if (!tempOut.renameTo(destFile)) {
                        throw IOException("Entpackte Datei konnte nicht gespeichert werden: ${destFile.name}")
                    }
                    header.mTime?.time?.takeIf { it > 0L }?.let { mod ->
                        runCatching { destFile.setLastModified(mod) }
                    }
                    processedBytes += destFile.length().coerceAtLeast(0L)
                    onProgress(
                        ArchiveOperationProgress(
                            title = "RAR wird entpackt…",
                            currentItem = destFile.name,
                            processedBytes = processedBytes,
                            totalBytes = maxOf(totalEstimated, processedBytes),
                        ),
                    )
                } catch (e: RarException) {
                    if (header.isEncrypted || !password.isNullOrEmpty()) {
                        throw ArchivePasswordException(
                            "Falsches Passwort oder beschädigtes RAR-Archiv.",
                            isWrongPassword = true,
                            cause = e,
                        )
                    }
                    throw IOException("RAR-Entpacken fehlgeschlagen (${destFile.name}): ${e.message}", e)
                } finally {
                    if (tempOut.exists()) tempOut.delete()
                }
            }
        }
    }

    private suspend fun verifyRar(
        group: ArchiveVolumeGroup,
        password: String?,
        onProgress: (ArchiveOperationProgress) -> Unit,
    ): Pair<Int, Long> = withRarTargetFile(group) { target ->
        val archive = openRarArchive(target, password)
        archive.use { rar ->
            if (runCatching { rar.isPasswordProtected }.getOrDefault(false) && password.isNullOrEmpty()) {
                throw ArchivePasswordException(
                    "Passwort zum Prüfen des verschlüsselten RAR-Archivs erforderlich.",
                    isWrongPassword = false,
                )
            }
            val seenPaths = HashSet<String>()
            var fileCount = 0
            var processedBytes = 0L
            val discardStream = object : OutputStream() {
                override fun write(b: Int) {
                    processedBytes++
                }

                override fun write(b: ByteArray, off: Int, len: Int) {
                    processedBytes += len
                }
            }

            while (true) {
                currentCoroutineContext().ensureActive()
                val header = rar.nextFileHeader()
                if (header == null) {
                    if (!advanceRarVolumeIfNeeded(rar, group)) break
                    continue
                }
                if (header.isSplitBefore || header.isDirectory) continue
                val norm = normalizeEntryPath(header.fileName.orEmpty())
                if (norm.isEmpty() || !seenPaths.add(norm)) continue
                if (header.isEncrypted && password.isNullOrEmpty()) {
                    throw ArchivePasswordException("Passwort für RAR-Archiv erforderlich.")
                }
                fileCount++
                try {
                    rar.extractFile(header, discardStream)
                    onProgress(
                        ArchiveOperationProgress(
                            title = "RAR-Integrität wird geprüft…",
                            currentItem = norm.substringAfterLast('/'),
                            processedBytes = processedBytes,
                            totalBytes = maxOf(group.totalSizeBytes, processedBytes),
                        ),
                    )
                } catch (e: RarException) {
                    if (header.isEncrypted || !password.isNullOrEmpty()) {
                        throw ArchivePasswordException(
                            "Falsches Passwort oder beschädigtes RAR-Archiv.",
                            isWrongPassword = true,
                            cause = e,
                        )
                    }
                    throw IOException("RAR-Prüfung fehlgeschlagen (${norm.substringAfterLast('/')}): ${e.message}", e)
                }
            }
            fileCount to processedBytes
        }
    }

    private fun advanceRarVolumeIfNeeded(rar: Archive, group: ArchiveVolumeGroup): Boolean {
        if (!group.isMultiVolume) return false
        val currentFile = (rar.volume as? FileVolume)?.file ?: return false
        val currentPath = runCatching { currentFile.canonicalPath }.getOrNull() ?: return false
        val idx = group.parts.indexOfFirst {
            runCatching { it.canonicalPath }.getOrNull() == currentPath
        }
        if (idx == -1 || idx >= group.parts.lastIndex) return false
        val nextVol = rar.volumeManager?.nextVolume(rar, rar.volume) ?: return false
        val nextFile = (nextVol as? FileVolume)?.file
        if (nextFile != null && !nextFile.exists()) return false
        rar.setVolume(nextVol)
        return true
    }

    // -------------------------------------------------------------------------
    // TAR / TAR.GZ / TAR.XZ / TAR.BZ2 Implementation
    // -------------------------------------------------------------------------

    private fun resolveTarCodec(group: ArchiveVolumeGroup): TarCodec {
        val lower = group.displayName.lowercase(Locale.ROOT)
        return when {
            lower.endsWith(".tar.xz") || lower.endsWith(".txz") -> TarCodec.XZ
            lower.endsWith(".tar.bz2") || lower.endsWith(".tbz2") -> TarCodec.BZIP2
            lower.endsWith(".tar.gz") || lower.endsWith(".tgz") -> TarCodec.GZIP
            lower.endsWith(".tar") -> TarCodec.PLAIN
            group.format == ArchiveFormat.TAR_XZ -> TarCodec.XZ
            else -> TarCodec.GZIP
        }
    }

    private fun openTarInputStream(group: ArchiveVolumeGroup): TarArchiveInputStream {
        val rawStream: InputStream = if (group.parts.size == 1) {
            BufferedInputStream(FileInputStream(group.primaryFile), BUFFER_SIZE)
        } else {
            BufferedInputStream(MultiFileInputStream(group.parts), BUFFER_SIZE)
        }
        return try {
            val decompressed: InputStream = when (resolveTarCodec(group)) {
                TarCodec.PLAIN -> rawStream
                TarCodec.GZIP -> GzipCompressorInputStream(rawStream, true)
                TarCodec.XZ -> XZCompressorInputStream(rawStream, true)
                TarCodec.BZIP2 -> BZip2CompressorInputStream(rawStream, true)
            }
            TarArchiveInputStream(decompressed)
        } catch (t: Throwable) {
            runCatching { rawStream.close() }
            throw t
        }
    }

    private fun openTarOutputStream(
        outFile: File,
        codec: TarCodec,
        compressionLevel: ArchiveCompressionLevel,
    ): TarArchiveOutputStream {
        val fileOut = BufferedOutputStream(FileOutputStream(outFile), BUFFER_SIZE)
        val compressedOut: OutputStream = try {
            when (codec) {
                TarCodec.PLAIN -> fileOut
                TarCodec.GZIP -> {
                    val gzParams = GzipParameters().apply {
                        this.compressionLevel = when (compressionLevel) {
                            ArchiveCompressionLevel.STORE -> Deflater.NO_COMPRESSION
                            ArchiveCompressionLevel.FAST -> Deflater.BEST_SPEED
                            ArchiveCompressionLevel.NORMAL -> 6
                            ArchiveCompressionLevel.MAXIMUM -> Deflater.BEST_COMPRESSION
                        }
                    }
                    GzipCompressorOutputStream(fileOut, gzParams)
                }
                TarCodec.XZ -> {
                    val preset = when (compressionLevel) {
                        ArchiveCompressionLevel.STORE -> 0
                        ArchiveCompressionLevel.FAST -> 1
                        ArchiveCompressionLevel.NORMAL -> 6
                        ArchiveCompressionLevel.MAXIMUM -> 7
                    }
                    XZCompressorOutputStream(fileOut, preset)
                }
                TarCodec.BZIP2 -> throw IllegalStateException("BZIP2 creation is not supported")
            }
        } catch (t: Throwable) {
            runCatching { fileOut.close() }
            throw t
        }
        return TarArchiveOutputStream(compressedOut).apply {
            setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
            setAddPaxHeadersForNonAsciiNames(true)
        }
    }

    private suspend fun inspectTar(group: ArchiveVolumeGroup): ArchiveSummary {
        val rawItems = ArrayList<ArchiveEntryItem>()
        openTarInputStream(group).use { tarIn ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val entry = tarIn.nextEntry ?: break
                val norm = normalizeEntryPath(entry.name.orEmpty())
                if (norm.isEmpty()) continue
                rawItems += ArchiveEntryItem(
                    path = norm,
                    name = norm.substringAfterLast('/'),
                    parentPath = norm.substringBeforeLast('/', ""),
                    isDirectory = entry.isDirectory,
                    uncompressedSize = entry.size.coerceAtLeast(0L),
                    compressedSize = 0L,
                    lastModifiedMs = entry.modTime?.time ?: 0L,
                    isEncrypted = false,
                    crc32 = null,
                )
            }
        }
        return buildArchiveSummary(
            group = group,
            rawItems = rawItems,
            isEncrypted = false,
            requiresPasswordToList = false,
        )
    }

    private suspend fun createTarArchive(
        items: List<SourceTreeItem>,
        totalBytes: Long,
        outputDir: File,
        finalArchiveFile: File,
        tempArchiveFile: File,
        format: ArchiveFormat,
        compressionLevel: ArchiveCompressionLevel,
        splitBytes: Long?,
        onProgress: (ArchiveOperationProgress) -> Unit,
    ): ArchiveVolumeGroup {
        val codec = if (format == ArchiveFormat.TAR_XZ) TarCodec.XZ else TarCodec.GZIP
        val buf = ByteArray(BUFFER_SIZE)
        var processedBytes = 0L

        try {
            openTarOutputStream(tempArchiveFile, codec, compressionLevel).use { tarOut ->
                for (item in items) {
                    currentCoroutineContext().ensureActive()
                    val isDir = item.file.isDirectory
                    val entryName = if (isDir) "${item.archivePath.trimEnd('/')}/" else item.archivePath
                    val entry = TarArchiveEntry(item.file, entryName)
                    tarOut.putArchiveEntry(entry)
                    if (!isDir) {
                        FileInputStream(item.file).use { input ->
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = input.read(buf)
                                if (read == -1) break
                                tarOut.write(buf, 0, read)
                                processedBytes += read
                                onProgress(
                                    ArchiveOperationProgress(
                                        title = "${format.label}-Archiv wird erstellt…",
                                        currentItem = item.file.name,
                                        processedBytes = processedBytes,
                                        totalBytes = totalBytes,
                                    ),
                                )
                            }
                        }
                    } else {
                        onProgress(
                            ArchiveOperationProgress(
                                title = "${format.label}-Archiv wird erstellt…",
                                currentItem = item.file.name,
                                processedBytes = processedBytes,
                                totalBytes = totalBytes,
                            ),
                        )
                    }
                    tarOut.closeArchiveEntry()
                }
                tarOut.finish()
            }

            if (splitBytes != null) {
                val firstPart = splitBinaryFile(
                    sourceFile = tempArchiveFile,
                    outputDir = outputDir,
                    baseArchiveName = finalArchiveFile.name,
                    splitVolumeBytes = splitBytes,
                    onProgress = onProgress,
                )
                tempArchiveFile.delete()
                return resolveVolumeGroup(firstPart)
            } else {
                if (finalArchiveFile.exists() && !finalArchiveFile.delete()) {
                    throw IOException("Zieldatei konnte nicht überschrieben werden: ${finalArchiveFile.name}")
                }
                if (!tempArchiveFile.renameTo(finalArchiveFile)) {
                    throw IOException("${format.label}-Archiv konnte nicht gespeichert werden: ${finalArchiveFile.name}")
                }
                return resolveVolumeGroup(finalArchiveFile)
            }
        } finally {
            if (tempArchiveFile.exists()) tempArchiveFile.delete()
        }
    }

    private suspend fun extractTar(
        group: ArchiveVolumeGroup,
        targetDir: File,
        selectedPaths: Set<String>?,
        conflictPolicy: ExtractConflictPolicy,
        onProgress: (ArchiveOperationProgress) -> Unit,
    ) {
        val buf = ByteArray(BUFFER_SIZE)
        val totalEstimated = group.totalSizeBytes.coerceAtLeast(1L)
        var processedBytes = 0L

        openTarInputStream(group).use { tarIn ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val entry = tarIn.nextEntry ?: break
                val norm = normalizeEntryPath(entry.name.orEmpty())
                if (norm.isEmpty() || !matchesSelection(norm, selectedPaths)) continue

                val safeTarget = resolveSafeExtractTarget(targetDir, norm)
                if (entry.isDirectory) {
                    if (!safeTarget.exists() && !safeTarget.mkdirs()) {
                        throw IOException("Ordner konnte nicht erstellt werden: ${safeTarget.name}")
                    }
                    continue
                }
                if (!tarIn.canReadEntryData(entry)) continue

                val destFile = resolveExtractFileConflict(safeTarget, conflictPolicy) ?: run {
                    processedBytes += entry.size.coerceAtLeast(0L)
                    onProgress(
                        ArchiveOperationProgress(
                            title = "${group.format.label} wird entpackt…",
                            currentItem = safeTarget.name,
                            processedBytes = processedBytes,
                            totalBytes = maxOf(totalEstimated, processedBytes),
                        ),
                    )
                    continue
                }
                destFile.parentFile?.mkdirs()
                val tempOut = File(destFile.parentFile, ".monolith_ext_${UUID.randomUUID()}")
                try {
                    BufferedOutputStream(FileOutputStream(tempOut), BUFFER_SIZE).use { out ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = tarIn.read(buf)
                            if (read == -1) break
                            out.write(buf, 0, read)
                            processedBytes += read
                            onProgress(
                                ArchiveOperationProgress(
                                    title = "${group.format.label} wird entpackt…",
                                    currentItem = destFile.name,
                                    processedBytes = processedBytes,
                                    totalBytes = maxOf(totalEstimated, processedBytes),
                                ),
                            )
                        }
                        out.flush()
                    }
                    if (destFile.exists() && !destFile.delete()) {
                        throw IOException("Vorhandene Datei konnte nicht ersetzt werden: ${destFile.name}")
                    }
                    if (!tempOut.renameTo(destFile)) {
                        throw IOException("Entpackte Datei konnte nicht gespeichert werden: ${destFile.name}")
                    }
                    entry.modTime?.time?.takeIf { it > 0L }?.let { mod ->
                        runCatching { destFile.setLastModified(mod) }
                    }
                } finally {
                    if (tempOut.exists()) tempOut.delete()
                }
            }
        }
    }

    private suspend fun verifyTar(
        group: ArchiveVolumeGroup,
        onProgress: (ArchiveOperationProgress) -> Unit,
    ): Pair<Int, Long> {
        var fileCount = 0
        var processed = 0L
        val buf = ByteArray(BUFFER_SIZE)
        openTarInputStream(group).use { tarIn ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val entry = tarIn.nextEntry ?: break
                if (entry.isDirectory) continue
                fileCount++
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = tarIn.read(buf)
                    if (read == -1) break
                    processed += read
                    onProgress(
                        ArchiveOperationProgress(
                            title = "${group.format.label}-Integrität wird geprüft…",
                            currentItem = entry.name.orEmpty().substringAfterLast('/'),
                            processedBytes = processed,
                            totalBytes = maxOf(group.totalSizeBytes, processed),
                        ),
                    )
                }
            }
        }
        return fileCount to processed
    }

    private suspend fun rebuildTarArchive(
        archiveFile: File,
        group: ArchiveVolumeGroup,
        pathsToAdd: List<String> = emptyList(),
        addPrefix: String = "",
        deleteTargets: Set<String> = emptySet(),
        renameFrom: String? = null,
        renameTo: String? = null,
        onProgress: (ArchiveOperationProgress) -> Unit = {},
    ) {
        val codec = resolveTarCodec(group)
        if (codec == TarCodec.BZIP2) {
            throw IllegalStateException("TAR.BZ2 unterstützt nur Entpacken und Prüfen.")
        }
        val parentDir = archiveFile.parentFile ?: throw IOException("Kein übergeordneter Ordner vorhanden.")
        val tempOut = File(parentDir, ".monolith_tar_${UUID.randomUUID()}.tmp")
        val buf = ByteArray(BUFFER_SIZE)

        try {
            if (renameFrom != null && renameTo != null) {
                openTarInputStream(group).use { checkIn ->
                    while (true) {
                        val e = checkIn.nextEntry ?: break
                        if (normalizeEntryPath(e.name.orEmpty()) == renameTo) {
                            throw IOException("Ein Eintrag mit dem Namen „${renameTo.substringAfterLast('/')}“ existiert bereits.")
                        }
                    }
                }
            }
            val addItems = if (pathsToAdd.isNotEmpty()) {
                collectSourceTreeItems(pathsToAdd, emptySet(), addPrefix)
            } else {
                emptyList()
            }
            val totalEstimated = (group.totalSizeBytes +
                addItems.sumOf { if (it.file.isFile) it.file.length().coerceAtLeast(0L) else 0L })
                .coerceAtLeast(1L)
            var processed = 0L

            openTarInputStream(group).use { tarIn ->
                openTarOutputStream(tempOut, codec, ArchiveCompressionLevel.NORMAL).use { tarOut ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val entry = tarIn.nextEntry ?: break
                        val norm = normalizeEntryPath(entry.name.orEmpty())
                        if (norm.isEmpty()) continue
                        if (deleteTargets.isNotEmpty() && matchesSelection(norm, deleteTargets)) {
                            continue
                        }
                        val targetNorm = if (renameFrom != null && renameTo != null) {
                            rewriteRenamedPath(norm, renameFrom, renameTo)
                        } else {
                            norm
                        }
                        val outEntryName = if (entry.isDirectory) "$targetNorm/" else targetNorm
                        val outEntry = TarArchiveEntry(outEntryName).apply {
                            mode = entry.mode
                            entry.modTime?.let { modTime = it }
                            if (!entry.isDirectory) {
                                size = entry.size
                            }
                        }
                        tarOut.putArchiveEntry(outEntry)
                        if (!entry.isDirectory) {
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = tarIn.read(buf)
                                if (read == -1) break
                                tarOut.write(buf, 0, read)
                                processed += read
                                onProgress(
                                    ArchiveOperationProgress(
                                        title = "${group.format.label}-Archiv wird aktualisiert…",
                                        currentItem = targetNorm.substringAfterLast('/'),
                                        processedBytes = processed,
                                        totalBytes = maxOf(totalEstimated, processed),
                                    ),
                                )
                            }
                        }
                        tarOut.closeArchiveEntry()
                    }

                    for (item in addItems) {
                        currentCoroutineContext().ensureActive()
                        val isDir = item.file.isDirectory
                        val entryName = if (isDir) "${item.archivePath.trimEnd('/')}/" else item.archivePath
                        val entry = TarArchiveEntry(item.file, entryName)
                        tarOut.putArchiveEntry(entry)
                        if (!isDir) {
                            FileInputStream(item.file).use { fis ->
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val read = fis.read(buf)
                                    if (read == -1) break
                                    tarOut.write(buf, 0, read)
                                    processed += read
                                    onProgress(
                                        ArchiveOperationProgress(
                                            title = "${group.format.label}-Archiv wird aktualisiert…",
                                            currentItem = item.file.name,
                                            processedBytes = processed,
                                            totalBytes = maxOf(totalEstimated, processed),
                                        ),
                                    )
                                }
                            }
                        }
                        tarOut.closeArchiveEntry()
                    }
                    tarOut.finish()
                }
            }

            replaceFileAtomically(tempOut, archiveFile)
        } finally {
            if (tempOut.exists()) tempOut.delete()
        }
    }

    // -------------------------------------------------------------------------
    // Shared Helpers
    // -------------------------------------------------------------------------

    private suspend fun splitBinaryFile(
        sourceFile: File,
        outputDir: File,
        baseArchiveName: String,
        splitVolumeBytes: Long,
        onProgress: (ArchiveOperationProgress) -> Unit,
    ): File {
        val totalSize = sourceFile.length().coerceAtLeast(1L)
        val estimatedParts = ((totalSize + splitVolumeBytes - 1L) / splitVolumeBytes).toInt().coerceAtLeast(1)
        val padWidth = if (estimatedParts > 999) 4 else 3

        val createdParts = ArrayList<File>()
        val buf = ByteArray(BUFFER_SIZE)
        var totalProcessed = 0L

        try {
            BufferedInputStream(FileInputStream(sourceFile), BUFFER_SIZE).use { input ->
                var partIndex = 1
                var remainingInStream = true
                while (remainingInStream) {
                    currentCoroutineContext().ensureActive()
                    val suffix = partIndex.toString().padStart(padWidth, '0')
                    val partFile = File(outputDir, "$baseArchiveName.$suffix")
                    var bytesInPart = 0L
                    var wroteAny = false

                    BufferedOutputStream(FileOutputStream(partFile), BUFFER_SIZE).use { out ->
                        while (bytesInPart < splitVolumeBytes) {
                            currentCoroutineContext().ensureActive()
                            val toRead = minOf(buf.size.toLong(), splitVolumeBytes - bytesInPart).toInt()
                            val read = input.read(buf, 0, toRead)
                            if (read == -1) {
                                remainingInStream = false
                                break
                            }
                            out.write(buf, 0, read)
                            bytesInPart += read
                            totalProcessed += read
                            wroteAny = true
                            onProgress(
                                ArchiveOperationProgress(
                                    title = "Teile werden aufgeteilt…",
                                    currentItem = partFile.name,
                                    processedBytes = totalProcessed,
                                    totalBytes = totalSize,
                                ),
                            )
                        }
                        out.flush()
                    }
                    if (wroteAny || createdParts.isEmpty()) {
                        createdParts += partFile
                        partIndex++
                    } else {
                        partFile.delete()
                    }
                }
            }
            return createdParts.first()
        } catch (t: Throwable) {
            createdParts.forEach { it.delete() }
            throw t
        }
    }

    private fun collectSourceTreeItems(
        sourcePaths: List<String>,
        excludedCanonicalPaths: Set<String>,
        internalPrefix: String = "",
    ): List<SourceTreeItem> {
        val result = ArrayList<SourceTreeItem>()
        val visitedDirs = HashSet<String>()
        val usedRootNames = HashSet<String>()

        for (path in sourcePaths) {
            val root = File(path)
            if (!root.exists()) continue
            val rootCanonical = runCatching { root.canonicalPath }.getOrDefault(root.absolutePath)
            if (rootCanonical in excludedCanonicalPaths) continue

            val uniqueRootName = makeUniqueSegment(root.name.ifBlank { "item" }, usedRootNames)
            val rootRel = if (internalPrefix.isEmpty()) uniqueRootName else "$internalPrefix/$uniqueRootName"

            fun visit(file: File, relPath: String) {
                val canonical = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
                if (canonical in excludedCanonicalPaths) return
                if (file.name.startsWith(".monolith_")) return

                if (file.isDirectory) {
                    if (!visitedDirs.add(canonical)) return
                    result += SourceTreeItem(file = file, archivePath = relPath)
                    file.listFiles()?.sortedBy { it.name.lowercase(Locale.ROOT) }?.forEach { child ->
                        visit(child, "$relPath/${child.name}")
                    }
                } else if (file.isFile) {
                    result += SourceTreeItem(file = file, archivePath = relPath)
                }
            }

            visit(root, rootRel)
        }
        return result
    }

    private fun makeUniqueSegment(desired: String, used: MutableSet<String>): String {
        if (used.add(desired)) return desired
        var idx = 1
        while (true) {
            val dot = desired.lastIndexOf('.')
            val candidate = if (dot > 0) {
                "${desired.substring(0, dot)} ($idx)${desired.substring(dot)}"
            } else {
                "$desired ($idx)"
            }
            if (used.add(candidate)) return candidate
            idx++
        }
    }

    private fun buildArchiveSummary(
        group: ArchiveVolumeGroup,
        rawItems: List<ArchiveEntryItem>,
        isEncrypted: Boolean,
        requiresPasswordToList: Boolean,
    ): ArchiveSummary {
        val byPath = LinkedHashMap<String, ArchiveEntryItem>()
        for (item in rawItems) {
            val existing = byPath[item.path]
            if (existing == null || (!item.isDirectory && existing.isDirectory)) {
                byPath[item.path] = item
            }
        }
        for (item in rawItems) {
            var currentParent = item.parentPath
            while (currentParent.isNotEmpty()) {
                if (currentParent !in byPath) {
                    byPath[currentParent] = ArchiveEntryItem(
                        path = currentParent,
                        name = currentParent.substringAfterLast('/'),
                        parentPath = currentParent.substringBeforeLast('/', ""),
                        isDirectory = true,
                        uncompressedSize = 0L,
                        compressedSize = 0L,
                        lastModifiedMs = item.lastModifiedMs,
                        isEncrypted = false,
                        crc32 = null,
                    )
                }
                currentParent = currentParent.substringBeforeLast('/', "")
            }
        }

        val sorted = byPath.values.sortedWith(
            compareByDescending<ArchiveEntryItem> { it.isDirectory }
                .thenBy { it.path.lowercase(Locale.ROOT) },
        )
        val fileEntries = sorted.filter { !it.isDirectory }
        val totalUncompressed = fileEntries.sumOf { it.uncompressedSize }
        val sumCompressed = fileEntries.sumOf { it.compressedSize }
        val totalCompressed = if (sumCompressed > 0L) sumCompressed else group.totalSizeBytes

        return ArchiveSummary(
            group = group,
            entries = sorted,
            isEncrypted = isEncrypted,
            requiresPasswordToList = requiresPasswordToList,
            totalUncompressedBytes = totalUncompressed,
            totalCompressedBytes = totalCompressed,
            fileCount = fileEntries.size,
            folderCount = sorted.count { it.isDirectory },
        )
    }

    private fun normalizeEntryPath(rawPath: String): String {
        val segments = rawPath
            .replace('\\', '/')
            .trim()
            .split('/')
            .filter { it.isNotEmpty() && it != "." }
        if (segments.isEmpty()) return ""
        return segments.joinToString("/")
    }

    private fun matchesSelection(normalizedPath: String, selectedPaths: Set<String>?): Boolean {
        if (selectedPaths == null) return true
        if (normalizedPath in selectedPaths) return true
        return selectedPaths.any { selected ->
            normalizedPath.startsWith("$selected/")
        }
    }

    private fun rewriteRenamedPath(currentNorm: String, oldNorm: String, newNorm: String): String {
        return when {
            currentNorm == oldNorm -> newNorm
            currentNorm.startsWith("$oldNorm/") -> "$newNorm/${currentNorm.removePrefix("$oldNorm/")}"
            else -> currentNorm
        }
    }

    private fun resolveSafeExtractTarget(targetRoot: File, entryPath: String): File {
        val rootCanonical = targetRoot.canonicalFile.path
        val cleaned = entryPath
            .replace('\\', '/')
            .split('/')
            .filter { it.isNotEmpty() && it != "." }
        if (cleaned.any { it == ".." }) {
            throw SecurityException("Unsicherer Archivpfad blockiert: $entryPath")
        }
        val relative = cleaned.joinToString(File.separator)
        val outFile = if (relative.isEmpty()) targetRoot.canonicalFile else File(targetRoot, relative)
        val outCanonical = outFile.canonicalPath
        if (outCanonical != rootCanonical && !outCanonical.startsWith(rootCanonical + File.separator)) {
            throw SecurityException("Unsicherer Archivpfad blockiert: $entryPath")
        }
        return outFile
    }

    private fun resolveExtractFileConflict(
        desiredTarget: File,
        conflictPolicy: ExtractConflictPolicy,
    ): File? {
        if (!desiredTarget.exists()) return desiredTarget
        return when (conflictPolicy) {
            ExtractConflictPolicy.SKIP -> null
            ExtractConflictPolicy.OVERWRITE -> desiredTarget
            ExtractConflictPolicy.RENAME -> resolveUniqueFile(desiredTarget)
        }
    }

    private fun resolveUniqueFile(desired: File): File {
        if (!desired.exists()) return desired
        val parent = desired.parentFile ?: File(".")
        val name = desired.name
        val dot = name.lastIndexOf('.')
        val (base, ext) = if (dot > 0) name.substring(0, dot) to name.substring(dot) else name to ""
        var idx = 1
        while (true) {
            val candidate = File(parent, "$base ($idx)$ext")
            if (!candidate.exists()) return candidate
            idx++
        }
    }

    private fun resolveUniqueArchiveBaseName(
        outputDir: File,
        baseName: String,
        format: ArchiveFormat,
        isSplit: Boolean,
    ): String {
        fun conflicts(candidateBase: String): Boolean {
            if (File(outputDir, "$candidateBase.${format.extension}").exists()) return true
            if (isSplit) {
                if (format == ArchiveFormat.ZIP && File(outputDir, "$candidateBase.z01").exists()) return true
                if (File(outputDir, "$candidateBase.${format.extension}.001").exists()) return true
            }
            return false
        }
        if (!conflicts(baseName)) return baseName
        var idx = 1
        while (true) {
            val candidate = "$baseName ($idx)"
            if (!conflicts(candidate)) return candidate
            idx++
        }
    }

    private fun sanitizeBaseName(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed == "." || trimmed == ".." ||
            trimmed.contains('/') || trimmed.contains('\\')
        ) {
            return null
        }
        return stripArchiveExtensions(trimmed).ifBlank { trimmed }
    }

    private fun stripArchiveExtensions(fileName: String, stripFallbackDot: Boolean = false): String {
        val lower = fileName.lowercase(Locale.ROOT)
        val knownSuffixes = listOf(
            ".tar.gz", ".tar.xz", ".tar.bz2", ".tgz", ".txz", ".tbz2",
            ".zip", ".7z", ".rar", ".tar", ".jar",
        )
        for (suffix in knownSuffixes) {
            if (lower.endsWith(suffix) && fileName.length > suffix.length) {
                return fileName.dropLast(suffix.length)
            }
        }
        return if (stripFallbackDot) fileName.substringBeforeLast('.', fileName) else fileName
    }

    private fun replaceFileAtomically(tempFile: File, targetFile: File) {
        val backup = File(targetFile.parentFile, ".monolith_bak_${UUID.randomUUID()}")
        val hadOriginal = targetFile.exists()
        if (hadOriginal && !targetFile.renameTo(backup)) {
            throw IOException("Bestehendes Archiv konnte nicht aktualisiert werden.")
        }
        if (!tempFile.renameTo(targetFile)) {
            if (hadOriginal) backup.renameTo(targetFile)
            throw IOException("Aktualisiertes Archiv konnte nicht gespeichert werden.")
        }
        if (hadOriginal && backup.exists()) {
            backup.delete()
        }
    }

    private fun hasDirectArchiveExtension(lowerName: String): Boolean {
        return lowerName.endsWith(".zip") ||
            lowerName.endsWith(".jar") ||
            lowerName.endsWith(".7z") ||
            lowerName.endsWith(".rar") ||
            lowerName.endsWith(".tar.gz") ||
            lowerName.endsWith(".tgz") ||
            lowerName.endsWith(".tar.xz") ||
            lowerName.endsWith(".txz") ||
            lowerName.endsWith(".tar.bz2") ||
            lowerName.endsWith(".tbz2") ||
            lowerName.endsWith(".tar")
    }

    private fun detectFormatByName(fileName: String): ArchiveFormat? {
        val lower = fileName.lowercase(Locale.ROOT)
        return when {
            lower.endsWith(".zip") || lower.endsWith(".jar") -> ArchiveFormat.ZIP
            lower.endsWith(".7z") -> ArchiveFormat.SEVEN_Z
            lower.endsWith(".rar") -> ArchiveFormat.RAR
            lower.endsWith(".tar.xz") || lower.endsWith(".txz") -> ArchiveFormat.TAR_XZ
            lower.endsWith(".tar.gz") || lower.endsWith(".tgz") ||
                lower.endsWith(".tar.bz2") || lower.endsWith(".tbz2") ||
                lower.endsWith(".tar") -> ArchiveFormat.TAR_GZ
            else -> null
        }
    }

    private fun detectFormatFromMagic(file: File): ArchiveFormat? {
        if (!file.isFile || file.length() < 4L) return null
        return runCatching {
            val header = ByteArray(6)
            val read = FileInputStream(file).use { it.read(header) }
            if (read < 2) return@runCatching null
            when {
                read >= 6 && header[0] == 0x37.toByte() && header[1] == 0x7A.toByte() &&
                    header[2] == 0xBC.toByte() && header[3] == 0xAF.toByte() &&
                    header[4] == 0x27.toByte() && header[5] == 0x1C.toByte() -> ArchiveFormat.SEVEN_Z
                read >= 4 && header[0] == 0x52.toByte() && header[1] == 0x61.toByte() &&
                    header[2] == 0x72.toByte() && header[3] == 0x21.toByte() -> ArchiveFormat.RAR
                read >= 6 && header[0] == 0xFD.toByte() && header[1] == 0x37.toByte() &&
                    header[2] == 0x7A.toByte() && header[3] == 0x58.toByte() &&
                    header[4] == 0x5A.toByte() && header[5] == 0x00.toByte() -> ArchiveFormat.TAR_XZ
                header[0] == 0x50.toByte() && header[1] == 0x4B.toByte() -> ArchiveFormat.ZIP
                header[0] == 0x1F.toByte() && header[1] == 0x8B.toByte() -> ArchiveFormat.TAR_GZ
                else -> null
            }
        }.getOrNull()
    }

    private fun hasSequenceGaps(sortedIndices: List<Int>, startFrom: Int): Boolean {
        if (sortedIndices.isEmpty()) return true
        var expected = startFrom
        for (idx in sortedIndices) {
            if (idx != expected) return true
            expected++
        }
        return false
    }

    private class MultiFileInputStream(
        private val files: List<File>,
    ) : InputStream() {
        private var currentIndex = 0
        private var currentStream: InputStream? = if (files.isNotEmpty()) FileInputStream(files[0]) else null

        override fun read(): Int {
            while (true) {
                val stream = currentStream ?: return -1
                val byteRead = stream.read()
                if (byteRead != -1) return byteRead
                advanceStream()
            }
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (true) {
                val stream = currentStream ?: return -1
                val read = stream.read(b, off, len)
                if (read > 0) return read
                advanceStream()
            }
        }

        private fun advanceStream() {
            currentStream?.close()
            currentIndex++
            currentStream = if (currentIndex < files.size) {
                FileInputStream(files[currentIndex])
            } else {
                null
            }
        }

        override fun close() {
            currentStream?.close()
            currentStream = null
        }
    }
}
