package com.monolith.feature.explorer

import android.content.Context
import com.monolith.core.model.LocationType
import com.monolith.core.model.VfsNode
import com.monolith.core.vfs.CloudCacheKey
import com.monolith.core.vfs.GoogleDriveVfsProvider
import com.monolith.core.vfs.SmbVfsProvider
import com.monolith.core.vfs.VfsProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File

object CloudCacheManager {

    private fun cacheDir(context: Context): File =
        File(context.cacheDir, "shared_drive")

    fun cachedFileFor(context: Context, node: VfsNode): File =
        File(cacheDir(context), CloudCacheKey.cachedFileName(node))

    fun folderMarkerFor(context: Context, folder: VfsNode): File =
        File(cacheDir(context), CloudCacheKey.folderMarkerName(folder))

    fun isNodeCached(context: Context, node: VfsNode): Boolean {
        return if (node.isDirectory) {
            folderMarkerFor(context, node).isFile
        } else {
            val f = cachedFileFor(context, node)
            f.isFile && (node.sizeBytes <= 0L || f.length() == node.sizeBytes)
        }
    }

    suspend fun cacheNodes(
        context: Context,
        nodes: List<VfsNode>,
        driveProvider: GoogleDriveVfsProvider,
        smbProvider: SmbVfsProvider? = null,
    ): Int = withContext(Dispatchers.IO) {
        cacheDir(context).mkdirs()
        var cachedCount = 0

        fun providerFor(node: VfsNode): VfsProvider =
            if (node.locationType == LocationType.SMB && smbProvider != null) {
                smbProvider
            } else {
                driveProvider
            }

        suspend fun cacheSingleFile(fileNode: VfsNode): File {
            val outFile = cachedFileFor(context, fileNode)
            if (!isNodeCached(context, fileNode)) {
                val tempFile = File(outFile.parentFile, "${outFile.name}.tmp")
                try {
                    providerFor(fileNode).openInputStream(fileNode.path).use { input ->
                        tempFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    if (outFile.exists()) {
                        outFile.delete()
                    }
                    if (!tempFile.renameTo(outFile)) {
                        tempFile.copyTo(outFile, overwrite = true)
                        tempFile.delete()
                    }
                } catch (t: Throwable) {
                    tempFile.delete()
                    throw t
                }
            }
            cachedCount++
            return outFile
        }

        suspend fun cacheFolderRecursively(folder: VfsNode): List<String> {
            val children = providerFor(folder).list(folder.path, showHidden = true)
            val cachedNames = mutableListOf<String>()
            for (child in children) {
                if (child.isDirectory) {
                    cachedNames.addAll(cacheFolderRecursively(child))
                } else {
                    val outFile = cacheSingleFile(child)
                    cachedNames.add(outFile.name)
                }
            }
            val markerArray = JSONArray()
            cachedNames.distinct().forEach { markerArray.put(it) }
            folderMarkerFor(context, folder).writeText(markerArray.toString())
            return cachedNames
        }

        for (node in nodes) {
            if (node.isDirectory) {
                cacheFolderRecursively(node)
            } else {
                cacheSingleFile(node)
            }
        }

        cachedCount
    }

    suspend fun removeCachedNodes(
        context: Context,
        nodes: List<VfsNode>,
    ) = withContext(Dispatchers.IO) {
        val dir = cacheDir(context)
        for (node in nodes) {
            if (node.isDirectory) {
                val marker = folderMarkerFor(context, node)
                if (marker.isFile) {
                    runCatching {
                        val array = JSONArray(marker.readText())
                        for (i in 0 until array.length()) {
                            val fileName = array.optString(i)
                            if (fileName.isNotBlank()) {
                                File(dir, fileName).delete()
                            }
                        }
                    }
                    marker.delete()
                }
            } else {
                cachedFileFor(context, node).delete()
            }
        }
    }
}
