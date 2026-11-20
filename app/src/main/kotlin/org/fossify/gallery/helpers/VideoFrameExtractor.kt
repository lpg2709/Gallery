package org.fossify.gallery.helpers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.File
import java.util.Locale

object VideoFrameExtractor {
    const val JPEG_QUALITY = 95

    fun extract(context: Context, source: Uri, positionMs: Long): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            openSource(retriever, context, source)
            val timeUs = clampedTimeUs(retriever, positionMs)
            val frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                ?: retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: return null
            rotateIfStillRaw(retriever, frame)
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    fun frameBaseName(displayName: String): String {
        val trimmed = displayName.substringAfterLast('/').substringAfterLast(':')
        val withoutExtension = trimmed.substringBeforeLast('.', trimmed).ifBlank { "frame" }
        return withoutExtension.replace(INVALID_FILENAME_CHARS, "_")
    }

    fun frameStamp(positionMs: Long): String {
        val totalSeconds = positionMs.coerceAtLeast(0L) / 1000L
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            "%dh%02dm%02ds".format(Locale.US, hours, minutes, seconds)
        } else {
            "%02dm%02ds".format(Locale.US, minutes, seconds)
        }
    }

    fun videoFrameUri(pathOrUri: String): Uri {
        return if (pathOrUri.startsWith("content://") || pathOrUri.startsWith("file://")) {
            Uri.parse(pathOrUri)
        } else {
            Uri.fromFile(File(pathOrUri))
        }
    }

    /** Extra clockwise turn on top of the display-oriented frame. 0° returns [source]. */
    fun rotate(source: Bitmap, degrees: Int): Bitmap {
        val turns = ((degrees % 360) + 360) % 360
        if (turns == 0 || source.isRecycled) {
            return source
        }
        val matrix = Matrix().apply { postRotate(turns.toFloat()) }
        val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        if (rotated != source) {
            source.recycle()
        }
        return rotated
    }

    fun frameStepMs(context: Context, source: Uri): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            openSource(retriever, context, source)
            val fps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                ?.toFloatOrNull()
                ?.takeIf { it >= 1f }
                ?: DEFAULT_FRAME_RATE
            (1000f / fps).toLong().coerceAtLeast(1L)
        } catch (_: Exception) {
            (1000f / DEFAULT_FRAME_RATE).toLong()
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    private const val DEFAULT_FRAME_RATE = 30f
}

private val INVALID_FILENAME_CHARS = Regex("""[\\/:*?"<>|]""")

private fun openSource(retriever: MediaMetadataRetriever, context: Context, source: Uri) {
    try {
        retriever.setDataSource(context, source)
    } catch (first: Exception) {
        val path = source.path
        if (path.isNullOrEmpty() || source.scheme == "content") {
            throw first
        }
        retriever.setDataSource(path)
    }
}

private fun clampedTimeUs(retriever: MediaMetadataRetriever, positionMs: Long): Long {
    val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
    val clampedMs = if (durationMs != null && durationMs > 0L) {
        positionMs.coerceIn(0L, durationMs)
    } else {
        positionMs.coerceAtLeast(0L)
    }
    return clampedMs * 1000L
}

/**
 * [MediaMetadataRetriever] sometimes returns the coded frame and sometimes a frame
 * already turned upright. Width and height still match the unrotated metadata only
 * in the first case. A 180° turn keeps the same size, so it is left to the platform.
 */
private fun rotateIfStillRaw(retriever: MediaMetadataRetriever, frame: Bitmap): Bitmap {
    val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
        ?.toIntOrNull() ?: 0
    val degrees = ((rotation % 360) + 360) % 360
    if (degrees != 90 && degrees != 270) {
        return frame
    }

    val rawWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
    val rawHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
    if (rawWidth <= 0 || rawHeight <= 0 || frame.width != rawWidth || frame.height != rawHeight) {
        return frame
    }

    val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
    val rotated = Bitmap.createBitmap(frame, 0, 0, frame.width, frame.height, matrix, true)
    if (rotated != frame) {
        frame.recycle()
    }
    return rotated
}
