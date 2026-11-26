package com.monolith.core.vfs

import android.content.Context
import com.monolith.core.common.MonolithDiagnostics
import com.monolith.core.model.VfsNode
import kotlinx.coroutines.runBlocking
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

object SmbStreamProxy {
    private data class CachedStat(
        val node: VfsNode,
        val cachedAtMs: Long,
    )

    private val registrations = ConcurrentHashMap<String, String>()
    private val pathToToken = ConcurrentHashMap<String, String>()
    private val statCache = ConcurrentHashMap<String, CachedStat>()
    private val executor = Executors.newCachedThreadPool()
    private const val STAT_CACHE_TTL_MS = 60_000L

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var serverPort: Int = -1

    fun registerUrl(context: Context, smbPath: String, fileName: String): String {
        appContext = context.applicationContext
        val port = ensureStarted()
        val token = pathToToken.getOrPut(smbPath) {
            UUID.randomUUID().toString().replace("-", "").also { newToken ->
                registrations[newToken] = smbPath
            }
        }
        registrations[token] = smbPath
        val encodedName = URLEncoder.encode(fileName.ifBlank { "media" }, Charsets.UTF_8.name())
            .replace("+", "%20")
        return "http://127.0.0.1:$port/stream/$token/$encodedName"
    }

    fun resolveSmbPath(streamUrl: String): String? {
        val path = streamUrl.substringAfter("://", "").substringAfter('/', "").substringBefore('?')
        val segments = path.trim('/').split('/')
        if (segments.size < 2 || segments[0] != "stream") return null
        return registrations[segments[1]]
    }

    @Synchronized
    private fun ensureStarted(): Int {
        if (serverSocket != null && serverPort > 0) return serverPort
        val socket = ServerSocket(0, 64, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
        serverSocket = socket
        serverPort = socket.localPort
        Thread(
            {
                while (!socket.isClosed) {
                    try {
                        val client = socket.accept()
                        executor.submit { handleClient(client) }
                    } catch (_: IOException) {
                        break
                    }
                }
            },
            "SmbStreamProxy-Accept",
        ).apply { isDaemon = true }.start()
        return serverPort
    }

    private fun handleClient(client: Socket) {
        client.use { sock ->
            try {
                sock.soTimeout = 30_000
                val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.US_ASCII))
                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(' ')
                if (parts.size < 2) return
                val method = parts[0].uppercase()
                val requestPath = parts[1]

                var rangeHeader: String? = null
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("Range:", ignoreCase = true)) {
                        rangeHeader = line.substringAfter(':').trim()
                    }
                }

                val pathSegments = requestPath.substringBefore('?').trim('/').split('/')
                if (pathSegments.size < 2 || pathSegments[0] != "stream") {
                    writeStatus(sock.getOutputStream(), "404 Not Found")
                    return
                }
                val token = pathSegments[1]
                val smbPath = registrations[token] ?: run {
                    writeStatus(sock.getOutputStream(), "404 Not Found")
                    return
                }
                val ctx = appContext ?: run {
                    writeStatus(sock.getOutputStream(), "500 Internal Server Error")
                    return
                }

                val provider = SmbVfsProvider(ctx)
                val now = System.currentTimeMillis()
                val cached = statCache[token]?.takeIf { now - it.cachedAtMs < STAT_CACHE_TTL_MS }
                val node = cached?.node ?: runBlocking { provider.stat(smbPath) }?.also {
                    statCache[token] = CachedStat(it, now)
                } ?: run {
                    writeStatus(sock.getOutputStream(), "404 Not Found")
                    return
                }
                val totalSize = node.sizeBytes.coerceAtLeast(0L)
                val mime = node.mimeType.ifBlank { "application/octet-stream" }

                var start = 0L
                var end = if (totalSize > 0L) totalSize - 1L else -1L
                var isRange = false

                if (rangeHeader != null && rangeHeader.startsWith("bytes=", ignoreCase = true) && totalSize > 0L) {
                    val spec = rangeHeader.substringAfter("bytes=").substringBefore(',').trim()
                    val dashIdx = spec.indexOf('-')
                    if (dashIdx >= 0) {
                        val startStr = spec.substring(0, dashIdx).trim()
                        val endStr = spec.substring(dashIdx + 1).trim()
                        if (startStr.isEmpty() && endStr.isNotEmpty()) {
                            val suffix = endStr.toLongOrNull() ?: 0L
                            start = (totalSize - suffix).coerceAtLeast(0L)
                            end = totalSize - 1L
                            isRange = true
                        } else if (startStr.isNotEmpty()) {
                            start = startStr.toLongOrNull() ?: 0L
                            end = endStr.toLongOrNull()?.coerceAtMost(totalSize - 1L) ?: (totalSize - 1L)
                            isRange = true
                        }
                    }
                }

                if (isRange && (start >= totalSize || start > end)) {
                    val out = sock.getOutputStream()
                    out.write(
                        ("HTTP/1.1 416 Range Not Satisfiable\r\n" +
                            "Content-Range: bytes */$totalSize\r\n" +
                            "Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII),
                    )
                    out.flush()
                    return
                }

                val contentLength = if (totalSize > 0L) (end - start + 1L) else -1L
                val out = sock.getOutputStream()
                val headers = buildString {
                    if (isRange) {
                        append("HTTP/1.1 206 Partial Content\r\n")
                        append("Content-Range: bytes $start-$end/$totalSize\r\n")
                    } else {
                        append("HTTP/1.1 200 OK\r\n")
                    }
                    append("Content-Type: $mime\r\n")
                    append("Accept-Ranges: bytes\r\n")
                    if (contentLength >= 0L) {
                        append("Content-Length: $contentLength\r\n")
                    }
                    append("Connection: close\r\n\r\n")
                }
                out.write(headers.toByteArray(Charsets.US_ASCII))
                out.flush()

                if (method == "HEAD") return

                runBlocking { provider.openInputStream(smbPath, offset = start) }.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var remaining = if (contentLength >= 0L) contentLength else Long.MAX_VALUE
                    while (remaining > 0L) {
                        val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                        val read = input.read(buffer, 0, toRead)
                        if (read == -1) break
                        out.write(buffer, 0, read)
                        if (contentLength >= 0L) {
                            remaining -= read
                        }
                    }
                    out.flush()
                }
            } catch (_: IOException) {
                // Client disconnected (e.g., player seek abort) - ignore cleanly.
            } catch (t: Throwable) {
                MonolithDiagnostics.logWarn("SmbStreamProxy", "Stream error: ${t.message}")
            }
        }
    }

    private fun writeStatus(out: OutputStream, status: String) {
        runCatching {
            out.write("HTTP/1.1 $status\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
            out.flush()
        }
    }
}
