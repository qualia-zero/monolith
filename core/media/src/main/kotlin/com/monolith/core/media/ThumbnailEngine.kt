package com.monolith.core.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.util.Size
import com.monolith.core.common.MonolithDiagnostics
import com.monolith.core.model.FileCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLEncoder
import kotlin.math.roundToInt

/**
 * Thumbnail extraction and scaling engine for local audio/video media.
 */
object ThumbnailEngine {

    const val ART_MAX_LONG = 854
    const val ART_MAX_SHORT = 480
    const val ART_SIZE_SMALL = 192
    const val LIST_THUMB_DEFAULT_PX = 128

    private const val LIST_CACHE_MAX_BYTES = 24 * 1024 * 1024 // 24 MiB

    private val listThumbnailCache = object : LruCache<String, Bitmap>(LIST_CACHE_MAX_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            value.allocationByteCount.coerceAtLeast(1)
    }

    /**
     * Returns a cached list thumbnail immediately if present, or null.
     */
    fun getCachedListThumbnail(
        path: String,
        lastModifiedMs: Long = 0L,
        targetPx: Int = LIST_THUMB_DEFAULT_PX,
    ): Bitmap? {
        if (path.isBlank() || targetPx <= 0) return null
        return listThumbnailCache.get(listCacheKey(path, lastModifiedMs, targetPx))
    }

    private val remoteExtractSemaphore = Semaphore(2)

    @Volatile
    var cloudTokenProvider: (() -> String?)? = null

    /**
     * Loads, downsamples, and caches a square-cropped thumbnail for list rows on [Dispatchers.IO].
     */
    suspend fun loadListThumbnail(
        context: Context,
        path: String,
        category: FileCategory,
        lastModifiedMs: Long = 0L,
        targetPx: Int = LIST_THUMB_DEFAULT_PX,
        thumbnailUrl: String? = null,
    ): Bitmap? = withContext(Dispatchers.IO) {
        if (path.isBlank() || targetPx <= 0) return@withContext null
        val isCloudThumb = !thumbnailUrl.isNullOrBlank()
        val isDriveVideo = path.startsWith("gdrive://") && category == FileCategory.VIDEO
        if (!isCloudThumb && !isDriveVideo) {
            if (isRemoteUri(path) || path.startsWith("gdrive://")) {
                MonolithDiagnostics.logWarn("ThumbnailEngine", "Cloud item has no thumbnailUrl: $path (category=$category)")
                return@withContext null
            }
            if (category != FileCategory.IMAGE &&
                category != FileCategory.VIDEO &&
                category != FileCategory.AUDIO
            ) {
                return@withContext null
            }
        }

        val cacheKey = listCacheKey(path, lastModifiedMs, targetPx)
        listThumbnailCache.get(cacheKey)?.let { return@withContext it }

        val decoded = try {
            val remoteThumb = if (isCloudThumb) decodeRemoteThumbnail(thumbnailUrl!!) else null
            when {
                remoteThumb != null -> remoteThumb
                isDriveVideo -> remoteExtractSemaphore.withPermit {
                    decodeDriveVideoFallback(path, targetPx)
                }
                else -> when (category) {
                    FileCategory.IMAGE -> decodeSampledImage(context, path, targetPx)
                    FileCategory.VIDEO -> decodeVideoListThumbnail(context, path, targetPx)
                    FileCategory.AUDIO -> decodeAudioCoverThumbnail(context, path, targetPx)
                    else -> null
                }
            }
        } catch (t: Throwable) {
            MonolithDiagnostics.logWarn("ThumbnailEngine", "List thumbnail load failed for $path: ${t.message}")
            null
        } ?: return@withContext null

        val prepared = centerCropScale(decoded, targetPx)
        if (prepared !== decoded) {
            decoded.recycle()
        }
        listThumbnailCache.put(cacheKey, prepared)
        prepared
    }

    private fun decodeRemoteThumbnail(urlString: String): Bitmap? {
        fun fetch(token: String?): Pair<Int, Bitmap?> {
            val conn = (java.net.URL(urlString).openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 5_000
                readTimeout = 5_000
                instanceFollowRedirects = true
                if (!token.isNullOrBlank()) {
                    setRequestProperty("Authorization", "Bearer $token")
                }
            }
            return try {
                val code = conn.responseCode
                if (code in 200..299) {
                    val bmp = conn.inputStream.use { stream ->
                        BitmapFactory.decodeStream(stream)
                    }
                    code to bmp
                } else {
                    code to null
                }
            } finally {
                conn.disconnect()
            }
        }

        val (code, first) = fetch(null)
        if (first != null) return first
        if (code == 200 || code == 302 || code == 401 || code == 403) {
            val token = cloudTokenProvider?.invoke()
            if (!token.isNullOrBlank()) {
                val (retryCode, retryBmp) = fetch(token)
                if (retryBmp == null) {
                    MonolithDiagnostics.logWarn("ThumbnailEngine", "Remote thumb retry failed ($retryCode) for $urlString")
                }
                return retryBmp
            }
        }
        MonolithDiagnostics.logWarn("ThumbnailEngine", "Remote thumb decode failed (http=$code) for $urlString")
        return null
    }

    private fun listCacheKey(path: String, lastModifiedMs: Long, targetPx: Int): String =
        "$path:$lastModifiedMs:$targetPx"

    /**
     * Samples a 3x3 interior grid (25%, 50%, 75% width/height) to reject near-black frames.
     */
    fun isNonBlackBitmap(bitmap: Bitmap): Boolean {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return false

        val xs = intArrayOf(
            (w / 4).coerceIn(0, w - 1),
            (w / 2).coerceIn(0, w - 1),
            ((w * 3) / 4).coerceIn(0, w - 1),
        )
        val ys = intArrayOf(
            (h / 4).coerceIn(0, h - 1),
            (h / 2).coerceIn(0, h - 1),
            ((h * 3) / 4).coerceIn(0, h - 1),
        )

        for (y in ys) {
            for (x in xs) {
                val pixel = bitmap.getPixel(x, y)
                if ((pixel and 0x00F0F0F0) != 0) {
                    return true
                }
            }
        }
        return false
    }

    /**
     * Loads and scales artwork or a representative non-black video frame on [Dispatchers.IO].
     */
    suspend fun loadMediaThumbnail(
        context: Context,
        uriString: String,
        isVideo: Boolean,
    ): Bitmap? = withContext(Dispatchers.IO) {
        if (uriString.isBlank()) return@withContext null
        val isRemote = isRemoteUri(uriString)
        val isDriveStream = isRemote && uriString.contains("googleapis.com/drive/")
        if (isRemote && !isDriveStream) return@withContext null

        val retriever = MediaMetadataRetriever()
        try {
            if (isDriveStream) {
                val token = cloudTokenProvider?.invoke()?.takeIf { it.isNotBlank() }
                    ?: return@withContext null
                retriever.setDataSource(uriString, mapOf("Authorization" to "Bearer $token"))
            } else {
                setDataSource(context, retriever, uriString)
            }

            var raw: Bitmap? = retriever.embeddedPicture?.let { bytes ->
                val opts = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            }

            if (raw == null) {
                raw = if (isVideo) {
                    extractNonBlackVideoFrame(retriever)
                } else {
                    retriever.frameAtTime
                }
            }

            val source = raw ?: return@withContext null
            val argb = ensureArgb8888(source)
            if (argb !== source) {
                source.recycle()
            }

            val maxW = if (argb.width >= argb.height) ART_MAX_LONG else ART_MAX_SHORT
            val maxH = if (argb.width >= argb.height) ART_MAX_SHORT else ART_MAX_LONG
            val scaled = scaleAspectPreserving(argb, maxW, maxH)
            if (scaled !== argb) {
                argb.recycle()
            }
            scaled
        } catch (t: Throwable) {
            MonolithDiagnostics.logWarn("ThumbnailEngine", "Thumbnail load failed for $uriString: ${t.message}")
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * Scales a bitmap to at most [ART_SIZE_SMALL] x [ART_SIZE_SMALL] preserving aspect ratio.
     */
    fun scaleToSmallIcon(bitmap: Bitmap): Bitmap {
        val argb = ensureArgb8888(bitmap)
        val scaled = scaleAspectPreserving(argb, ART_SIZE_SMALL, ART_SIZE_SMALL)
        if (argb !== bitmap && argb !== scaled) {
            argb.recycle()
        }
        return scaled
    }

    private fun setDataSource(
        context: Context,
        retriever: MediaMetadataRetriever,
        uriString: String,
    ) {
        when {
            uriString.startsWith("content://") -> {
                retriever.setDataSource(context, Uri.parse(uriString))
            }
            uriString.startsWith("file://") -> {
                val path = Uri.parse(uriString).path ?: uriString
                retriever.setDataSource(path)
            }
            else -> retriever.setDataSource(uriString)
        }
    }

    private fun isRemoteUri(uriString: String): Boolean {
        val lower = uriString.lowercase()
        return lower.startsWith("http://") ||
            lower.startsWith("https://") ||
            lower.startsWith("rtsp://") ||
            lower.startsWith("rtmp://")
    }

    private fun ensureArgb8888(bitmap: Bitmap): Bitmap {
        if (bitmap.config == Bitmap.Config.ARGB_8888) return bitmap
        return bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: bitmap
    }

    private fun scaleAspectPreserving(src: Bitmap, maxW: Int, maxH: Int): Bitmap {
        val srcW = src.width
        val srcH = src.height
        if (srcW <= 0 || srcH <= 0) return src
        if (srcW <= maxW && srcH <= maxH) return src

        val scale = minOf(maxW.toFloat() / srcW.toFloat(), maxH.toFloat() / srcH.toFloat())
        val targetW = (srcW * scale).roundToInt().coerceAtLeast(1)
        val targetH = (srcH * scale).roundToInt().coerceAtLeast(1)
        val filter = srcW >= 128 && srcH >= 128
        return Bitmap.createScaledBitmap(src, targetW, targetH, filter)
    }

    private fun decodeSampledImage(context: Context, path: String, targetPx: Int): Bitmap? {
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val isContentUri = path.startsWith("content://")
        if (isContentUri) {
            val uri = Uri.parse(path)
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, boundsOpts)
            } ?: return null
        } else {
            val localPath = resolveLocalFilePath(path)
            BitmapFactory.decodeFile(localPath, boundsOpts)
        }

        if (boundsOpts.outWidth <= 0 || boundsOpts.outHeight <= 0) return null

        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(boundsOpts.outWidth, boundsOpts.outHeight, targetPx, targetPx)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }

        return if (isContentUri) {
            val uri = Uri.parse(path)
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOpts)
            }
        } else {
            val localPath = resolveLocalFilePath(path)
            BitmapFactory.decodeFile(localPath, decodeOpts)
        }
    }

    private fun decodeVideoListThumbnail(context: Context, path: String, targetPx: Int): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            setDataSource(context, retriever, path)
            decodeEmbeddedPicture(retriever, targetPx) ?: extractNonBlackVideoFrame(retriever)
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * Fallback for Drive videos when Drive's thumbnailLink is missing or fails:
     * checks embedded cover art first, then extracts a 5s/10s/15s frame.
     */
    private fun decodeDriveVideoFallback(
        gdrivePath: String,
        targetPx: Int,
    ): Bitmap? {
        val fileId = gdrivePath.removePrefix("gdrive://").trim()
        if (fileId.isEmpty()) return null
        val token = cloudTokenProvider?.invoke()?.takeIf { it.isNotBlank() } ?: return null

        val encodedId = URLEncoder.encode(fileId, Charsets.UTF_8.name())
        val streamUrl = "https://www.googleapis.com/drive/v3/files/$encodedId?alt=media"
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(streamUrl, mapOf("Authorization" to "Bearer $token"))
            decodeEmbeddedPicture(retriever, targetPx) ?: extractNonBlackVideoFrame(retriever)
        } catch (t: Throwable) {
            MonolithDiagnostics.logWarn("ThumbnailEngine", "Drive video fallback failed for $gdrivePath: ${t.message}")
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun decodeEmbeddedPicture(retriever: MediaMetadataRetriever, targetPx: Int): Bitmap? {
        val bytes = retriever.embeddedPicture ?: return null
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOpts)
        if (boundsOpts.outWidth <= 0 || boundsOpts.outHeight <= 0) return null
        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(boundsOpts.outWidth, boundsOpts.outHeight, targetPx, targetPx)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
    }

    private fun extractNonBlackVideoFrame(retriever: MediaMetadataRetriever): Bitmap? {
        val candidateTimesUs = longArrayOf(5_000_000L, 10_000_000L, 15_000_000L, 1_000_000L)
        var fallbackFrame: Bitmap? = null

        for (timeUs in candidateTimesUs) {
            val frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            if (frame != null) {
                if (isNonBlackBitmap(frame)) {
                    fallbackFrame?.recycle()
                    return frame
                }
                if (fallbackFrame == null) {
                    fallbackFrame = frame
                } else {
                    frame.recycle()
                }
            }
        }

        val defaultFrame = retriever.frameAtTime
        if (defaultFrame != null) {
            if (isNonBlackBitmap(defaultFrame) || fallbackFrame == null) {
                fallbackFrame?.recycle()
                return defaultFrame
            }
            defaultFrame.recycle()
        }
        return fallbackFrame
    }

    private fun decodeAudioCoverThumbnail(context: Context, path: String, targetPx: Int): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            setDataSource(context, retriever, path)
            val bytes = retriever.embeddedPicture ?: return null
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOpts)
            if (boundsOpts.outWidth <= 0 || boundsOpts.outHeight <= 0) return null

            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(boundsOpts.outWidth, boundsOpts.outHeight, targetPx, targetPx)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun resolveLocalFilePath(path: String): String =
        if (path.startsWith("file://")) Uri.parse(path).path ?: path else path

    private fun calculateInSampleSize(
        rawWidth: Int,
        rawHeight: Int,
        reqWidth: Int,
        reqHeight: Int,
    ): Int {
        var inSampleSize = 1
        if (rawHeight > reqHeight || rawWidth > reqWidth) {
            val halfHeight = rawHeight / 2
            val halfWidth = rawWidth / 2
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    /**
     * Scales [src] so its shorter edge matches [targetPx] (when larger) and center-crops to a square.
     */
    private fun centerCropScale(src: Bitmap, targetPx: Int): Bitmap {
        val argb = ensureArgb8888(src)
        val w = argb.width
        val h = argb.height
        if (w <= 0 || h <= 0) return argb

        val minEdge = minOf(w, h)
        val scaled = if (minEdge > targetPx) {
            val scale = targetPx.toFloat() / minEdge.toFloat()
            val scaledW = (w * scale).roundToInt().coerceAtLeast(targetPx)
            val scaledH = (h * scale).roundToInt().coerceAtLeast(targetPx)
            Bitmap.createScaledBitmap(argb, scaledW, scaledH, true)
        } else {
            argb
        }

        val cropSize = minOf(scaled.width, scaled.height, targetPx)
        val result = if (scaled.width == cropSize && scaled.height == cropSize) {
            scaled
        } else {
            val x = ((scaled.width - cropSize) / 2).coerceAtLeast(0)
            val y = ((scaled.height - cropSize) / 2).coerceAtLeast(0)
            Bitmap.createBitmap(scaled, x, y, cropSize, cropSize)
        }

        if (scaled !== argb && scaled !== result) {
            scaled.recycle()
        }
        if (argb !== src && argb !== result) {
            argb.recycle()
        }
        return result
    }
}
