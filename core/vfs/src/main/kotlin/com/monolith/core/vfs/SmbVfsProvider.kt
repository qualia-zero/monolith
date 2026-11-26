package com.monolith.core.vfs

import android.content.Context
import com.monolith.core.common.MimeNormalizer
import com.monolith.core.model.FileCategory
import com.monolith.core.model.LocationType
import com.monolith.core.model.VfsNode
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtStatus
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbAuthException
import jcifs.smb.SmbException
import jcifs.smb.SmbFile
import jcifs.smb.SmbRandomAccessFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class SmbAuthRequiredException(
    val serverId: String,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

class SmbVfsProvider(
    private val context: Context,
) : VfsProvider {

    override val locationType: LocationType = LocationType.SMB

    private val memoryCache = ConcurrentHashMap<String, List<VfsNode>>()
    private val cacheDir by lazy {
        File(context.applicationContext.cacheDir, "smb_dir_cache").apply { mkdirs() }
    }

    fun hasCachedList(path: String, showHidden: Boolean): Boolean {
        val key = cacheKey(path, showHidden)
        if (memoryCache.containsKey(key)) return true
        return cacheFileForKey(key).isFile
    }

    fun invalidateCache(path: String? = null) {
        if (path == null) {
            memoryCache.clear()
            cacheDir.listFiles()?.forEach { it.delete() }
            return
        }
        val normalized = path.trimEnd('/')
        listOf(true, false).forEach { hidden ->
            val key = "$normalized|$hidden"
            memoryCache.remove(key)
            cacheFileForKey(key).delete()
        }
    }

    override suspend fun list(path: String, showHidden: Boolean): List<VfsNode> =
        list(path = path, showHidden = showHidden, forceRefresh = false)

    suspend fun list(path: String, showHidden: Boolean, forceRefresh: Boolean): List<VfsNode> =
        withContext(Dispatchers.IO) {
            val key = cacheKey(path, showHidden)
            if (!forceRefresh) {
                memoryCache[key]?.let { return@withContext it }
                readDiskCache(key)?.let { diskNodes ->
                    memoryCache[key] = diskNodes
                    return@withContext diskNodes
                }
            }

            val parsed = SmbPathParser.parse(path)
                ?: throw IOException("Ungültiger SMB-Pfad: $path")
            val config = requireServer(parsed.serverId)

            val nodes = withSmbAuthRetry(config) { ctx ->
                val smbUrl = SmbPathParser.encodeSmbUrl(
                    host = config.host,
                    port = config.port,
                    share = parsed.share,
                    relativePath = parsed.relativePath,
                    isDirectory = true,
                )
                val dir = SmbFile(smbUrl, ctx)
                val children = dir.listFiles() ?: emptyArray()
                if (parsed.share == null) {
                    children.asSequence()
                        .filter { child ->
                            val type = runCatching { child.type }.getOrDefault(0)
                            if (type == SmbFile.TYPE_NAMED_PIPE || type == SmbFile.TYPE_PRINTER) {
                                return@filter false
                            }
                            val shareName = child.name.trimEnd('/')
                            if (!showHidden && (shareName.endsWith("$") || shareName.startsWith("."))) {
                                return@filter false
                            }
                            true
                        }
                        .map { child ->
                            val shareName = child.name.trimEnd('/')
                            VfsNode(
                                id = SmbPathParser.buildPath(config.id, share = shareName, isDirectory = true),
                                name = shareName,
                                path = SmbPathParser.buildPath(config.id, share = shareName, isDirectory = true),
                                parentPath = SmbPathParser.buildPath(config.id, isDirectory = true),
                                locationType = LocationType.SMB,
                                isDirectory = true,
                                sizeBytes = 0L,
                                lastModifiedMs = runCatching { child.lastModified }.getOrDefault(0L),
                                mimeType = "inode/directory",
                                category = FileCategory.DIRECTORY,
                            )
                        }
                        .toList()
                } else {
                    children.asSequence()
                        .filter { child ->
                            val cleanName = child.name.trimEnd('/')
                            cleanName != "." && cleanName != ".." && (showHidden || !cleanName.startsWith("."))
                        }
                        .map { child ->
                            child.toVfsNode(
                                serverId = config.id,
                                share = parsed.share,
                                parentRelative = parsed.relativePath,
                            )
                        }
                        .toList()
                }
            }

            memoryCache[key] = nodes
            writeDiskCache(key, nodes)
            nodes
        }

    override suspend fun stat(path: String): VfsNode? =
        withContext(Dispatchers.IO) {
            val parsed = SmbPathParser.parse(path) ?: return@withContext null
            val config = SmbConnectionStore.getServer(context, parsed.serverId) ?: return@withContext null
            if (parsed.share == null) {
                return@withContext VfsNode(
                    id = SmbPathParser.buildPath(config.id, isDirectory = true),
                    name = config.effectiveDisplayName(),
                    path = SmbPathParser.buildPath(config.id, isDirectory = true),
                    parentPath = null,
                    locationType = LocationType.SMB,
                    isDirectory = true,
                    sizeBytes = 0L,
                    lastModifiedMs = 0L,
                    mimeType = "inode/directory",
                    category = FileCategory.DIRECTORY,
                )
            }
            if (parsed.relativePath.isEmpty()) {
                return@withContext VfsNode(
                    id = SmbPathParser.buildPath(config.id, parsed.share, isDirectory = true),
                    name = parsed.share,
                    path = SmbPathParser.buildPath(config.id, parsed.share, isDirectory = true),
                    parentPath = SmbPathParser.buildPath(config.id, isDirectory = true),
                    locationType = LocationType.SMB,
                    isDirectory = true,
                    sizeBytes = 0L,
                    lastModifiedMs = 0L,
                    mimeType = "inode/directory",
                    category = FileCategory.DIRECTORY,
                )
            }

            withSmbAuthRetry(config) { ctx ->
                val fileUrl = SmbPathParser.encodeSmbUrl(
                    host = config.host,
                    port = config.port,
                    share = parsed.share,
                    relativePath = parsed.relativePath,
                    isDirectory = path.endsWith("/"),
                )
                val smbFile = SmbFile(fileUrl, ctx)
                if (!smbFile.exists()) return@withSmbAuthRetry null
                val isDir = smbFile.isDirectory
                val parentRel = parsed.relativePath.substringBeforeLast('/', "")
                smbFile.toVfsNode(
                    serverId = config.id,
                    share = parsed.share,
                    parentRelative = parentRel,
                    overrideIsDir = isDir,
                )
            }
        }

    override suspend fun openInputStream(path: String, offset: Long): InputStream =
        withContext(Dispatchers.IO) {
            val parsed = SmbPathParser.parse(path)
                ?: throw IOException("Ungültiger SMB-Pfad: $path")
            val config = requireServer(parsed.serverId)
            withSmbAuthRetry(config) { ctx ->
                val smbUrl = SmbPathParser.encodeSmbUrl(
                    host = config.host,
                    port = config.port,
                    share = parsed.share,
                    relativePath = parsed.relativePath,
                    isDirectory = false,
                )
                val smbFile = SmbFile(smbUrl, ctx)
                if (offset > 0L) {
                    val raf = SmbRandomAccessFile(smbFile, "r")
                    try {
                        raf.seek(offset)
                    } catch (t: Throwable) {
                        runCatching { raf.close() }
                        throw t
                    }
                    object : InputStream() {
                        override fun read(): Int = raf.read()
                        override fun read(b: ByteArray, off: Int, len: Int): Int = raf.read(b, off, len)
                        override fun close() {
                            raf.close()
                        }
                    }
                } else {
                    BufferedInputStream(smbFile.openInputStream(), TRANSFER_BUFFER_SIZE)
                }
            }
        }

    override suspend fun openOutputStream(path: String): OutputStream =
        withContext(Dispatchers.IO) {
            val parsed = SmbPathParser.parse(path)
                ?: throw IOException("Ungültiger SMB-Pfad: $path")
            val config = requireServer(parsed.serverId)
            withSmbAuthRetry(config) { ctx ->
                val smbUrl = SmbPathParser.encodeSmbUrl(
                    host = config.host,
                    port = config.port,
                    share = parsed.share,
                    relativePath = parsed.relativePath,
                    isDirectory = false,
                )
                SmbFile(smbUrl, ctx).openOutputStream()
            }
        }

    override suspend fun createDirectory(parentPath: String, name: String): Result<Unit> =
        runIoResult {
            val trimmed = name.trim()
            validateName(trimmed)
            val parsed = SmbPathParser.parse(parentPath)
                ?: throw IOException("Ungültiger SMB-Pfad: $parentPath")
            val share = parsed.share
                ?: throw IllegalStateException("Auf Server-Ebene können keine neuen Freigaben erstellt werden.")
            val config = requireServer(parsed.serverId)
            val newRel = if (parsed.relativePath.isEmpty()) trimmed else "${parsed.relativePath}/$trimmed"
            withSmbAuthRetry(config) { ctx ->
                val target = SmbFile(
                    SmbPathParser.encodeSmbUrl(config.host, config.port, share, newRel, isDirectory = true),
                    ctx,
                )
                if (target.exists()) {
                    throw IOException("Ordner oder Datei existiert bereits: $trimmed")
                }
                target.mkdir()
            }
            invalidateCache(parentPath)
        }

    override suspend fun rename(path: String, newName: String): Result<Unit> =
        runIoResult {
            val trimmed = newName.trim()
            validateName(trimmed)
            val parsed = SmbPathParser.parse(path)
                ?: throw IOException("Ungültiger SMB-Pfad: $path")
            val share = parsed.share
                ?: throw IllegalStateException("Server kann nicht umbenannt werden.")
            if (parsed.relativePath.isEmpty()) {
                throw IllegalStateException("Freigaben können nicht umbenannt werden.")
            }
            val config = requireServer(parsed.serverId)
            val parentRel = parsed.relativePath.substringBeforeLast('/', "")
            val destRel = if (parentRel.isEmpty()) trimmed else "$parentRel/$trimmed"

            withSmbAuthRetry(config) { ctx ->
                val srcUrl = SmbPathParser.encodeSmbUrl(
                    config.host,
                    config.port,
                    share,
                    parsed.relativePath,
                    isDirectory = path.endsWith("/"),
                )
                val src = SmbFile(srcUrl, ctx)
                val isDir = src.isDirectory
                val destUrl = SmbPathParser.encodeSmbUrl(config.host, config.port, share, destRel, isDirectory = isDir)
                val dest = SmbFile(destUrl, ctx)
                if (dest.exists()) {
                    throw IOException("Ziel existiert bereits: $trimmed")
                }
                src.renameTo(dest)
            }
            invalidateCache(null)
        }

    override suspend fun delete(paths: List<String>): Result<Unit> =
        runIoResult {
            for (path in paths) {
                currentCoroutineContext().ensureActive()
                val parsed = SmbPathParser.parse(path)
                    ?: throw IOException("Ungültiger SMB-Pfad: $path")
                val share = parsed.share
                    ?: throw IllegalStateException("Server kann nicht gelöscht werden.")
                if (parsed.relativePath.isEmpty()) {
                    throw IllegalStateException("Freigaben können nicht gelöscht werden.")
                }
                val config = requireServer(parsed.serverId)
                withSmbAuthRetry(config) { ctx ->
                    val target = SmbFile(
                        SmbPathParser.encodeSmbUrl(
                            config.host,
                            config.port,
                            share,
                            parsed.relativePath,
                            isDirectory = path.endsWith("/"),
                        ),
                        ctx,
                    )
                    if (target.exists()) {
                        deleteRecursively(target)
                    }
                }
            }
            invalidateCache(null)
        }

    override suspend fun copy(
        sourcePaths: List<String>,
        targetDir: String,
        onProgress: ((VfsTransferUpdate) -> Unit)?,
    ): Result<Unit> =
        runIoResult {
            val targetParsed = SmbPathParser.parse(targetDir)
                ?: throw IOException("Ungültiger SMB-Zielpfad: $targetDir")
            if (targetParsed.share == null) {
                throw IllegalStateException("Bitte zuerst eine Freigabe als Ziel öffnen.")
            }
            val targetConfig = requireServer(targetParsed.serverId)
            for (sourcePath in sourcePaths) {
                currentCoroutineContext().ensureActive()
                if (sourcePath.startsWith(SmbPathParser.SCHEME_PREFIX, ignoreCase = true)) {
                    copySmbNode(sourcePath, targetDir, targetConfig, onProgress)
                } else {
                    uploadLocalNode(File(sourcePath), targetDir, targetConfig, onProgress)
                }
            }
            invalidateCache(targetDir)
        }

    override suspend fun move(
        sourcePaths: List<String>,
        targetDir: String,
        onProgress: ((VfsTransferUpdate) -> Unit)?,
    ): Result<Unit> =
        runIoResult {
            val targetParsed = SmbPathParser.parse(targetDir)
                ?: throw IOException("Ungültiger SMB-Zielpfad: $targetDir")
            if (targetParsed.share == null) {
                throw IllegalStateException("Bitte zuerst eine Freigabe als Ziel öffnen.")
            }
            val targetConfig = requireServer(targetParsed.serverId)
            for (sourcePath in sourcePaths) {
                currentCoroutineContext().ensureActive()
                val srcParsed = SmbPathParser.parse(sourcePath)
                    ?: throw IOException("Ungültige SMB-Quelle: $sourcePath")
                val sameServerAndShare = srcParsed.serverId == targetParsed.serverId &&
                    srcParsed.share.equals(targetParsed.share, ignoreCase = false)
                if (sameServerAndShare && srcParsed.share != null && srcParsed.relativePath.isNotEmpty()) {
                    val srcName = srcParsed.relativePath.substringAfterLast('/')
                    val destRel = if (targetParsed.relativePath.isEmpty()) srcName else "${targetParsed.relativePath}/$srcName"
                    if (destRel == srcParsed.relativePath) continue
                    val info = stat(sourcePath)
                    withSmbAuthRetry(targetConfig) { ctx ->
                        val srcFile = SmbFile(
                            SmbPathParser.encodeSmbUrl(
                                targetConfig.host,
                                targetConfig.port,
                                srcParsed.share,
                                srcParsed.relativePath,
                                isDirectory = sourcePath.endsWith("/"),
                            ),
                            ctx,
                        )
                        val isDir = srcFile.isDirectory
                        val destUrl = resolveUniqueSmbTargetUrl(
                            ctx = ctx,
                            config = targetConfig,
                            share = targetParsed.share,
                            parentRel = targetParsed.relativePath,
                            name = srcName,
                            isDirectory = isDir,
                        )
                        srcFile.renameTo(SmbFile(destUrl, ctx))
                    }
                    onProgress?.invoke(
                        VfsTransferUpdate(
                            currentFileName = info?.name ?: srcName,
                            bytesDelta = info?.sizeBytes ?: 0L,
                            itemsDelta = 1,
                        ),
                    )
                } else {
                    copySmbNode(sourcePath, targetDir, targetConfig, onProgress)
                    delete(listOf(sourcePath)).getOrThrow()
                }
            }
            invalidateCache(null)
        }

    suspend fun uploadFile(
        localFile: File,
        targetFolderPath: String,
        onProgress: ((VfsTransferUpdate) -> Unit)? = null,
    ): Result<Unit> =
        runIoResult {
            if (!localFile.exists() || !localFile.isFile) {
                throw IOException("Lokale Datei nicht gefunden: ${localFile.absolutePath}")
            }
            val parsed = SmbPathParser.parse(targetFolderPath)
                ?: throw IOException("Ungültiger SMB-Zielpfad: $targetFolderPath")
            val share = parsed.share
                ?: throw IllegalStateException("Bitte zuerst eine Freigabe als Ziel öffnen.")
            val config = requireServer(parsed.serverId)
            val expectedSize = localFile.length()

            withSmbAuthRetry(config) { ctx ->
                val destUrl = resolveUniqueSmbTargetUrl(
                    ctx = ctx,
                    config = config,
                    share = share,
                    parentRel = parsed.relativePath,
                    name = localFile.name,
                    isDirectory = false,
                )
                val destFile = SmbFile(destUrl, ctx)
                var bytesSent = 0L
                FileInputStream(localFile).use { input ->
                    destFile.openOutputStream().use { output ->
                        val buffer = ByteArray(TRANSFER_BUFFER_SIZE)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            bytesSent += read
                            onProgress?.invoke(
                                VfsTransferUpdate(
                                    currentFileName = localFile.name,
                                    bytesDelta = read.toLong(),
                                ),
                            )
                        }
                        output.flush()
                    }
                }
                if (bytesSent != expectedSize) {
                    throw IOException("Unvollständiger SMB-Upload für ${localFile.name}: $bytesSent von $expectedSize Bytes.")
                }
            }
            onProgress?.invoke(VfsTransferUpdate(currentFileName = localFile.name, itemsDelta = 1))
            invalidateCache(targetFolderPath)
        }

    suspend fun downloadToFile(
        path: String,
        destFile: File,
        onProgress: ((VfsTransferUpdate) -> Unit)? = null,
        expectedSize: Long = -1L,
    ): Result<File> =
        withContext(Dispatchers.IO) {
            val parentDir = destFile.parentFile
            val tempFile = File(parentDir ?: File("."), ".monolith_smb_${UUID.randomUUID()}")
            try {
                parentDir?.let { dir ->
                    if (!dir.exists() && !dir.mkdirs()) {
                        throw IOException("Zielordner konnte nicht erstellt werden: ${dir.absolutePath}")
                    }
                }
                val resolvedExpectedSize = if (expectedSize >= 0L) {
                    expectedSize
                } else {
                    runCatching { stat(path)?.sizeBytes ?: -1L }.getOrDefault(-1L)
                }
                var bytesWritten = 0L
                openInputStream(path).use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(TRANSFER_BUFFER_SIZE)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            bytesWritten += read
                            onProgress?.invoke(
                                VfsTransferUpdate(
                                    currentFileName = destFile.name,
                                    bytesDelta = read.toLong(),
                                ),
                            )
                        }
                        output.flush()
                        output.fd.sync()
                    }
                }
                if (resolvedExpectedSize >= 0L && bytesWritten != resolvedExpectedSize) {
                    throw IOException(
                        "Unvollständiger SMB-Download für ${destFile.name}: $bytesWritten von $resolvedExpectedSize Bytes.",
                    )
                }
                if (destFile.exists() && !destFile.delete()) {
                    throw IOException("Bestehende Zieldatei konnte nicht ersetzt werden: ${destFile.absolutePath}")
                }
                if (!tempFile.renameTo(destFile)) {
                    throw IOException("Heruntergeladene Datei konnte nicht gespeichert werden: ${destFile.absolutePath}")
                }
                onProgress?.invoke(VfsTransferUpdate(currentFileName = destFile.name, itemsDelta = 1))
                Result.success(destFile)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            } finally {
                if (tempFile.exists()) {
                    tempFile.delete()
                }
            }
        }

    suspend fun testConnection(config: SmbServerConfig): Result<Unit> =
        runIoResult {
            withSmbAuthRetry(config) { ctx ->
                val url = SmbPathParser.encodeSmbUrl(
                    host = config.host,
                    port = config.port,
                    share = config.share.takeIf { it.isNotBlank() },
                    relativePath = "",
                    isDirectory = true,
                )
                val target = SmbFile(url, ctx)
                target.listFiles()
            }
        }

    private suspend fun copySmbNode(
        sourcePath: String,
        targetDir: String,
        targetConfig: SmbServerConfig,
        onProgress: ((VfsTransferUpdate) -> Unit)?,
    ) {
        currentCoroutineContext().ensureActive()
        val node = stat(sourcePath) ?: throw IOException("SMB-Quelle nicht gefunden: $sourcePath")
        val targetParsed = SmbPathParser.parse(targetDir)
            ?: throw IOException("Ungültiges SMB-Ziel: $targetDir")
        val targetShare = targetParsed.share
            ?: throw IllegalStateException("Ziel ist keine SMB-Freigabe.")
        if (node.isDirectory) {
            val newDirRel = withSmbAuthRetry(targetConfig) { ctx ->
                val uniqueUrl = resolveUniqueSmbTargetUrl(
                    ctx = ctx,
                    config = targetConfig,
                    share = targetShare,
                    parentRel = targetParsed.relativePath,
                    name = node.name,
                    isDirectory = true,
                )
                SmbFile(uniqueUrl, ctx).mkdir()
                if (targetParsed.relativePath.isEmpty()) node.name else "${targetParsed.relativePath}/${node.name}"
            }
            val newDirPath = SmbPathParser.buildPath(targetConfig.id, targetShare, newDirRel, isDirectory = true)
            val children = list(sourcePath, showHidden = true)
            if (children.isEmpty()) {
                onProgress?.invoke(VfsTransferUpdate(currentFileName = node.name, itemsDelta = 1))
                return
            }
            for (child in children) {
                copySmbNode(child.path, newDirPath, targetConfig, onProgress)
            }
        } else {
            val destRel = if (targetParsed.relativePath.isEmpty()) node.name else "${targetParsed.relativePath}/${node.name}"
            withSmbAuthRetry(targetConfig) { ctx ->
                val destUrl = resolveUniqueSmbTargetUrl(
                    ctx = ctx,
                    config = targetConfig,
                    share = targetShare,
                    parentRel = targetParsed.relativePath,
                    name = node.name,
                    isDirectory = false,
                )
                val destFile = SmbFile(destUrl, ctx)
                openInputStream(sourcePath).use { input ->
                    destFile.openOutputStream().use { output ->
                        val buffer = ByteArray(TRANSFER_BUFFER_SIZE)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            onProgress?.invoke(
                                VfsTransferUpdate(
                                    currentFileName = node.name,
                                    bytesDelta = read.toLong(),
                                ),
                            )
                        }
                        output.flush()
                    }
                }
            }
            onProgress?.invoke(VfsTransferUpdate(currentFileName = node.name, itemsDelta = 1))
        }
    }

    private suspend fun uploadLocalNode(
        localFile: File,
        targetDir: String,
        targetConfig: SmbServerConfig,
        onProgress: ((VfsTransferUpdate) -> Unit)?,
    ) {
        currentCoroutineContext().ensureActive()
        if (!localFile.exists()) {
            throw IOException("Lokale Quelle nicht gefunden: ${localFile.absolutePath}")
        }
        val targetParsed = SmbPathParser.parse(targetDir)
            ?: throw IOException("Ungültiges SMB-Ziel: $targetDir")
        val targetShare = targetParsed.share
            ?: throw IllegalStateException("Ziel ist keine SMB-Freigabe.")
        if (localFile.isDirectory) {
            val folderName = localFile.name
            withSmbAuthRetry(targetConfig) { ctx ->
                val dirUrl = SmbPathParser.encodeSmbUrl(
                    targetConfig.host,
                    targetConfig.port,
                    targetShare,
                    if (targetParsed.relativePath.isEmpty()) folderName else "${targetParsed.relativePath}/$folderName",
                    isDirectory = true,
                )
                val smbDir = SmbFile(dirUrl, ctx)
                if (!smbDir.exists()) smbDir.mkdir()
            }
            val subRel = if (targetParsed.relativePath.isEmpty()) folderName else "${targetParsed.relativePath}/$folderName"
            val subTargetDir = SmbPathParser.buildPath(targetConfig.id, targetShare, subRel, isDirectory = true)
            val children = localFile.listFiles()
            if (children.isNullOrEmpty()) {
                onProgress?.invoke(VfsTransferUpdate(currentFileName = folderName, itemsDelta = 1))
                return
            }
            for (child in children) {
                uploadLocalNode(child, subTargetDir, targetConfig, onProgress)
            }
        } else {
            uploadFile(localFile, targetDir, onProgress).getOrThrow()
        }
    }

    private fun resolveUniqueSmbTargetUrl(
        ctx: CIFSContext,
        config: SmbServerConfig,
        share: String,
        parentRel: String,
        name: String,
        isDirectory: Boolean,
    ): String {
        val directRel = if (parentRel.isEmpty()) name else "$parentRel/$name"
        val directUrl = SmbPathParser.encodeSmbUrl(config.host, config.port, share, directRel, isDirectory)
        if (!SmbFile(directUrl, ctx).exists()) return directUrl

        val dotIdx = if (isDirectory) -1 else name.lastIndexOf('.')
        val (base, ext) = if (dotIdx > 0) {
            name.substring(0, dotIdx) to name.substring(dotIdx)
        } else {
            name to ""
        }
        var index = 1
        while (true) {
            val candidateName = "$base ($index)$ext"
            val candidateRel = if (parentRel.isEmpty()) candidateName else "$parentRel/$candidateName"
            val candidateUrl = SmbPathParser.encodeSmbUrl(config.host, config.port, share, candidateRel, isDirectory)
            if (!SmbFile(candidateUrl, ctx).exists()) return candidateUrl
            index++
        }
    }

    private fun deleteRecursively(file: SmbFile) {
        if (file.isDirectory) {
            val dirUrl = if (file.canonicalPath.endsWith("/")) file.canonicalPath else "${file.canonicalPath}/"
            val dir = SmbFile(dirUrl, file.context)
            dir.listFiles()?.forEach { child ->
                deleteRecursively(child)
            }
            dir.delete()
        } else {
            file.delete()
        }
    }

    private inline fun <T> withSmbAuthRetry(
        config: SmbServerConfig,
        block: (CIFSContext) -> T,
    ): T {
        val primaryCtx = buildContext(config, guestFallback = false)
        return try {
            block(primaryCtx)
        } catch (e: SmbAuthException) {
            if (config.isGuest) {
                val guestCtx = buildContext(config, guestFallback = true)
                try {
                    block(guestCtx)
                } catch (e2: SmbAuthException) {
                    throw SmbAuthRequiredException(
                        serverId = config.id,
                        message = "SMB-Anmeldung erforderlich für ${config.effectiveDisplayName()}.",
                        cause = e2,
                    )
                } catch (e2: SmbException) {
                    throw mapSmbException(config, e2)
                } catch (e2: IOException) {
                    throw mapNetworkException(e2)
                }
            } else {
                throw SmbAuthRequiredException(
                    serverId = config.id,
                    message = "SMB-Anmeldung fehlgeschlagen für ${config.effectiveDisplayName()}.",
                    cause = e,
                )
            }
        } catch (e: SmbException) {
            throw mapSmbException(config, e)
        } catch (e: IOException) {
            throw mapNetworkException(e)
        }
    }

    private fun mapSmbException(config: SmbServerConfig, e: SmbException): IOException {
        return when (e.ntStatus) {
            NtStatus.NT_STATUS_LOGON_FAILURE,
            NtStatus.NT_STATUS_ACCESS_DENIED -> SmbAuthRequiredException(
                serverId = config.id,
                message = "Zugriff verweigert für ${config.effectiveDisplayName()}.",
                cause = e,
            )
            NtStatus.NT_STATUS_OBJECT_NAME_NOT_FOUND,
            NtStatus.NT_STATUS_NO_SUCH_FILE,
            NtStatus.NT_STATUS_OBJECT_PATH_NOT_FOUND -> IOException(
                "Datei oder Ordner auf dem SMB-Server nicht gefunden.",
                e,
            )
            NtStatus.NT_STATUS_BAD_NETWORK_NAME -> IOException(
                "SMB-Freigabe wurde nicht gefunden.",
                e,
            )
            NtStatus.NT_STATUS_SHARING_VIOLATION -> IOException(
                "Die Datei wird gerade von einem anderen Prozess verwendet.",
                e,
            )
            NtStatus.NT_STATUS_OBJECT_NAME_COLLISION -> IOException(
                "Datei oder Ordner existiert bereits.",
                e,
            )
            NtStatus.NT_STATUS_DISK_FULL -> IOException(
                "Nicht genügend Speicherplatz auf der SMB-Freigabe.",
                e,
            )
            else -> IOException(
                "SMB-Fehler: ${e.message ?: "Unbekannter Fehler."}",
                e,
            )
        }
    }

    private fun mapNetworkException(e: IOException): IOException = when (e) {
        is UnknownHostException,
        is ConnectException -> IOException("SMB-Server konnte nicht erreicht werden.", e)
        is SocketTimeoutException -> IOException("Zeitüberschreitung bei der Verbindung zum SMB-Server.", e)
        else -> e
    }

    private fun buildContext(config: SmbServerConfig, guestFallback: Boolean): CIFSContext {
        val poolKey = listOf(
            config.id,
            config.host,
            config.port.toString(),
            config.username,
            config.password,
            config.domain,
            config.isGuest.toString(),
            guestFallback.toString(),
        ).joinToString("|")
        return contextPool.getOrPut(poolKey) {
            val props = Properties().apply {
                put("jcifs.smb.client.minVersion", "SMB1")
                put("jcifs.smb.client.maxVersion", "SMB311")
                put("jcifs.smb.client.useSMB2Negotiation", "true")
                put("jcifs.smb.client.dfs.disabled", "true")
                put("jcifs.smb.client.connTimeout", "10000")
                put("jcifs.smb.client.responseTimeout", "15000")
                put("jcifs.smb.client.soTimeout", "20000")
            }
            val base = BaseContext(PropertyConfiguration(props))
            val auth = when {
                !config.isGuest && config.username.isNotBlank() ->
                    NtlmPasswordAuthenticator(config.domain, config.username, config.password)
                guestFallback ->
                    NtlmPasswordAuthenticator("", "guest", "")
                else ->
                    NtlmPasswordAuthenticator(null, null, null)
            }
            base.withCredentials(auth)
        }
    }

    private fun requireServer(serverId: String): SmbServerConfig =
        SmbConnectionStore.getServer(context, serverId)
            ?: throw IOException("SMB-Server nicht konfiguriert ($serverId).")

    private fun validateName(name: String) {
        if (name.isEmpty() || name == "." || name == ".." || name.contains('/') || name.contains('\\')) {
            throw IllegalArgumentException("Ungültiger Name: $name")
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

    private fun SmbFile.toVfsNode(
        serverId: String,
        share: String,
        parentRelative: String,
        overrideIsDir: Boolean? = null,
    ): VfsNode {
        val cleanName = name.trimEnd('/')
        val isDir = overrideIsDir ?: runCatching { isDirectory }.getOrDefault(name.endsWith("/"))
        val rel = if (parentRelative.isEmpty()) cleanName else "$parentRelative/$cleanName"
        val nodePath = SmbPathParser.buildPath(serverId, share, rel, isDirectory = isDir)
        val parentPath = SmbPathParser.buildPath(serverId, share, parentRelative, isDirectory = true)
        val mime = if (isDir) "inode/directory" else MimeNormalizer.normalizeMime(cleanName)
        val category = if (isDir) FileCategory.DIRECTORY else MimeNormalizer.categorize(cleanName, false, mime)
        val size = if (isDir) 0L else runCatching { length() }.getOrDefault(0L)
        val modified = runCatching { lastModified }.getOrDefault(0L)
        return VfsNode(
            id = nodePath,
            name = cleanName,
            path = nodePath,
            parentPath = parentPath,
            locationType = LocationType.SMB,
            isDirectory = isDir,
            sizeBytes = size,
            lastModifiedMs = modified,
            mimeType = mime,
            category = category,
        )
    }

    private fun cacheKey(path: String, showHidden: Boolean): String =
        "${path.trimEnd('/')}|$showHidden"

    private fun cacheFileForKey(key: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        return File(cacheDir, "$hex.json")
    }

    private fun readDiskCache(key: String): List<VfsNode>? {
        val file = cacheFileForKey(key)
        if (!file.isFile) return null
        return runCatching {
            val arr = JSONArray(file.readText(Charsets.UTF_8))
            buildList(arr.length()) {
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val category = runCatching {
                        FileCategory.valueOf(obj.optString("category", FileCategory.OTHER.name))
                    }.getOrDefault(FileCategory.OTHER)
                    add(
                        VfsNode(
                            id = obj.getString("id"),
                            name = obj.getString("name"),
                            path = obj.getString("path"),
                            parentPath = obj.optString("parentPath").ifEmpty { null },
                            locationType = LocationType.SMB,
                            isDirectory = obj.optBoolean("isDirectory", false),
                            sizeBytes = obj.optLong("sizeBytes", 0L),
                            lastModifiedMs = obj.optLong("lastModifiedMs", 0L),
                            mimeType = obj.optString("mimeType", ""),
                            category = category,
                        ),
                    )
                }
            }
        }.getOrNull()
    }

    private fun writeDiskCache(key: String, nodes: List<VfsNode>) {
        runCatching {
            val arr = JSONArray().apply {
                nodes.forEach { node ->
                    put(
                        JSONObject().apply {
                            put("id", node.id)
                            put("name", node.name)
                            put("path", node.path)
                            put("parentPath", node.parentPath.orEmpty())
                            put("isDirectory", node.isDirectory)
                            put("sizeBytes", node.sizeBytes)
                            put("lastModifiedMs", node.lastModifiedMs)
                            put("mimeType", node.mimeType)
                            put("category", node.category.name)
                        },
                    )
                }
            }
            cacheFileForKey(key).writeText(arr.toString(), Charsets.UTF_8)
        }
    }

    private companion object {
        const val TRANSFER_BUFFER_SIZE = 64 * 1024
        val contextPool = ConcurrentHashMap<String, CIFSContext>()
    }
}
