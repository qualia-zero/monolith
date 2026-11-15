package com.monolith.core.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.monolith.core.common.MonolithDiagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Thumbnail extraction and scaling engine for local audio/video media.
 */
object ThumbnailEngine {

    const val ART_MAX_LONG = 854
    const val ART_MAX_SHORT = 480
    const val ART_SIZE_SMALL = 192

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
        if (uriString.isBlank() || isRemoteUri(uriString)) return@withContext null

        val retriever = MediaMetadataRetriever()
        try {
            setDataSource(context, retriever, uriString)

            var raw: Bitmap? = retriever.embeddedPicture?.let { bytes ->
                val opts = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            }

            if (raw == null) {
                raw = retriever.frameAtTime
                if (raw != null && !isNonBlackBitmap(raw)) {
                    raw.recycle()
                    raw = null
                }
                if (raw == null && isVideo) {
                    val fiveSec = retriever.getFrameAtTime(
                        5_000_000L,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    )
                    if (fiveSec != null && isNonBlackBitmap(fiveSec)) {
                        raw = fiveSec
                    } else {
                        fiveSec?.recycle()
                        val tenSec = retriever.getFrameAtTime(
                            10_000_000L,
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        )
                        if (tenSec != null) {
                            raw = tenSec
                        }
                    }
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
}
