package com.monolith.core.vfs

import com.monolith.core.model.LocationType
import com.monolith.core.model.VfsNode
import java.io.InputStream
import java.io.OutputStream

data class VfsTransferUpdate(
    val currentFileName: String,
    val bytesDelta: Long = 0L,
    val itemsDelta: Int = 0,
)

interface VfsProvider {
    val locationType: LocationType

    suspend fun list(path: String, showHidden: Boolean = false): List<VfsNode>
    suspend fun stat(path: String): VfsNode?
    suspend fun openInputStream(path: String, offset: Long = 0L): InputStream
    suspend fun openOutputStream(path: String): OutputStream
    suspend fun createDirectory(parentPath: String, name: String): Result<Unit>
    suspend fun rename(path: String, newName: String): Result<Unit>
    suspend fun delete(paths: List<String>): Result<Unit>
    suspend fun copy(
        sourcePaths: List<String>,
        targetDir: String,
        onProgress: ((VfsTransferUpdate) -> Unit)? = null,
    ): Result<Unit>

    suspend fun move(
        sourcePaths: List<String>,
        targetDir: String,
        onProgress: ((VfsTransferUpdate) -> Unit)? = null,
    ): Result<Unit>
}
