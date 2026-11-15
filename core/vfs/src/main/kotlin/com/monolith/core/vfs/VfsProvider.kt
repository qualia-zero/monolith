package com.monolith.core.vfs

import com.monolith.core.model.LocationType
import com.monolith.core.model.VfsNode
import java.io.InputStream
import java.io.OutputStream

interface VfsProvider {
    val locationType: LocationType

    suspend fun list(path: String, showHidden: Boolean = false): List<VfsNode>
    suspend fun stat(path: String): VfsNode?
    suspend fun openInputStream(path: String, offset: Long = 0L): InputStream
    suspend fun openOutputStream(path: String): OutputStream
    suspend fun createDirectory(parentPath: String, name: String): VfsNode
    suspend fun delete(path: String): Boolean
    suspend fun rename(path: String, newName: String): VfsNode
}
