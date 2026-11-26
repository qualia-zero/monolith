package com.monolith.core.vfs

import com.monolith.core.model.LocationType
import com.monolith.core.model.VfsNode
import java.security.MessageDigest

object CloudCacheKey {
    fun cachedFileName(node: VfsNode): String {
        val safeName = node.name.replace('/', '_')
        val prefix = when (node.locationType) {
            LocationType.SMB -> "smb_${sha256Prefix(node.path)}"
            else -> node.id
        }
        return "${prefix}_$safeName"
    }

    fun folderMarkerName(folder: VfsNode): String = when (folder.locationType) {
        LocationType.SMB -> "folder_smb_${sha256Prefix(folder.path)}.json"
        else -> "folder_${folder.id}.json"
    }

    private fun sha256Prefix(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(24)
    }
}
