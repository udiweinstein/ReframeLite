package com.udiweinstein.reframelite

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Keeps its child area at a fixed width/height ratio (9:16), centred. */
class AspectFrame(ctx: Context, private val ratio: Float) : FrameLayout(ctx) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxW = MeasureSpec.getSize(widthMeasureSpec)
        val maxH = MeasureSpec.getSize(heightMeasureSpec)
        var w = maxW
        var h = (maxW / ratio).toInt()
        if (h > maxH) { h = maxH; w = (maxH * ratio).toInt() }
        super.onMeasure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
    }
}

/** Drag = look around, pinch = zoom; in selection mode: draw the Deep Track box. */
class TouchView(ctx: Context, private val listener: Listener) : View(ctx) {
    interface Listener {
        fun onPan(dx: Float, dy: Float, viewHeight: Float)
        fun onZoom(scale: Float)
        fun onInteract(active: Boolean)
        fun onSubjectSelected(rect: RectF, width: Int, height: Int)
    }

    var selecting = false
        set(v) { field = v; box = null; invalidate() }
    private var box: RectF? = null
    private var startX = 0f
    private var startY = 0f
    private var lastX = 0f
    private var lastY = 0f

    private val dim = Paint().apply { color = Color.argb(90, 0, 0, 0) }
    private val stroke = Paint().apply { color = GREEN; style = Paint.Style.STROKE; strokeWidth = 5f; isAntiAlias = true }
    private val fill = Paint().apply { color = Color.argb(50, 52, 199, 89) }

    private val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            listener.onZoom(d.scaleFactor)
            return true
        }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (selecting) {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startX = e.x; startY = e.y; box = RectF(e.x, e.y, e.x, e.y) }
                MotionEvent.ACTION_MOVE -> box = RectF(min(startX, e.x), min(startY, e.y), max(startX, e.x), max(startY, e.y))
                MotionEvent.ACTION_UP -> {
                    val r = box
                    if (r != null && r.width() > 16 && r.height() > 16) {
                        selecting = false
                        listener.onSubjectSelected(r, width, height)
                    } else {
                        box = null
                    }
                }
            }
            invalidate()
            return true
        }
        scaler.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastX = e.x; lastY = e.y; listener.onInteract(true) }
            MotionEvent.ACTION_MOVE -> {
                if (!scaler.isInProgress && e.pointerCount == 1) {
                    listener.onPan(e.x - lastX, e.y - lastY, height.toFloat())
                }
                lastX = e.x; lastY = e.y
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val keep = if (e.actionIndex == 0) 1 else 0
                lastX = e.getX(keep); lastY = e.getY(keep)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> listener.onInteract(false)
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        if (!selecting) return
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
        box?.let {
            canvas.drawRect(it, fill)
            canvas.drawRect(it, stroke)
        }
    }

    companion object { val GREEN = Color.rgb(52, 199, 89) }
}

/** Timeline: playhead, start/end markers, Deep Track ranges. Drag to scrub. */
class TimelineView(ctx: Context) : View(ctx) {
    var durationUs = 1L; set(v) { field = max(1L, v); invalidate() }
    var positionUs = 0L; set(v) { field = v; invalidate() }
    var startUs = 0L; set(v) { field = v; invalidate() }
    var endUs = 1L; set(v) { field = v; invalidate() }
    var tracked: List<LongRange> = emptyList(); set(v) { field = v; invalidate() }
    var onScrub: ((Long, Boolean) -> Unit)? = null

    private val density = ctx.resources.displayMetrics.density
    private val pad = 18 * density
    private val bar = Paint().apply { color = Color.argb(60, 255, 255, 255) }
    private val range = Paint().apply { color = Color.rgb(0, 160, 240) }
    private val trackPaint = Paint().apply { color = TouchView.GREEN }
    private val head = Paint().apply { color = Color.WHITE; isAntiAlias = true }
    private val startPaint = Paint().apply { color = TouchView.GREEN; isAntiAlias = true }
    private val endPaint = Paint().apply { color = Color.rgb(255, 69, 58); isAntiAlias = true }

    private fun x(us: Long) = pad + (width - 2 * pad) * (us.toFloat() / durationUs)
    private fun us(x: Float) = (((x - pad) / (width - 2 * pad)).coerceIn(0f, 1f) * durationUs).toLong()

    override fun onDraw(c: Canvas) {
        val mid = height / 2f
        val t = 3 * density
        c.drawRoundRect(RectF(pad, mid - t, width - pad, mid + t), t, t, bar)
        c.drawRect(x(startUs), mid - t, x(endUs), mid + t, range)
        for (r in tracked) c.drawRect(x(r.first), mid + 2 * t, max(x(r.last), x(r.first) + 2), mid + 3.5f * t, trackPaint)
        marker(c, x(startUs), mid, startPaint)
        marker(c, x(endUs), mid, endPaint)
        c.drawCircle(x(positionUs), mid, 8 * density, head)
    }

    private fun marker(c: Canvas, x: Float, mid: Float, p: Paint) {
        val s = 7 * density
        val path = Path().apply {
            moveTo(x, mid - 2.2f * s); lineTo(x - s, mid - 3.6f * s); lineTo(x + s, mid - 3.6f * s); close()
        }
        c.drawPath(path, p)
        c.drawRect(x - density, mid - 2.4f * s, x + density, mid + s, p)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                onScrub?.invoke(us(e.x), false)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> onScrub?.invoke(us(e.x), true)
        }
        return true
    }

    @Suppress("unused")
    private fun near(a: Float, b: Float) = abs(a - b) < 24 * density
}
