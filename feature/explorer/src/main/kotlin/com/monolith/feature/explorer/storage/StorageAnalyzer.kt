package com.monolith.feature.explorer.storage

import android.os.StatFs
import com.monolith.core.common.MimeNormalizer
import com.monolith.core.model.FileCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.EnumMap
import java.util.IdentityHashMap
import java.util.Locale
import java.util.PriorityQueue

internal data class DirNode(
    val path: String,
    val relativePath: String,
    val name: String,
    var ownFilesBytes: Long = 0L,
    var totalBytes: Long = 0L,
    var ownFilesCount: Int = 0,
    var totalFileCount: Int = 0,
    var totalSubfolderCount: Int = 0,
    val children: MutableList<DirNode> = mutableListOf(),
    val extensionBytes: MutableMap<String, Long> = mutableMapOf(),
)

/**
 * Pure-Kotlin storage scanner and folder antichain analyzer.
 * Uses only JVM/stdlib + [MimeNormalizer] (and `runCatching`-guarded `StatFs`)
 * so both Android runtime and JVM unit tests execute reliably.
 */
object StorageAnalyzer {

    private const val LARGE_FILE_THRESHOLD_BYTES = 100L * 1024L * 1024L
    private const val DUPLICATE_MIN_SIZE_BYTES = 512L * 1024L
    private const val PARTIAL_HASH_WINDOW_BYTES = 4096
    private const val PROGRESS_FILE_INTERVAL = 100
    private const val PROGRESS_TIME_INTERVAL_NS = 75_000_000L

    private val TEMP_EXTENSIONS = setOf("tmp", "temp", "bak", "log", "cache")
    private val TEMP_FOLDER_NAMES = setOf("cache", "tmp", "temp", ".cache", "code_cache")

    private data class ScanStackItem(
        val dir: File,
        val node: DirNode,
        val inTempOrCacheDir: Boolean,
    )

    suspend fun scanStorage(
        rootPath: String,
        volumeLabel: String,
        maxResults: Int = 60,
        onProgress: (scannedFiles: Int, scannedBytes: Long, currentDir: String) -> Unit = { _, _, _ -> },
    ): StorageScanResult = withContext(Dispatchers.IO) {
        val rootFile = File(rootPath)
        val normalizedRootPath = rootFile.absolutePath
        val statResult = readStatFsSafely(normalizedRootPath)
        val volumeStats = StorageVolumeStats(
            rootPath = normalizedRootPath,
            label = volumeLabel,
            totalCapacityBytes = statResult?.first ?: 0L,
            usedCapacityBytes = statResult?.second ?: 0L,
            freeCapacityBytes = statResult?.third ?: 0L,
        )

        if (!rootFile.exists() || !rootFile.isDirectory) {
            return@withContext emptyResult(normalizedRootPath, volumeStats)
        }

        val effectiveMaxResults = maxResults.coerceAtLeast(0)
        val rootNode = DirNode(
            path = normalizedRootPath,
            relativePath = "",
            name = rootFile.name.ifBlank { volumeLabel },
        )

        val visitedCanonicalDirs = HashSet<String>()
        visitedCanonicalDirs.add(canonicalPathOf(rootFile))

        val stack = ArrayDeque<ScanStackItem>()
        stack.addLast(
            ScanStackItem(
                dir = rootFile,
                node = rootNode,
                inTempOrCacheDir = isTempOrCacheFolderName(rootFile.name),
            ),
        )
        val postOrderNodes = ArrayList<DirNode>(256)

        var scannedBytes = 0L
        var totalFilesCount = 0
        var totalFoldersCount = 0
        val categoryBytes = EnumMap<FileCategory, Long>(FileCategory::class.java)
        val categoryCounts = EnumMap<FileCategory, Int>(FileCategory::class.java)
        var largeFilesOver100MbBytes = 0L
        var largeFilesOver100MbCount = 0
        var archiveAndApkBytes = 0L
        var archiveAndApkCount = 0
        var tempAndCacheBytes = 0L
        var tempAndCacheCount = 0

        val largestHeap = if (effectiveMaxResults > 0) {
            PriorityQueue(
                effectiveMaxResults + 1,
                compareBy<AnalyzedFileItem> { it.sizeBytes }.thenBy { it.path },
            )
        } else {
            null
        }
        val duplicateCandidatesBySize = HashMap<Long, MutableList<AnalyzedFileItem>>()

        var lastProgressFiles = 0
        var lastProgressNs = System.nanoTime()
        onProgress(0, 0L, normalizedRootPath)

        while (stack.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val item = stack.removeLast()
            postOrderNodes.add(item.node)

            val entries = runCatching { item.dir.listFiles() }.getOrNull() ?: continue
            for (entry in entries) {
                currentCoroutineContext().ensureActive()
                val isDir = runCatching { entry.isDirectory }.getOrDefault(false)
                if (isDir) {
                    val canonical = canonicalPathOf(entry)
                    if (!visitedCanonicalDirs.add(canonical)) continue
                    totalFoldersCount++
                    val rel = buildRelativePath(item.node.relativePath, entry.name)
                    val childNode = DirNode(
                        path = entry.absolutePath,
                        relativePath = rel,
                        name = entry.name,
                    )
                    item.node.children.add(childNode)
                    val childInTemp = item.inTempOrCacheDir || isTempOrCacheFolderName(entry.name)
                    stack.addLast(ScanStackItem(entry, childNode, childInTemp))
                } else if (runCatching { entry.isFile }.getOrDefault(false)) {
                    val size = runCatching { entry.length() }.getOrDefault(0L).coerceAtLeast(0L)
                    val lastMod = runCatching { entry.lastModified() }.getOrDefault(0L).coerceAtLeast(0L)
                    val name = entry.name
                    val ext = MimeNormalizer.extensionOf(name)
                    val mime = MimeNormalizer.normalizeMime(name)
                    val category = MimeNormalizer.categorize(name, isDirectory = false, mimeType = mime)
                    val rel = buildRelativePath(item.node.relativePath, name)

                    totalFilesCount++
                    scannedBytes += size
                    item.node.ownFilesBytes += size
                    item.node.ownFilesCount++
                    if (ext.isNotEmpty() && size > 0L) {
                        item.node.extensionBytes[ext] = (item.node.extensionBytes[ext] ?: 0L) + size
                    }

                    categoryBytes[category] = (categoryBytes[category] ?: 0L) + size
                    categoryCounts[category] = (categoryCounts[category] ?: 0) + 1

                    if (size > LARGE_FILE_THRESHOLD_BYTES) {
                        largeFilesOver100MbBytes += size
                        largeFilesOver100MbCount++
                    }
                    if (category == FileCategory.ARCHIVE || category == FileCategory.APK) {
                        archiveAndApkBytes += size
                        archiveAndApkCount++
                    }
                    if (item.inTempOrCacheDir || ext in TEMP_EXTENSIONS) {
                        tempAndCacheBytes += size
                        tempAndCacheCount++
                    }

                    val fileItem = AnalyzedFileItem(
                        path = entry.absolutePath,
                        relativePath = rel,
                        name = name,
                        sizeBytes = size,
                        lastModifiedMs = lastMod,
                        category = category,
                        mimeType = mime,
                        isDuplicateCandidate = false,
                    )

                    if (largestHeap != null) {
                        if (largestHeap.size < effectiveMaxResults) {
                            largestHeap.offer(fileItem)
                        } else {
                            val smallest = largestHeap.peek()
                            if (smallest != null && fileItem.sizeBytes > smallest.sizeBytes) {
                                largestHeap.poll()
                                largestHeap.offer(fileItem)
                            }
                        }
                    }

                    if (size >= DUPLICATE_MIN_SIZE_BYTES) {
                        duplicateCandidatesBySize.getOrPut(size) { mutableListOf() }.add(fileItem)
                    }

                    if (totalFilesCount - lastProgressFiles >= PROGRESS_FILE_INTERVAL) {
                        val nowNs = System.nanoTime()
                        if (nowNs - lastProgressNs >= PROGRESS_TIME_INTERVAL_NS) {
                            lastProgressFiles = totalFilesCount
                            lastProgressNs = nowNs
                            onProgress(totalFilesCount, scannedBytes, item.dir.absolutePath)
                        }
                    }
                }
            }
        }

        // Bottom-up folder aggregation (postOrderNodes lists every parent strictly before its descendants).
        for (i in postOrderNodes.indices.reversed()) {
            val node = postOrderNodes[i]
            var sumBytes = node.ownFilesBytes
            var sumFiles = node.ownFilesCount
            var sumSubfolders = node.children.size
            for (child in node.children) {
                sumBytes += child.totalBytes
                sumFiles += child.totalFileCount
                sumSubfolders += child.totalSubfolderCount
                for ((ext, extBytes) in child.extensionBytes) {
                    node.extensionBytes[ext] = (node.extensionBytes[ext] ?: 0L) + extBytes
                }
            }
            node.totalBytes = sumBytes
            node.totalFileCount = sumFiles
            node.totalSubfolderCount = sumSubfolders
            if (node.extensionBytes.size > 24) {
                val top = node.extensionBytes.entries
                    .sortedByDescending { it.value }
                    .take(16)
                node.extensionBytes.clear()
                for ((k, v) in top) {
                    node.extensionBytes[k] = v
                }
            }
        }

        // Duplicate detection via size collisions + partial SHA-256 (first 4 KB + last 4 KB + size).
        val duplicateGroups = ArrayList<DuplicateGroup>()
        val duplicatePaths = HashSet<String>()
        for ((sizeBytes, candidates) in duplicateCandidatesBySize) {
            if (candidates.size < 2) continue
            currentCoroutineContext().ensureActive()
            val byFingerprint = HashMap<String, MutableList<AnalyzedFileItem>>()
            for (candidate in candidates) {
                currentCoroutineContext().ensureActive()
                val fp = computePartialSha256(File(candidate.path), sizeBytes) ?: continue
                byFingerprint.getOrPut(fp) { mutableListOf() }.add(candidate)
            }
            for ((fp, matching) in byFingerprint) {
                if (matching.size < 2) continue
                val marked = matching
                    .map { it.copy(isDuplicateCandidate = true) }
                    .sortedWith(
                        compareByDescending<AnalyzedFileItem> { it.lastModifiedMs }
                            .thenBy { it.relativePath },
                    )
                marked.forEach { duplicatePaths.add(it.path) }
                val repName = marked.minByOrNull { it.name.length }?.name ?: marked.first().name
                duplicateGroups.add(
                    DuplicateGroup(
                        key = "$sizeBytes:$fp",
                        fileName = repName,
                        eachSizeBytes = sizeBytes,
                        files = marked,
                    ),
                )
            }
        }
        duplicateGroups.sortWith(
            compareByDescending<DuplicateGroup> { it.reclaimableBytes }
                .thenBy { it.fileName },
        )
        val reclaimableDuplicatesBytes = duplicateGroups.sumOf { it.reclaimableBytes }

        val categoryBreakdowns = categoryBytes.entries
            .filter { it.value > 0L || (categoryCounts[it.key] ?: 0) > 0 }
            .sortedByDescending { it.value }
            .map { (cat, bytes) ->
                CategoryBreakdown(
                    category = cat,
                    label = categoryLabel(cat),
                    totalBytes = bytes,
                    fileCount = categoryCounts[cat] ?: 0,
                    fractionOfScanned = if (scannedBytes > 0L) {
                        (bytes.toDouble() / scannedBytes.toDouble()).toFloat().coerceIn(0f, 1f)
                    } else {
                        0f
                    },
                )
            }

        val folderLimit = if (effectiveMaxResults == 0) 0 else minOf(35, effectiveMaxResults)
        val disjointTopFolders = computeDisjointTopFolders(rootNode, maxCount = folderLimit)

        val largestFiles = largestHeap
            ?.toList()
            ?.map { item ->
                if (item.path in duplicatePaths) item.copy(isDuplicateCandidate = true) else item
            }
            ?.sortedWith(
                compareByDescending<AnalyzedFileItem> { it.sizeBytes }
                    .thenBy { it.relativePath },
            )
            .orEmpty()

        onProgress(totalFilesCount, scannedBytes, normalizedRootPath)

        StorageScanResult(
            rootPath = normalizedRootPath,
            volumeStats = volumeStats,
            scannedBytes = scannedBytes,
            totalFilesCount = totalFilesCount,
            totalFoldersCount = totalFoldersCount,
            categoryBreakdowns = categoryBreakdowns,
            disjointTopFolders = disjointTopFolders,
            largestFiles = largestFiles,
            duplicateGroups = duplicateGroups,
            largeFilesOver100MbBytes = largeFilesOver100MbBytes,
            largeFilesOver100MbCount = largeFilesOver100MbCount,
            archiveAndApkBytes = archiveAndApkBytes,
            archiveAndApkCount = archiveAndApkCount,
            tempAndCacheBytes = tempAndCacheBytes,
            tempAndCacheCount = tempAndCacheCount,
            reclaimableDuplicatesBytes = reclaimableDuplicatesBytes,
        )
    }

    /**
     * Computes a strict directory antichain of the largest disjoint folders below [root],
     * unwrapping pass-through wrapper directories (e.g. `Android -> media -> com.whatsapp`)
     * and large multi-branch containers (e.g. `DCIM -> Camera + Screenshots`) without ever
     * returning both an ancestor and a descendant in the final list.
     */
    internal fun computeDisjointTopFolders(
        root: DirNode,
        maxCount: Int = 35,
    ): List<AnalyzedFolderItem> {
        if (maxCount <= 0) return emptyList()
        val effectiveRootBytes = if (root.totalBytes > 0L) {
            root.totalBytes
        } else {
            root.ownFilesBytes + root.children.sumOf { it.totalBytes }
        }
        if (effectiveRootBytes <= 0L) return emptyList()

        val frontier = root.children
            .filter { it.totalBytes > 0L }
            .toMutableList()
        if (frontier.isEmpty()) return emptyList()

        // Use IdentityHashMap so data-class hashCode/equals never walks subtrees.
        val settled = IdentityHashMap<DirNode, Boolean>()
        val maxFrontierSize = (maxCount * 3).coerceAtLeast(12)

        while (frontier.size < maxFrontierSize) {
            var candidateIdx = -1
            var candidateBytes = -1L
            for (idx in frontier.indices) {
                val node = frontier[idx]
                if (!settled.containsKey(node) && node.totalBytes > candidateBytes) {
                    candidateBytes = node.totalBytes
                    candidateIdx = idx
                }
            }
            if (candidateIdx < 0) break

            val candidate = frontier[candidateIdx]
            val nonEmptyChildren = candidate.children.filter { it.totalBytes > 0L }
            if (nonEmptyChildren.isEmpty()) {
                settled[candidate] = true
                continue
            }

            val subdirsBytes = nonEmptyChildren.sumOf { it.totalBytes }
            val maxChildBytes = nonEmptyChildren.maxOfOrNull { it.totalBytes } ?: 0L
            val dominantWrapper =
                maxChildBytes >= (candidate.totalBytes * 0.65).toLong() &&
                    candidate.ownFilesBytes <= (candidate.totalBytes * 0.35).toLong()
            val multiBranchContainer =
                subdirsBytes >= (candidate.totalBytes * 0.65).toLong() &&
                    maxChildBytes >= maxOf(effectiveRootBytes / 50L, 15L * 1024L * 1024L) &&
                    nonEmptyChildren.count { it.totalBytes >= 5L * 1024L * 1024L } <= 10

            if (dominantWrapper || multiBranchContainer) {
                frontier.removeAt(candidateIdx)
                frontier.addAll(nonEmptyChildren)
            } else {
                settled[candidate] = true
            }
        }

        val sortedItems = frontier
            .sortedWith(
                compareByDescending<DirNode> { it.totalBytes }
                    .thenBy { it.relativePath }
                    .thenBy { it.path },
            )
            .map { node ->
                val rel = node.relativePath.ifBlank {
                    node.path.removePrefix(root.path).trimStart('/', '\\').ifBlank { node.name }
                }
                val topExtensions = node.extensionBytes.entries
                    .sortedByDescending { it.value }
                    .map { it.key }
                    .filter { it.isNotBlank() }
                    .take(3)
                AnalyzedFolderItem(
                    path = node.path,
                    relativePath = rel,
                    name = node.name,
                    totalBytes = node.totalBytes,
                    ownFilesBytes = node.ownFilesBytes,
                    fileCount = maxOf(node.totalFileCount, node.ownFilesCount),
                    subfolderCount = maxOf(node.totalSubfolderCount, node.children.size),
                    dominantExtensions = topExtensions,
                    fractionOfScanned = (node.totalBytes.toDouble() / effectiveRootBytes.toDouble())
                        .toFloat()
                        .coerceIn(0f, 1f),
                )
            }

        return enforceDisjointAntichain(sortedItems).take(maxCount)
    }

    private fun enforceDisjointAntichain(items: List<AnalyzedFolderItem>): List<AnalyzedFolderItem> {
        val result = ArrayList<AnalyzedFolderItem>(items.size)
        val acceptedPrefixes = ArrayList<String>(items.size)
        for (candidate in items) {
            val candPrefix = normalizedDirPrefix(candidate.path)
            val conflicts = acceptedPrefixes.any { existingPrefix ->
                candPrefix == existingPrefix ||
                    candPrefix.startsWith(existingPrefix) ||
                    existingPrefix.startsWith(candPrefix)
            }
            if (!conflicts) {
                result.add(candidate)
                acceptedPrefixes.add(candPrefix)
            }
        }
        return result
    }

    private fun normalizedDirPrefix(path: String): String {
        val trimmed = path.replace('\\', '/').trimEnd('/')
        return if (trimmed.isEmpty()) "/" else "$trimmed/"
    }

    private fun readStatFsSafely(path: String): Triple<Long, Long, Long>? = runCatching {
        val stat = StatFs(path)
        val total = stat.totalBytes.coerceAtLeast(0L)
        val free = stat.availableBytes.coerceAtLeast(0L)
        val used = (total - free).coerceAtLeast(0L)
        Triple(total, used, free)
    }.getOrNull()

    private fun canonicalPathOf(file: File): String =
        runCatching { file.canonicalPath }.getOrElse { file.absolutePath }

    private fun buildRelativePath(parentRel: String, name: String): String =
        if (parentRel.isEmpty()) name else "$parentRel/$name"

    private fun isTempOrCacheFolderName(name: String): Boolean =
        name.lowercase(Locale.ROOT) in TEMP_FOLDER_NAMES

    private fun computePartialSha256(file: File, expectedSize: Long): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        for (shift in 56 downTo 0 step 8) {
            digest.update(((expectedSize ushr shift) and 0xFFL).toByte())
        }
        RandomAccessFile(file, "r").use { raf ->
            val actualLength = raf.length()
            if (actualLength <= 0L) return@runCatching null
            val headBuf = ByteArray(PARTIAL_HASH_WINDOW_BYTES)
            val headRead = readUpTo(raf, headBuf)
            if (headRead > 0) {
                digest.update(headBuf, 0, headRead)
            }
            if (actualLength > PARTIAL_HASH_WINDOW_BYTES) {
                val tailOffset = (actualLength - PARTIAL_HASH_WINDOW_BYTES)
                    .coerceAtLeast(PARTIAL_HASH_WINDOW_BYTES.toLong())
                raf.seek(tailOffset)
                val tailBuf = ByteArray(PARTIAL_HASH_WINDOW_BYTES)
                val tailRead = readUpTo(raf, tailBuf)
                if (tailRead > 0) {
                    digest.update(tailBuf, 0, tailRead)
                }
            }
        }
        digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }
    }.getOrNull()

    private fun readUpTo(raf: RandomAccessFile, buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val read = raf.read(buffer, total, buffer.size - total)
            if (read <= 0) break
            total += read
        }
        return total
    }

    private fun categoryLabel(category: FileCategory): String = when (category) {
        FileCategory.DIRECTORY -> "Ordner"
        FileCategory.VIDEO -> "Videos"
        FileCategory.IMAGE -> "Bilder"
        FileCategory.AUDIO -> "Audio"
        FileCategory.ARCHIVE -> "Archive"
        FileCategory.APK -> "APKs"
        FileCategory.DOCUMENT -> "Dokumente"
        FileCategory.TEXT -> "Textdateien"
        FileCategory.OTHER -> "Sonstige"
    }

    private fun emptyResult(rootPath: String, volumeStats: StorageVolumeStats): StorageScanResult =
        StorageScanResult(
            rootPath = rootPath,
            volumeStats = volumeStats,
            scannedBytes = 0L,
            totalFilesCount = 0,
            totalFoldersCount = 0,
            categoryBreakdowns = emptyList(),
            disjointTopFolders = emptyList(),
            largestFiles = emptyList(),
            duplicateGroups = emptyList(),
            largeFilesOver100MbBytes = 0L,
            largeFilesOver100MbCount = 0,
            archiveAndApkBytes = 0L,
            archiveAndApkCount = 0,
            tempAndCacheBytes = 0L,
            tempAndCacheCount = 0,
            reclaimableDuplicatesBytes = 0L,
        )
}

internal fun formatStorageBytes(bytes: Long, locale: Locale = Locale.GERMAN): String {
    val safe = bytes.coerceAtLeast(0L)
    if (safe < 1024L) return "$safe B"
    val kb = safe / 1024.0
    if (kb < 1024.0) return String.format(locale, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return String.format(locale, "%.1f MB", mb)
    val gb = mb / 1024.0
    if (gb < 1024.0) return String.format(locale, "%.2f GB", gb)
    return String.format(locale, "%.2f TB", gb / 1024.0)
}
