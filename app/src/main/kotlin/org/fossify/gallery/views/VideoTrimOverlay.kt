package org.fossify.gallery.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.SeekBar
import kotlin.math.abs

/** Two draggable pins on the video timeline. The span between them is the exported range. */
class VideoTrimOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    var startFraction = 0f
    var endFraction = 1f
    var onRangeChanged: ((start: Float, end: Float, movingStart: Boolean, dragging: Boolean) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val pinWidth = 8f * density
    private val pinHeight = 20f * density
    private val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val pinStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.BLACK
        strokeWidth = 1.5f * density
    }
    private val rangePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
    }
    private var drag = NONE

    fun setRange(start: Float, end: Float) {
        startFraction = start.coerceIn(0f, 1f)
        endFraction = end.coerceIn(startFraction, 1f)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val barHeight = seekBar()?.measuredHeight ?: 0
        val cap = if (barHeight > 0) barHeight else (36f * density).toInt()
        val specSize = MeasureSpec.getSize(heightMeasureSpec)
        val specMode = MeasureSpec.getMode(heightMeasureSpec)
        val height = if (specMode == MeasureSpec.EXACTLY) specSize.coerceAtMost(cap) else cap
        setMeasuredDimension(width, height.coerceAtLeast(1))
    }

    override fun onDraw(canvas: Canvas) {
        val startX = xFor(startFraction)
        val endX = xFor(endFraction)
        val centerY = height / 2f
        canvas.drawLine(startX, centerY, endX, centerY, rangePaint)
        drawPin(canvas, startX, centerY)
        drawPin(canvas, endX, centerY)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (visibility != VISIBLE) {
            return false
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                drag = hitPin(event.x)
                if (drag == NONE) {
                    return false
                }
                parent?.requestDisallowInterceptTouchEvent(true)
                applyDrag(event.x, dragging = true)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (drag == NONE) {
                    return false
                }
                applyDrag(event.x, dragging = true)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val wasDragging = drag != NONE
                if (wasDragging) {
                    onRangeChanged?.invoke(startFraction, endFraction, drag == START, false)
                }
                drag = NONE
                return wasDragging
            }
        }
        return false
    }

    private fun applyDrag(x: Float, dragging: Boolean) {
        val gap = minGapFraction()
        val fraction = fractionFor(x)
        val movingStart = drag == START
        if (movingStart) {
            startFraction = fraction.coerceIn(0f, endFraction - gap)
        } else {
            endFraction = fraction.coerceIn(startFraction + gap, 1f)
        }
        invalidate()
        onRangeChanged?.invoke(startFraction, endFraction, movingStart, dragging)
    }

    private fun drawPin(canvas: Canvas, centerX: Float, centerY: Float) {
        val halfW = pinWidth / 2f
        val halfH = pinHeight / 2f
        val cx = centerX.coerceIn(halfW, (width - halfW).coerceAtLeast(halfW))
        val rect = RectF(cx - halfW, centerY - halfH, cx + halfW, centerY + halfH)
        canvas.drawRoundRect(rect, halfW, halfW, pinPaint)
        canvas.drawRoundRect(rect, halfW, halfW, pinStroke)
    }

    private fun hitPin(x: Float): Int {
        val slop = 24f * density
        val distStart = abs(x - xFor(startFraction))
        val distEnd = abs(x - xFor(endFraction))
        val hitStart = distStart <= slop
        val hitEnd = distEnd <= slop
        return when {
            hitStart && hitEnd -> if (distStart <= distEnd) START else END
            hitStart -> START
            hitEnd -> END
            else -> NONE
        }
    }

    private fun minGapFraction(): Float {
        val span = (trackRight() - trackLeft()).coerceAtLeast(1f)
        return (pinWidth * 2f / span).coerceIn(0.002f, 0.25f)
    }

    private fun xFor(fraction: Float): Float {
        val directed = if (isRtl()) 1f - fraction else fraction
        return trackLeft() + (trackRight() - trackLeft()) * directed
    }

    private fun fractionFor(x: Float): Float {
        val span = (trackRight() - trackLeft()).coerceAtLeast(1f)
        val raw = ((x - trackLeft()) / span).coerceIn(0f, 1f)
        return if (isRtl()) 1f - raw else raw
    }

    private fun trackLeft(): Float = (seekBar()?.paddingLeft ?: paddingLeft).toFloat()

    private fun trackRight(): Float {
        val bar = seekBar()
        val widthPx = (bar?.width ?: width).toFloat()
        val padding = (bar?.paddingRight ?: paddingRight).toFloat()
        return (widthPx - padding).coerceAtLeast(trackLeft() + 1f)
    }

    private fun seekBar(): SeekBar? {
        val parentView = parent as? android.view.ViewGroup ?: return null
        for (index in 0 until parentView.childCount) {
            val child = parentView.getChildAt(index)
            if (child is SeekBar) {
                return child
            }
        }
        return null
    }

    private fun isRtl(): Boolean = layoutDirection == LAYOUT_DIRECTION_RTL

    companion object {
        private const val NONE = 0
        private const val START = 1
        private const val END = 2
    }
}
