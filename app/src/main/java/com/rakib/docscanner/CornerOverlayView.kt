package com.rakib.docscanner

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Draws the 4 detected page corners on top of the preview image and lets the
 * user drag any of them to correct a bad auto-detection. Coordinates here
 * are always in *this view's* pixel space — ReviewActivity is responsible
 * for converting to/from the source bitmap's coordinate space.
 */
class CornerOverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    // Order: topLeft, topRight, bottomRight, bottomLeft
    var corners: Array<PointF> = arrayOf(
        PointF(0f, 0f), PointF(0f, 0f), PointF(0f, 0f), PointF(0f, 0f)
    )
        set(value) {
            field = value
            invalidate()
        }

    private var draggingIndex = -1
    private val touchSlopPx = 60f

    private val linePaint = Paint().apply {
        color = Color.parseColor("#2196F3")
        strokeWidth = 4f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    private val handlePaint = Paint().apply {
        color = Color.parseColor("#2196F3")
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val handleRadius = 24f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (corners.size != 4) return

        for (i in corners.indices) {
            val a = corners[i]
            val b = corners[(i + 1) % corners.size]
            canvas.drawLine(a.x, a.y, b.x, b.y, linePaint)
        }
        for (p in corners) {
            canvas.drawCircle(p.x, p.y, handleRadius, handlePaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                draggingIndex = corners.indexOfFirst {
                    distance(it.x, it.y, event.x, event.y) < touchSlopPx
                }
                return draggingIndex != -1
            }
            MotionEvent.ACTION_MOVE -> {
                if (draggingIndex != -1) {
                    val clampedX = event.x.coerceIn(0f, width.toFloat())
                    val clampedY = event.y.coerceIn(0f, height.toFloat())
                    corners[draggingIndex] = PointF(clampedX, clampedY)
                    invalidate()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                draggingIndex = -1
            }
        }
        return super.onTouchEvent(event)
    }

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }
}
