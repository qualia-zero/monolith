package com.monolith.core.vfs

import android.content.Context
import com.monolith.core.common.MimeNormalizer
import com.monolith.core.model.FileCategory
import com.monolith.core.model.LocationType
import com.monolith.core.model.VfsNode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.ProtocolException
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class GoogleDriveVfsProvider(
    private val context: Context,
    private val authManager: GoogleDriveAuthManager = GoogleDriveAuthManager,
) : VfsProvider {

    override val locationType: LocationType = LocationType.GOOGLE_DRIVE

    private val memoryCache = ConcurrentHashMap<String, List<VfsNode>>()
    private val cacheDir by lazy {
        File(context.applicationContext.cacheDir, "gdrive_dir_cache").apply { mkdirs() }
    }

    fun extractId(path: String): String =
        path.removePrefix(SCHEME_PREFIX).trim('/').ifEmpty { ROOT_ID }

    fun streamUrl(path: String): String =
        "$FILES_ENDPOINT/${urlEncode(extractId(path))}?alt=media"

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
        val parentId = extractId(path)
        val account = authManager.getActiveEmail(context).orEmpty().ifBlank { "default" }
        listOf(true, false).forEach { hidden ->
            val key = "$account|$parentId|$hidden"
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

            val token = requireToken()
            val parentId = extractId(path)
            val query = "'$parentId' in parents and trashed = false"
            val encodedQuery = urlEncode(query)
            val fields = urlEncode("nextPageToken,files(id,name,mimeType,size,modifiedTime,thumbnailLink)")

            val nodes = mutableListOf<VfsNode>()
            var pageToken: String? = null

            do {
                val urlBuilder = StringBuilder(FILES_ENDPOINT)
                    .append("?q=").append(encodedQuery)
                    .append("&fields=").append(fields)
                    .append("&pageSize=1000")
                if (!pageToken.isNullOrEmpty()) {
                    urlBuilder.append("&pageToken=").append(urlEncode(pageToken))
                }

                val json = executeJsonRequest(
                    urlString = urlBuilder.toString(),
                    method = "GET",
                    token = token,
                )

                val filesArray = json.optJSONArray("files")
                if (filesArray != null) {
                    for (i in 0 until filesArray.length()) {
                        val item = filesArray.getJSONObject(i)
                        val name = item.optString("name", "")
                        if (!showHidden && name.startsWith(".")) continue
                        nodes.add(item.toVfsNode(parentPath = path))
                    }
                }

                pageToken = json.optString("nextPageToken", "").takeIf { it.isNotEmpty() }
            } while (pageToken != null)

            val folderIds = nodes.filter { it.isDirectory }.map { it.id }
            val counts = if (folderIds.isNotEmpty()) {
                runCatching { fetchChildCounts(folderIds, showHidden, token) }.getOrNull()
            } else {
                null
            }
            val finalNodes = if (counts != null) {
                nodes.map { node ->
                    if (node.isDirectory) node.copy(childCount = counts[node.id] ?: 0) else node
                }
            } else {
                nodes
            }

            memoryCache[key] = finalNodes
            writeDiskCache(key, finalNodes)
            finalNodes
        }

    private fun cacheKey(path: String, showHidden: Boolean): String {
        val account = authManager.getActiveEmail(context).orEmpty().ifBlank { "default" }
        return "$account|${extractId(path)}|$showHidden"
    }

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
                            locationType = LocationType.GOOGLE_DRIVE,
                            isDirectory = obj.optBoolean("isDirectory", false),
                            sizeBytes = obj.optLong("sizeBytes", 0L),
                            lastModifiedMs = obj.optLong("lastModifiedMs", 0L),
                            mimeType = obj.optString("mimeType", ""),
                            category = category,
                            thumbnailUrl = obj.optString("thumbnailUrl").ifEmpty { null },
                            childCount = obj.optInt("childCount", 0),
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
                            put("mimeType", node.mimeType.orEmpty())
                            put("category", node.category.name)
                            put("thumbnailUrl", node.thumbnailUrl.orEmpty())
                            put("childCount", node.childCount)
                        },
                    )
                }
            }
            cacheFileForKey(key).writeText(arr.toString(), Charsets.UTF_8)
        }
    }

    override suspend fun stat(path: String): VfsNode? =
        withContext(Dispatchers.IO) {
            val token = requireToken()
            val fileId = extractId(path)
            val fields = urlEncode("id,name,mimeType,size,modifiedTime,parents,thumbnailLink")
            val urlString = "$FILES_ENDPOINT/${urlEncode(fileId)}?fields=$fields"
            val conn = openConnection(urlString, "GET", token)
            try {
                val code = conn.responseCode
                if (code == HttpURLConnection.HTTP_NOT_FOUND) {
                    return@withContext null
                }
                if (code !in 200..299) {
                    throw IOException(readErrorMessage(conn, code))
                }
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val parents = json.optJSONArray("parents")
                val parentPath = parents?.optString(0)?.takeIf { it.isNotEmpty() }?.let { "$SCHEME_PREFIX$it" }
                json.toVfsNode(parentPath = parentPath)
            } finally {
                conn.disconnect()
            }
        }

    override suspend fun openInputStream(path: String, offset: Long): InputStream =
        withContext(Dispatchers.IO) {
            val token = requireToken()
            val conn = openConnection(streamUrl(path), "GET", token)
            if (offset > 0L) {
                conn.setRequestProperty("Range", "bytes=$offset-")
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                val msg = readErrorMessage(conn, code)
                conn.disconnect()
                throw IOException(msg)
            }
            val rawStream = BufferedInputStream(conn.inputStream)
            object : FilterInputStream(rawStream) {
                override fun close() {
                    try {
                        super.close()
                    } finally {
                        conn.disconnect()
                    }
                }
            }
        }

    override suspend fun openOutputStream(path: String): OutputStream =
        throw UnsupportedOperationException("Use uploadFile() for Google Drive uploads")

    suspend fun uploadFile(
        localFile: File,
        targetFolderPath: String,
        onProgress: ((VfsTransferUpdate) -> Unit)? = null,
    ): Result<Unit> =
        runIoResult {
            if (!localFile.exists() || !localFile.isFile) {
                throw IOException("Lokale Datei nicht gefunden: ${localFile.absolutePath}")
            }
            val token = requireToken()
            val parentId = extractId(targetFolderPath)
            val boundary = "monolith_${UUID.randomUUID().toString().replace("-", "")}"
            val metadata = JSONObject().apply {
                put("name", localFile.name)
                put("parents", JSONArray().put(parentId))
            }
            val fileMime = MimeNormalizer.normalizeMime(localFile.name)

            val conn = (URL(UPLOAD_ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                doInput = true
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setChunkedStreamingMode(TRANSFER_BUFFER_SIZE)
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Content-Type", "multipart/related; boundary=$boundary")
            }

            val expectedSize = localFile.length()
            try {
                var bytesSent = 0L
                DataOutputStream(conn.outputStream).use { out ->
                    out.writeBytes("--$boundary\r\n")
                    out.writeBytes("Content-Type: application/json; charset=UTF-8\r\n\r\n")
                    out.write(metadata.toString().toByteArray(Charsets.UTF_8))
                    out.writeBytes("\r\n--$boundary\r\n")
                    out.writeBytes("Content-Type: $fileMime\r\n\r\n")

                    localFile.inputStream().use { input ->
                        val buffer = ByteArray(TRANSFER_BUFFER_SIZE)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read == -1) break
                            out.write(buffer, 0, read)
                            bytesSent += read
                            onProgress?.invoke(
                                VfsTransferUpdate(
                                    currentFileName = localFile.name,
                                    bytesDelta = read.toLong(),
                                ),
                            )
                        }
                    }

                    out.writeBytes("\r\n--$boundary--\r\n")
                    out.flush()
                }

                if (bytesSent != expectedSize) {
                    throw IOException("Unvollständiger Upload für ${localFile.name}: $bytesSent von $expectedSize Bytes gesendet.")
                }

                val code = conn.responseCode
                if (code !in 200..299) {
                    throw IOException(readErrorMessage(conn, code))
                }

                val responseText = conn.inputStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (responseText.isNotBlank()) {
                    val responseJson = JSONObject(responseText)
                    if (responseJson.has("size")) {
                        val remoteSize = responseJson.optString("size", "").toLongOrNull()
                            ?: responseJson.optLong("size", -1L)
                        if (remoteSize != expectedSize) {
                            throw IOException("Upload-Größenprüfung fehlgeschlagen für ${localFile.name}: lokal $expectedSize Bytes, Drive $remoteSize Bytes")
                        }
                    }
                }

                onProgress?.invoke(
                    VfsTransferUpdate(
                        currentFileName = localFile.name,
                        itemsDelta = 1,
                    ),
                )
            } finally {
                conn.disconnect()
            }
        }

    suspend fun downloadToFile(
        path: String,
        destFile: File,
        onProgress: ((VfsTransferUpdate) -> Unit)? = null,
        expectedSize: Long = -1L,
    ): Result<File> =
        withContext(Dispatchers.IO) {
            val parentDir = destFile.parentFile
            val tempFile = File(parentDir ?: File("."), ".monolith_dl_${UUID.randomUUID()}")
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
                    throw IOException("Unvollständiger Download für ${destFile.name}: $bytesWritten von $resolvedExpectedSize Bytes empfangen.")
                }
                if (destFile.exists() && !destFile.delete()) {
                    throw IOException("Bestehende Zieldatei konnte nicht ersetzt werden: ${destFile.absolutePath}")
                }
                if (!tempFile.renameTo(destFile)) {
                    throw IOException("Heruntergeladene Datei konnte nicht gespeichert werden: ${destFile.absolutePath}")
                }
                onProgress?.invoke(
                    VfsTransferUpdate(
                        currentFileName = destFile.name,
                        itemsDelta = 1,
                    ),
                )
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

    override suspend fun createDirectory(parentPath: String, name: String): Result<Unit> =
        runIoResult {
            val trimmed = name.trim()
            if (trimmed.isEmpty()) throw IllegalArgumentException("Ordnername darf nicht leer sein.")
            val token = requireToken()
            createDriveFolder(token, extractId(parentPath), trimmed)
            invalidateCache(parentPath)
        }

    override suspend fun rename(path: String, newName: String): Result<Unit> =
        runIoResult {
            val trimmed = newName.trim()
            if (trimmed.isEmpty()) throw IllegalArgumentException("Neuer Name darf nicht leer sein.")
            val token = requireToken()
            val fileId = extractId(path)
            val body = JSONObject().put("name", trimmed)
            executeJsonRequest(
                urlString = "$FILES_ENDPOINT/${urlEncode(fileId)}",
                method = "PATCH",
                token = token,
                body = body,
            )
            invalidateCache(null)
        }

    override suspend fun delete(paths: List<String>): Result<Unit> =
        runIoResult {
            val token = requireToken()
            val body = JSONObject().put("trashed", true)
            for (path in paths) {
                val fileId = extractId(path)
                executeJsonRequest(
                    urlString = "$FILES_ENDPOINT/${urlEncode(fileId)}",
                    method = "PATCH",
                    token = token,
                    body = body,
                )
            }
            invalidateCache(null)
        }

    override suspend fun copy(
        sourcePaths: List<String>,
        targetDir: String,
        onProgress: ((VfsTransferUpdate) -> Unit)?,
    ): Result<Unit> =
        runIoResult {
            val token = requireToken()
            val targetId = extractId(targetDir)
            for (sourcePath in sourcePaths) {
                currentCoroutineContext().ensureActive()
                if (sourcePath.startsWith(SCHEME_PREFIX)) {
                    copyDriveNode(token, sourcePath, targetId, onProgress)
                } else {
                    uploadLocalNode(File(sourcePath), targetDir, onProgress)
                }
            }
        }

    override suspend fun move(
        sourcePaths: List<String>,
        targetDir: String,
        onProgress: ((VfsTransferUpdate) -> Unit)?,
    ): Result<Unit> =
        runIoResult {
            val token = requireToken()
            val targetId = extractId(targetDir)
            for (sourcePath in sourcePaths) {
                currentCoroutineContext().ensureActive()
                val fileId = extractId(sourcePath)
                val meta = executeJsonRequest(
                    urlString = "$FILES_ENDPOINT/${urlEncode(fileId)}?fields=${urlEncode("parents,name,size")}",
                    method = "GET",
                    token = token,
                )
                val parentsArray = meta.optJSONArray("parents")
                val oldParents = buildList {
                    if (parentsArray != null) {
                        for (i in 0 until parentsArray.length()) {
                            val p = parentsArray.optString(i)
                            if (p.isNotEmpty()) add(p)
                        }
                    }
                }.joinToString(",")

                val urlBuilder = StringBuilder("$FILES_ENDPOINT/${urlEncode(fileId)}")
                    .append("?addParents=").append(urlEncode(targetId))
                if (oldParents.isNotEmpty()) {
                    urlBuilder.append("&removeParents=").append(urlEncode(oldParents))
                }

                executeJsonRequest(
                    urlString = urlBuilder.toString(),
                    method = "PATCH",
                    token = token,
                    body = JSONObject(),
                )
                onProgress?.invoke(
                    VfsTransferUpdate(
                        currentFileName = meta.optString("name", fileId),
                        bytesDelta = meta.optString("size", "0").toLongOrNull() ?: 0L,
                        itemsDelta = 1,
                    ),
                )
            }
        }

    private suspend fun copyDriveNode(
        token: String,
        sourcePath: String,
        targetParentId: String,
        onProgress: ((VfsTransferUpdate) -> Unit)? = null,
    ) {
        currentCoroutineContext().ensureActive()
        val node = stat(sourcePath) ?: throw IOException("Quelle nicht gefunden: $sourcePath")
        if (node.isDirectory) {
            val newFolderId = createDriveFolder(token, targetParentId, node.name)
            val children = list(sourcePath, showHidden = true)
            if (children.isEmpty()) {
                onProgress?.invoke(VfsTransferUpdate(currentFileName = node.name, itemsDelta = 1))
                return
            }
            for (child in children) {
                copyDriveNode(token, child.path, newFolderId, onProgress)
            }
        } else {
            val fileId = extractId(sourcePath)
            val body = JSONObject().put("parents", JSONArray().put(targetParentId))
            executeJsonRequest(
                urlString = "$FILES_ENDPOINT/${urlEncode(fileId)}/copy",
                method = "POST",
                token = token,
                body = body,
            )
            onProgress?.invoke(
                VfsTransferUpdate(
                    currentFileName = node.name,
                    bytesDelta = node.sizeBytes,
                    itemsDelta = 1,
                ),
            )
        }
    }

    private suspend fun uploadLocalNode(
        localFile: File,
        targetFolderPath: String,
        onProgress: ((VfsTransferUpdate) -> Unit)? = null,
    ) {
        currentCoroutineContext().ensureActive()
        if (!localFile.exists()) {
            throw IOException("Lokale Quelle nicht gefunden: ${localFile.absolutePath}")
        }
        if (localFile.isDirectory) {
            val token = requireToken()
            val newFolderId = createDriveFolder(token, extractId(targetFolderPath), localFile.name)
            val children = localFile.listFiles()
            if (children.isNullOrEmpty()) {
                onProgress?.invoke(VfsTransferUpdate(currentFileName = localFile.name, itemsDelta = 1))
                return
            }
            for (child in children) {
                uploadLocalNode(child, "$SCHEME_PREFIX$newFolderId", onProgress)
            }
        } else {
            uploadFile(localFile, targetFolderPath, onProgress).getOrThrow()
        }
    }

    private fun createDriveFolder(token: String, parentId: String, name: String): String {
        val body = JSONObject().apply {
            put("name", name)
            put("mimeType", FOLDER_MIME_TYPE)
            put("parents", JSONArray().put(parentId))
        }
        val response = executeJsonRequest(
            urlString = FILES_ENDPOINT,
            method = "POST",
            token = token,
            body = body,
        )
        return response.optString("id", "")
    }

    private fun fetchChildCounts(
        folderIds: List<String>,
        showHidden: Boolean,
        token: String,
    ): Map<String, Int> {
        if (folderIds.isEmpty()) return emptyMap()
        val counts = HashMap<String, Int>(folderIds.size)
        folderIds.forEach { counts[it] = 0 }
        val fields = urlEncode("nextPageToken,files(name,parents)")
        folderIds.chunked(25).forEach { chunk ->
            val parentsExpr = chunk.joinToString(" or ") { "'$it' in parents" }
            val query = "($parentsExpr) and trashed = false"
            val encodedQuery = urlEncode(query)
            var pageToken: String? = null
            do {
                val urlBuilder = StringBuilder(FILES_ENDPOINT)
                    .append("?q=").append(encodedQuery)
                    .append("&fields=").append(fields)
                    .append("&pageSize=1000")
                if (!pageToken.isNullOrEmpty()) {
                    urlBuilder.append("&pageToken=").append(urlEncode(pageToken))
                }
                val json = executeJsonRequest(urlBuilder.toString(), "GET", token)
                val filesArray = json.optJSONArray("files")
                if (filesArray != null) {
                    for (i in 0 until filesArray.length()) {
                        val item = filesArray.getJSONObject(i)
                        val name = item.optString("name", "")
                        if (!showHidden && name.startsWith(".")) continue
                        val parents = item.optJSONArray("parents") ?: continue
                        for (p in 0 until parents.length()) {
                            val pid = parents.optString(p)
                            if (pid in counts) {
                                counts[pid] = (counts[pid] ?: 0) + 1
                            }
                        }
                    }
                }
                pageToken = json.optString("nextPageToken", "").takeIf { it.isNotEmpty() }
            } while (pageToken != null)
        }
        return counts
    }

    private suspend fun requireToken(): String =
        authManager.getAccessToken(context)
            ?: throw IOException("Nicht bei Google Drive angemeldet.")

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

    private fun openConnection(urlString: String, method: String, token: String): HttpURLConnection {
        val conn = URL(urlString).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
        } catch (_: ProtocolException) {
            conn.requestMethod = "POST"
            conn.setRequestProperty("X-HTTP-Method-Override", method)
        }
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("Accept", "application/json")
        return conn
    }

    private fun executeJsonRequest(
        urlString: String,
        method: String,
        token: String,
        body: JSONObject? = null,
    ): JSONObject {
        val conn = openConnection(urlString, method, token)
        try {
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                conn.outputStream.use { out ->
                    out.write(body.toString().toByteArray(Charsets.UTF_8))
                }
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IOException(readErrorMessage(conn, code))
            }
            val responseText = conn.inputStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            return if (responseText.isBlank()) JSONObject() else JSONObject(responseText)
        } finally {
            conn.disconnect()
        }
    }

    private fun readErrorMessage(conn: HttpURLConnection, code: Int): String {
        val errText = runCatching {
            conn.errorStream?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
        return if (errText.isNullOrBlank()) {
            "Google-Drive-API-Fehler: HTTP $code"
        } else {
            "Google-Drive-API-Fehler (HTTP $code): $errText"
        }
    }

    private fun JSONObject.toVfsNode(parentPath: String?): VfsNode {
        val id = optString("id", "")
        val name = optString("name", id)
        val rawMime = optString("mimeType", "")
        val isDir = rawMime == FOLDER_MIME_TYPE
        val normalizedMime = if (isDir) {
            "inode/directory"
        } else {
            MimeNormalizer.normalizeMime(name, rawMime.ifBlank { null })
        }
        val category = if (isDir) {
            FileCategory.DIRECTORY
        } else {
            MimeNormalizer.categorize(name, false, normalizedMime)
        }
        val sizeBytes = if (isDir) 0L else optString("size", "0").toLongOrNull() ?: 0L
        val modifiedTime = optString("modifiedTime", "")
        val lastModifiedMs = if (modifiedTime.isNotEmpty()) {
            runCatching { Instant.parse(modifiedTime).toEpochMilli() }.getOrDefault(0L)
        } else {
            0L
        }

        val thumbnailLink = optString("thumbnailLink", "").takeIf { it.isNotBlank() }

        return VfsNode(
            id = id,
            name = name,
            path = "$SCHEME_PREFIX$id",
            parentPath = parentPath,
            locationType = LocationType.GOOGLE_DRIVE,
            isDirectory = isDir,
            sizeBytes = sizeBytes,
            lastModifiedMs = lastModifiedMs,
            mimeType = normalizedMime,
            category = category,
            thumbnailUrl = thumbnailLink,
        )
    }

    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name())

    private companion object {
        const val SCHEME_PREFIX = "gdrive://"
        const val ROOT_ID = "root"
        const val FOLDER_MIME_TYPE = "application/vnd.google-apps.folder"
        const val FILES_ENDPOINT = "https://www.googleapis.com/drive/v3/files"
        const val UPLOAD_ENDPOINT = "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id,size"
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
        const val TRANSFER_BUFFER_SIZE = 64 * 1024
    }
}
