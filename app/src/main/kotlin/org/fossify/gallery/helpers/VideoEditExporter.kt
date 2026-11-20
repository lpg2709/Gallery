package org.fossify.gallery.helpers

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File

@UnstableApi
class VideoEditExporter(
    context: Context,
    source: Uri,
    rotationDegrees: Int,
    muted: Boolean,
    clipStartMs: Long,
    clipEndMs: Long,
    outputFile: File,
    private val listener: Listener,
) {
    fun interface Listener {
        fun onFinished(success: Boolean, error: ExportException?)
    }

    private var cancelRequested = false
    private val transformer: Transformer
    private val edited: EditedMediaItem
    private val outputPath: String = outputFile.absolutePath

    init {
        val videoEffects = buildList<Effect> {
            val turns = ((rotationDegrees % 360) + 360) % 360
            if (turns != 0) {
                add(
                    ScaleAndRotateTransformation.Builder()
                        .setRotationDegrees(turns.toFloat())
                        .build(),
                )
            }
        }
        val mediaItem = MediaItem.Builder().setUri(source).apply {
            if (clipStartMs > 0L || clipEndMs >= 0L) {
                val end = if (clipEndMs < 0L) C.TIME_END_OF_SOURCE else clipEndMs
                setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(clipStartMs.coerceAtLeast(0L))
                        .setEndPositionMs(end)
                        .setStartsAtKeyFrame(false)
                        .build(),
                )
            }
        }.build()
        edited = EditedMediaItem.Builder(mediaItem)
            .setRemoveAudio(muted)
            .setEffects(Effects(emptyList<AudioProcessor>(), videoEffects))
            .build()
        transformer = Transformer.Builder(context)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    listener.onFinished(success = true, error = null)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException,
                ) {
                    val reported = if (cancelRequested) null else exportException
                    listener.onFinished(success = false, error = reported)
                }
            })
            .build()
    }

    fun start() {
        transformer.start(edited, outputPath)
    }

    fun cancel() {
        cancelRequested = true
        transformer.cancel()
    }
}
