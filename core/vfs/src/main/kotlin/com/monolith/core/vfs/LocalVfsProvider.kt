package com.monolith.core.vfs

import com.monolith.core.common.MimeNormalizer
import com.monolith.core.model.LocationType
import com.monolith.core.model.VfsNode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

class LocalVfsProvider : VfsProvider {
    override val locationType: LocationType = LocationType.LOCAL

    override suspend fun list(path: String, showHidden: Boolean): List<VfsNode> =
        withContext(Dispatchers.IO) {
            val dir = File(path)
            if (!dir.exists() || !dir.isDirectory) return@withContext emptyList()
            val files = dir.listFiles() ?: return@withContext emptyList()
            files.asSequence()
                .filter { showHidden || !it.name.startsWith(".") }
                .map { it.toVfsNode() }
                .toList()
        }

    override suspend fun stat(path: String): VfsNode? =
        withContext(Dispatchers.IO) {
            val file = File(path)
            if (!file.exists()) null else file.toVfsNode()
        }

    override suspend fun openInputStream(path: String, offset: Long): InputStream =
        withContext(Dispatchers.IO) {
            val stream = FileInputStream(File(path))
            if (offset > 0L) {
                var remaining = offset
                while (remaining > 0L) {
                    val skipped = stream.skip(remaining)
                    if (skipped <= 0L) break
                    remaining -= skipped
                }
            }
            stream
        }

    override suspend fun openOutputStream(path: String): OutputStream =
        withContext(Dispatchers.IO) {
            FileOutputStream(File(path))
        }

    override suspend fun createDirectory(parentPath: String, name: String): Result<Unit> =
        runIoResult {
            val trimmed = name.trim()
            validateName(trimmed)
            val target = File(parentPath, trimmed)
            if (target.exists()) {
                throw IOException("Ordner oder Datei existiert bereits: $trimmed")
            }
            if (!target.mkdirs()) {
                throw IOException("Ordner konnte nicht erstellt werden: ${target.absolutePath}")
            }
        }

    override suspend fun rename(path: String, newName: String): Result<Unit> =
        runIoResult {
            val trimmed = newName.trim()
            validateName(trimmed)
            val source = File(path)
            if (!source.exists()) {
                throw IOException("Quelle nicht gefunden: $path")
            }
            val dest = File(source.parentFile, trimmed)
            if (dest.exists()) {
                throw IOException("Ziel existiert bereits: $trimmed")
            }
            if (!source.renameTo(dest)) {
                throw IOException("Umbenennen fehlgeschlagen: ${source.name}")
            }
        }

    override suspend fun delete(paths: List<String>): Result<Unit> =
        runIoResult {
            for (path in paths) {
                val target = File(path)
                if (target.exists() && !target.deleteRecursively()) {
                    throw IOException("Löschen fehlgeschlagen: ${target.name}")
                }
            }
        }

    override suspend fun copy(
        sourcePaths: List<String>,
        targetDir: String,
        onProgress: ((VfsTransferUpdate) -> Unit)?,
    ): Result<Unit> =
        runIoResult {
            val targetRoot = requireDirectory(targetDir)
            for (sourcePath in sourcePaths) {
                currentCoroutineContext().ensureActive()
                val source = File(sourcePath)
                if (!source.exists()) {
                    throw IOException("Quelle nicht gefunden: $sourcePath")
                }
                ensureNotDescendant(source, targetRoot, "kopiert")
                val dest = resolveUniqueTarget(targetRoot, source.name, source.isDirectory)
                copyRecursivelyWithProgress(source, dest, onProgress)
            }
        }

    override suspend fun move(
        sourcePaths: List<String>,
        targetDir: String,
        onProgress: ((VfsTransferUpdate) -> Unit)?,
    ): Result<Unit> =
        runIoResult {
            val targetRoot = requireDirectory(targetDir)
            val targetCanonical = targetRoot.canonicalPath
            for (sourcePath in sourcePaths) {
                currentCoroutineContext().ensureActive()
                val source = File(sourcePath)
                if (!source.exists()) {
                    throw IOException("Quelle nicht gefunden: $sourcePath")
                }
                if (source.parentFile?.canonicalPath == targetCanonical) {
                    continue
                }
                ensureNotDescendant(source, targetRoot, "verschoben")
                val dest = resolveUniqueTarget(targetRoot, source.name, source.isDirectory)
                if (source.renameTo(dest)) {
                    reportMovedNode(dest, onProgress)
                } else {
                    copyRecursivelyWithProgress(source, dest, onProgress)
                    if (!source.deleteRecursively()) {
                        throw IOException("Quelle konnte nach Verschieben nicht gelöscht werden: ${source.name}")
                    }
                }
            }
        }

    private suspend fun copyRecursivelyWithProgress(
        source: File,
        dest: File,
        onProgress: ((VfsTransferUpdate) -> Unit)?,
    ) {
        currentCoroutineContext().ensureActive()
        if (source.isDirectory) {
            if (dest.exists() && !dest.isDirectory) {
                throw IOException("Ziel existiert bereits: ${dest.absolutePath}")
            }
            if (!dest.exists() && !dest.mkdirs()) {
                throw IOException("Ordner konnte nicht erstellt werden: ${dest.absolutePath}")
            }
            val children = source.listFiles()
            if (children.isNullOrEmpty()) {
                onProgress?.invoke(VfsTransferUpdate(currentFileName = source.name, itemsDelta = 1))
                return
            }
            for (child in children) {
                copyRecursivelyWithProgress(child, File(dest, child.name), onProgress)
            }
        } else {
            dest.parentFile?.let { parent ->
                if (!parent.exists() && !parent.mkdirs()) {
                    throw IOException("Zielordner konnte nicht erstellt werden: ${parent.absolutePath}")
                }
            }
            if (dest.exists()) {
                throw IOException("Ziel existiert bereits: ${dest.absolutePath}")
            }
            val buffer = ByteArray(COPY_BUFFER_SIZE)
            try {
                FileInputStream(source).use { input ->
                    FileOutputStream(dest).use { output ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            onProgress?.invoke(
                                VfsTransferUpdate(
                                    currentFileName = source.name,
                                    bytesDelta = read.toLong(),
                                ),
                            )
                        }
                    }
                }
            } catch (e: Throwable) {
                dest.delete()
                throw e
            }
            onProgress?.invoke(
                VfsTransferUpdate(
                    currentFileName = source.name,
                    itemsDelta = 1,
                ),
            )
        }
    }

    private fun reportMovedNode(node: File, onProgress: ((VfsTransferUpdate) -> Unit)?) {
        if (onProgress == null) return
        if (node.isDirectory) {
            val children = node.listFiles()
            if (children.isNullOrEmpty()) {
                onProgress(VfsTransferUpdate(currentFileName = node.name, itemsDelta = 1))
            } else {
                for (child in children) {
                    reportMovedNode(child, onProgress)
                }
            }
        } else {
            onProgress(
                VfsTransferUpdate(
                    currentFileName = node.name,
                    bytesDelta = node.length(),
                    itemsDelta = 1,
                ),
            )
        }
    }

    private suspend inline fun runIoResult(crossinline block: suspend () -> Unit): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                block()
                Result.success(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    private fun validateName(name: String) {
        if (name.isEmpty() || name == "." || name == ".." || name.contains('/') || name.contains('\\')) {
            throw IllegalArgumentException("Ungültiger Name: $name")
        }
    }

    private fun requireDirectory(path: String): File {
        val dir = File(path)
        if (!dir.exists() || !dir.isDirectory) {
            throw IOException("Zielordner nicht gefunden: $path")
        }
        return dir
    }

    private fun ensureNotDescendant(source: File, targetRoot: File, verb: String) {
        if (!source.isDirectory) return
        val srcCanonical = source.canonicalPath
        val targetCanonical = targetRoot.canonicalPath
        if (targetCanonical == srcCanonical || targetCanonical.startsWith("$srcCanonical${File.separator}")) {
            throw IOException("Ordner kann nicht in sich selbst $verb werden: ${source.name}")
        }
    }

    private fun resolveUniqueTarget(targetDir: File, name: String, isDirectory: Boolean): File {
        val direct = File(targetDir, name)
        if (!direct.exists()) return direct

        val dotIndex = if (isDirectory) -1 else name.lastIndexOf('.')
        val (base, ext) = if (dotIndex > 0) {
            name.substring(0, dotIndex) to name.substring(dotIndex)
        } else {
            name to ""
        }
        var index = 1
        while (true) {
            val candidate = File(targetDir, "$base ($index)$ext")
            if (!candidate.exists()) return candidate
            index++
        }
    }

    private fun File.toVfsNode(): VfsNode {
        val isDir = isDirectory
        val mime = if (isDir) "inode/directory" else MimeNormalizer.normalizeMime(name)
        return VfsNode(
            id = absolutePath,
            name = name.ifEmpty { absolutePath },
            path = absolutePath,
            parentPath = parent,
            locationType = LocationType.LOCAL,
            isDirectory = isDir,
            sizeBytes = if (isDir) 0L else length(),
            lastModifiedMs = lastModified(),
            mimeType = mime,
            category = MimeNormalizer.categorize(name, isDir, mime),
            childCount = if (isDir) list()?.size else null,
        )
    }

    private companion object {
        const val COPY_BUFFER_SIZE = 64 * 1024
    }
}
