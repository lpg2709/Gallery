@file:androidx.annotation.OptIn(markerClass = [UnstableApi::class])

package org.fossify.gallery.activities

import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.SeekBar
import androidx.appcompat.app.AlertDialog
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import org.fossify.commons.extensions.applyColorFilter
import org.fossify.commons.extensions.checkAppSideloading
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.getFileOutputStreamSync
import org.fossify.commons.extensions.getFilenameFromPath
import org.fossify.commons.extensions.getFilenameFromUri
import org.fossify.commons.extensions.getParentPath
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getRealPathFromURI
import org.fossify.commons.extensions.rescanPaths
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.REAL_FILE_PATH
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.gallery.R
import org.fossify.gallery.databinding.ActivityVideoEditBinding
import org.fossify.gallery.extensions.getFormattedDuration
import org.fossify.gallery.extensions.pickWritableExportPath
import org.fossify.gallery.extensions.saveVideoFrameBitmap
import org.fossify.gallery.helpers.VIDEO_EDIT_POSITION
import org.fossify.gallery.helpers.VideoEditExporter
import org.fossify.gallery.helpers.VideoFrameExtractor
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.math.min

class VideoEditActivity : SimpleActivity(), TextureView.SurfaceTextureListener {
    private val binding by viewBinding(ActivityVideoEditBinding::inflate)
    private val progressHandler = Handler(Looper.getMainLooper())
    private var exoPlayer: ExoPlayer? = null
    private var videoSurface: Surface? = null
    private var source: Uri? = null
    private var startPositionMs = 0L
    private var userRotation = 0
    private var muted = false
    private var trimVisible = false
    private var trimStart = 0f
    private var trimEnd = 1f
    private var videoWidth = 0
    private var videoHeight = 0
    private var frameStepMs = 33L
    private var userSeeking = false
    private var readyApplied = false
    private var exportCancelled = false
    private var exporter: VideoEditExporter? = null
    private var progressDialog: AlertDialog? = null
    private var laidOutWidth = 0
    private var laidOutHeight = 0
    private var laidOutRotation = -1
    private var laidOutScale = -1f

    private val progressRunnable = object : Runnable {
        override fun run() {
            updateProgress()
            progressHandler.postDelayed(this, PROGRESS_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupEdgeToEdge(padBottomSystem = listOf(binding.editActions))
        if (checkAppSideloading()) {
            return
        }

        val uri = intent.data
        if (uri == null) {
            toast(R.string.invalid_video_path)
            finish()
            return
        }

        source = uri
        startPositionMs = intent.getLongExtra(VIDEO_EDIT_POSITION, 0L).coerceAtLeast(0L)
        restoreState(savedInstanceState)
        setupMenu()
        setupControls()
        tintActions()
        binding.videoSurface.surfaceTextureListener = this
        binding.previewHolder.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> layoutPreview() }
        preparePlayer(uri)
        loadFrameStep(uri)
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.videoEditAppbar, NavigationIcon.Arrow)
        tintActions()
        progressHandler.removeCallbacks(progressRunnable)
        progressHandler.post(progressRunnable)
    }

    override fun onPause() {
        super.onPause()
        progressHandler.removeCallbacks(progressRunnable)
        pausePlayback()
    }

    override fun onDestroy() {
        exportCancelled = true
        exporter?.cancel()
        exporter = null
        progressDialog?.dismiss()
        progressDialog = null
        progressHandler.removeCallbacksAndMessages(null)
        exoPlayer?.clearVideoSurface()
        exoPlayer?.release()
        exoPlayer = null
        videoSurface?.release()
        videoSurface = null
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_ROTATION, userRotation)
        outState.putBoolean(STATE_MUTED, muted)
        outState.putBoolean(STATE_TRIM, trimVisible)
        outState.putFloat(STATE_TRIM_START, trimStart)
        outState.putFloat(STATE_TRIM_END, trimEnd)
        outState.putLong(STATE_POSITION, exoPlayer?.currentPosition ?: startPositionMs)
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        attachSurface()
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        exoPlayer?.clearVideoSurface()
        videoSurface?.release()
        videoSurface = null
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    private fun restoreState(savedInstanceState: Bundle?) {
        if (savedInstanceState == null) {
            return
        }
        userRotation = savedInstanceState.getInt(STATE_ROTATION)
        muted = savedInstanceState.getBoolean(STATE_MUTED)
        startPositionMs = savedInstanceState.getLong(STATE_POSITION, startPositionMs)
        trimVisible = savedInstanceState.getBoolean(STATE_TRIM)
        trimStart = savedInstanceState.getFloat(STATE_TRIM_START, 0f)
        trimEnd = savedInstanceState.getFloat(STATE_TRIM_END, 1f)
        if (trimVisible) {
            showTrim(trimStart, trimEnd)
        }
        updateMuteIcon()
    }

    private fun setupMenu() {
        binding.videoEditToolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.menu_save_video_edit) {
                saveVideo()
                true
            } else {
                false
            }
        }
    }

    private fun setupControls() {
        binding.videoCurrTime.text = 0L.getFormattedDuration()
        binding.videoDuration.text = 0L.getFormattedDuration()
        binding.framePrevious.setOnClickListener { stepFrame(forward = false) }
        binding.frameNext.setOnClickListener { stepFrame(forward = true) }
        binding.videoPlayOutline.setOnClickListener { playVideo() }
        binding.previewHolder.setOnClickListener { pausePlayback() }
        binding.trimOverlay.onRangeChanged = { start, end, movingStart, dragging ->
            trimStart = start
            trimEnd = end
            userSeeking = dragging
            seekToTrim(if (movingStart) start else end)
        }
        binding.actionRotate.setOnClickListener {
            userRotation = (userRotation + 90) % 360
            layoutPreview()
            tintActions()
        }
        binding.actionMute.setOnClickListener {
            muted = !muted
            exoPlayer?.volume = if (muted) 0f else 1f
            updateMuteIcon()
            tintActions()
        }
        binding.actionCrop.setOnClickListener { toggleTrim() }
        binding.actionExtract.setOnClickListener { extractFrame() }
        binding.videoSeekbar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    exoPlayer?.seekTo(progress.toLong())
                    binding.videoCurrTime.text = progress.toLong().getFormattedDuration()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                userSeeking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                userSeeking = false
            }
        })
        binding.framePrevious.applyColorFilter(android.graphics.Color.WHITE)
        binding.frameNext.applyColorFilter(android.graphics.Color.WHITE)
        updateMuteIcon()
    }

    private fun preparePlayer(uri: Uri) {
        exoPlayer = ExoPlayer.Builder(this)
            .setSeekParameters(SeekParameters.EXACT)
            .build()
            .also { player ->
                player.setMediaItem(MediaItem.fromUri(uri))
                player.playWhenReady = false
                player.volume = if (muted) 0f else 1f
                player.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY) {
                            onPlayerReady(player)
                        }
                        updatePlayButton()
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        updatePlayButton()
                    }

                    override fun onVideoSizeChanged(videoSize: VideoSize) {
                        storeVideoSize(videoSize)
                    }

                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        showErrorToast(error)
                    }
                })
                player.prepare()
            }
        if (binding.videoSurface.isAvailable) {
            attachSurface()
        }
    }

    private fun onPlayerReady(player: ExoPlayer) {
        val duration = player.duration.coerceAtLeast(0L)
        binding.videoDuration.text = duration.getFormattedDuration()
        binding.videoSeekbar.max = duration.coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(1)
        if (!readyApplied) {
            readyApplied = true
            val resumePlayback = player.playWhenReady
            if (startPositionMs > 0L && duration > 0L) {
                player.seekTo(startPositionMs.coerceAtMost(duration))
            }
            player.playWhenReady = resumePlayback
        }
        updateProgress()
        updatePlayButton()
    }

    private fun storeVideoSize(videoSize: VideoSize) {
        if (videoSize.width <= 0 || videoSize.height <= 0) {
            return
        }
        val ratio = videoSize.pixelWidthHeightRatio.takeIf { it > 0f } ?: 1f
        videoWidth = (videoSize.width * ratio).toInt().coerceAtLeast(1)
        videoHeight = videoSize.height.coerceAtLeast(1)
        laidOutWidth = 0
        layoutPreview()
    }

    private fun attachSurface() {
        val texture = binding.videoSurface.surfaceTexture ?: return
        val surface = Surface(texture)
        exoPlayer?.setVideoSurface(surface)
        videoSurface?.release()
        videoSurface = surface
    }

    private fun layoutPreview() {
        val holderW = binding.previewHolder.width
        val holderH = binding.previewHolder.height
        if (holderW == 0 || holderH == 0 || videoWidth == 0 || videoHeight == 0) {
            return
        }

        val fit = min(holderW / videoWidth.toFloat(), holderH / videoHeight.toFloat())
        val fittedW = (videoWidth * fit).toInt().coerceAtLeast(1)
        val fittedH = (videoHeight * fit).toInt().coerceAtLeast(1)
        val quarterTurn = userRotation % 180 != 0
        val scale = if (quarterTurn) {
            min(holderW / fittedH.toFloat(), holderH / fittedW.toFloat())
        } else {
            1f
        }
        val sameLayout = fittedW == laidOutWidth &&
            fittedH == laidOutHeight &&
            userRotation == laidOutRotation &&
            scale == laidOutScale
        if (sameLayout) {
            return
        }
        laidOutWidth = fittedW
        laidOutHeight = fittedH
        laidOutRotation = userRotation
        laidOutScale = scale

        binding.videoSurface.layoutParams = FrameLayout.LayoutParams(fittedW, fittedH, Gravity.CENTER)
        binding.videoSurface.pivotX = fittedW / 2f
        binding.videoSurface.pivotY = fittedH / 2f
        binding.videoSurface.rotation = userRotation.toFloat()
        binding.videoSurface.scaleX = scale
        binding.videoSurface.scaleY = scale
    }

    private fun playVideo() {
        val player = exoPlayer ?: return
        val atEnd = player.playbackState == Player.STATE_ENDED ||
            (player.duration > 0L && player.currentPosition >= player.duration)
        if (atEnd) {
            player.seekTo(0)
        }
        player.playWhenReady = true
        updatePlayButton()
    }

    private fun pausePlayback() {
        val player = exoPlayer ?: return
        if (!player.playWhenReady) {
            return
        }
        player.playWhenReady = false
        updatePlayButton()
    }

    private fun updatePlayButton() {
        val player = exoPlayer
        val ended = player?.playbackState == Player.STATE_ENDED
        val showPlay = player == null || ended || !player.playWhenReady
        binding.videoPlayOutline.visibility = if (showPlay) View.VISIBLE else View.GONE
        if (player?.playWhenReady == true && !ended) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun stepFrame(forward: Boolean) {
        val player = exoPlayer ?: return
        val duration = player.duration.takeIf { it > 0L } ?: Long.MAX_VALUE
        val delta = if (forward) frameStepMs else -frameStepMs
        val target = (player.currentPosition + delta).coerceIn(0L, duration)
        player.seekTo(target)
        binding.videoSeekbar.progress = target.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        binding.videoCurrTime.text = target.getFormattedDuration()
    }

    private fun updateProgress() {
        val player = exoPlayer ?: return
        if (userSeeking) {
            return
        }
        val position = player.currentPosition.coerceAtLeast(0L)
        if (player.duration > 0L) {
            binding.videoSeekbar.max = player.duration.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            binding.videoDuration.text = player.duration.getFormattedDuration()
        }
        binding.videoSeekbar.progress = position.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        binding.videoCurrTime.text = position.getFormattedDuration()
    }

    private fun updateMuteIcon() {
        val icon = if (muted) R.drawable.ic_vector_speaker_off else R.drawable.ic_vector_speaker_on
        binding.actionMuteIcon.setImageResource(icon)
    }

    private fun toggleTrim() {
        if (trimVisible) {
            trimVisible = false
            trimStart = 0f
            trimEnd = 1f
            userSeeking = false
            binding.trimOverlay.visibility = View.GONE
        } else {
            showTrim(0f, 1f)
        }
        tintActions()
    }

    private fun showTrim(start: Float, end: Float) {
        trimVisible = true
        trimStart = start
        trimEnd = end
        binding.trimOverlay.setRange(start, end)
        binding.trimOverlay.visibility = View.VISIBLE
    }

    private fun seekToTrim(fraction: Float) {
        val duration = exoPlayer?.duration?.takeIf { it > 0L } ?: return
        val position = (fraction * duration).toLong().coerceIn(0L, duration)
        exoPlayer?.seekTo(position)
        binding.videoSeekbar.progress = position.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        binding.videoCurrTime.text = position.getFormattedDuration()
    }

    private fun clipRange(): Pair<Long, Long>? {
        if (!trimVisible || (trimStart <= TRIM_EDGE && trimEnd >= 1f - TRIM_EDGE)) {
            return null
        }
        val duration = exoPlayer?.duration?.takeIf { it > 0L } ?: return null
        val start = (trimStart * duration).toLong().coerceIn(0L, duration - 1)
        val end = if (trimEnd >= 1f - TRIM_EDGE) {
            CLIP_THROUGH_END
        } else {
            (trimEnd * duration).toLong().coerceIn(start + 1, duration)
        }
        return start to end
    }

    private fun tintActions() {
        val accent = getProperPrimaryColor()
        val idle = android.graphics.Color.WHITE
        binding.actionRotateIcon.applyColorFilter(if (userRotation % 360 != 0) accent else idle)
        binding.actionMuteIcon.applyColorFilter(if (muted) accent else idle)
        binding.actionCropIcon.applyColorFilter(if (trimVisible) accent else idle)
        binding.actionExtractIcon.applyColorFilter(idle)
    }

    private fun loadFrameStep(uri: Uri) {
        ensureBackgroundThread {
            val step = VideoFrameExtractor.frameStepMs(this, uri)
            runOnUiThread {
                if (!isDestroyed) {
                    frameStepMs = step
                }
            }
        }
    }

    private fun extractFrame() {
        val uri = source ?: return
        val player = exoPlayer ?: return
        val position = player.currentPosition.coerceAtLeast(0L)
        val degrees = userRotation
        val hint = directoryHint()
        val name = displayName()
        pausePlayback()
        ensureBackgroundThread {
            try {
                val extracted = VideoFrameExtractor.extract(this, uri, position)
                if (extracted == null) {
                    runOnUiThread { toast(R.string.extract_frame_failed) }
                    return@ensureBackgroundThread
                }
                val frame = VideoFrameExtractor.rotate(extracted, degrees)
                runOnUiThread {
                    if (isDestroyed) {
                        if (!frame.isRecycled) {
                            frame.recycle()
                        }
                    } else {
                        saveVideoFrameBitmap(frame, hint, name, position)
                    }
                }
            } catch (_: Exception) {
                runOnUiThread { toast(R.string.extract_frame_failed) }
            } catch (_: OutOfMemoryError) {
                runOnUiThread { toast(org.fossify.commons.R.string.out_of_memory_error) }
            }
        }
    }

    private fun saveVideo() {
        if (exporter != null) {
            return
        }
        val wantsTrim = trimVisible && (trimStart > TRIM_EDGE || trimEnd < 1f - TRIM_EDGE)
        val clip = clipRange()
        if (wantsTrim && clip == null) {
            toast(R.string.video_edit_failed)
            return
        }
        val changed = userRotation % 360 != 0 || muted || clip != null
        if (!changed) {
            toast(R.string.video_edit_unchanged)
            return
        }
        pickWritableExportPath(
            directoryHint = directoryHint(),
            baseName = displayName(),
            fileStemSuffix = "edit",
            extension = "mp4",
            onCancel = {},
            onPath = { startExport(it, clip?.first ?: 0L, clip?.second ?: CLIP_THROUGH_END) },
        )
    }

    private fun startExport(destination: String, clipStartMs: Long, clipEndMs: Long) {
        val uri = source ?: return
        if (exporter != null || exportCancelled) {
            return
        }
        pausePlayback()
        val cache = File(cacheDir, "video-edit-${System.currentTimeMillis()}.mp4")
        progressDialog = getAlertDialogBuilder()
            .setMessage(org.fossify.commons.R.string.saving)
            .setNegativeButton(org.fossify.commons.R.string.cancel) { _, _ -> exporter?.cancel() }
            .setCancelable(false)
            .show()
        val export = VideoEditExporter(
            context = applicationContext,
            source = uri,
            rotationDegrees = userRotation,
            muted = muted,
            clipStartMs = clipStartMs,
            clipEndMs = clipEndMs,
            outputFile = cache,
        ) { success, error ->
            runOnUiThread {
                progressDialog?.dismiss()
                progressDialog = null
                exporter = null
                if (exportCancelled || isDestroyed) {
                    cache.delete()
                    return@runOnUiThread
                }
                if (success) {
                    copyExport(cache, destination)
                } else {
                    cache.delete()
                    if (error != null) {
                        toast(R.string.video_edit_failed)
                    }
                }
            }
        }
        exporter = export
        export.start()
    }

    private fun copyExport(cache: File, destination: String) {
        ensureBackgroundThread {
            val copied = copyFile(cache, destination)
            cache.delete()
            runOnUiThread {
                if (isDestroyed) {
                    return@runOnUiThread
                }
                if (copied) {
                    rescanPaths(arrayListOf(destination)) {
                        toast(org.fossify.commons.R.string.file_saved)
                    }
                } else {
                    File(destination).delete()
                    toast(R.string.video_edit_failed)
                }
            }
        }
    }

    private fun copyFile(cache: File, destination: String): Boolean {
        if (!cache.isFile || cache.length() == 0L) {
            return false
        }
        try {
            FileInputStream(cache).use { input ->
                FileOutputStream(File(destination)).use { output -> input.copyTo(output) }
            }
            return true
        } catch (_: Exception) {
        }
        return try {
            val output = getFileOutputStreamSync(destination, "video/mp4") ?: return false
            FileInputStream(cache).use { input -> output.use { input.copyTo(it) } }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun directoryHint(): String {
        val uri = source
        val realPath = intent.getStringExtra(REAL_FILE_PATH)?.takeIf { File(it).isFile }
            ?: uri?.takeIf { it.scheme == "file" }?.path?.takeIf { File(it).isFile }
            ?: uri?.let { getRealPathFromURI(it) }?.takeIf { File(it).isFile }
        return realPath?.getParentPath().orEmpty()
    }

    private fun displayName(): String {
        val realPath = intent.getStringExtra(REAL_FILE_PATH)?.takeIf { File(it).isFile }
        if (realPath != null) {
            return realPath.getFilenameFromPath()
        }
        val uri = source ?: return "video"
        return getFilenameFromUri(uri).ifBlank { "video" }
    }

    companion object {
        private const val PROGRESS_INTERVAL_MS = 200L
        private const val TRIM_EDGE = 0.005f
        private const val CLIP_THROUGH_END = -1L
        private const val STATE_ROTATION = "video_edit_rotation"
        private const val STATE_MUTED = "video_edit_muted"
        private const val STATE_TRIM = "video_edit_trim"
        private const val STATE_TRIM_START = "video_edit_trim_start"
        private const val STATE_TRIM_END = "video_edit_trim_end"
        private const val STATE_POSITION = "video_edit_position_state"
    }
}
