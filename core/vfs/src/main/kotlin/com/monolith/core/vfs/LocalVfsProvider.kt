package com.monolith.core.vfs

import com.monolith.core.common.MimeNormalizer
import com.monolith.core.model.LocationType
import com.monolith.core.model.VfsNode
import kotlinx.coroutines.Dispatchers
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

    override suspend fun createDirectory(parentPath: String, name: String): VfsNode =
        withContext(Dispatchers.IO) {
            val target = File(parentPath, name)
            if (!target.exists() && !target.mkdirs()) {
                throw IOException("Failed to create directory: ${target.absolutePath}")
            }
            target.toVfsNode()
        }

    override suspend fun delete(path: String): Boolean =
        withContext(Dispatchers.IO) {
            File(path).deleteRecursively()
        }

    override suspend fun rename(path: String, newName: String): VfsNode =
        withContext(Dispatchers.IO) {
            val source = File(path)
            val dest = File(source.parentFile, newName)
            if (!source.renameTo(dest)) {
                throw IOException("Failed to rename ${source.absolutePath} to ${dest.absolutePath}")
            }
            dest.toVfsNode()
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
}
