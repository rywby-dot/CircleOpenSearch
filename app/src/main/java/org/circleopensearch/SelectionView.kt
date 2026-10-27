package org.circleopensearch

import android.content.Context
import android.graphics.*
import android.view.*

/** All selection coordinates are kept in bitmap space, independent of screen insets. */
class SelectionView(context: Context) : View(context) {
    var onSelectionFinished: (() -> Unit)? = null
    var onInteractionStarted: (() -> Unit)? = null
    private var image: Bitmap? = null
    private val destination = RectF()
    private val selected = RectF()
    private val trail = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val feedback = SelectionFeedback(resources.displayMetrics.density)
    private var trailX = 0f
    private var trailY = 0f
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var mode = 0 // 0: draw, 1: move, 2..5: resize corner
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private var multiTouch = false
    private var centroidX = 0f
    private var centroidY = 0f
    private val beforeGesture = RectF()
    private var strokeDistance = 0f
    private var strokeX = 0f
    private var strokeY = 0f
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val next = (zoom * detector.scaleFactor).coerceIn(1f, 4f)
            val ratio = next / zoom
            panX = detector.focusX - width / 2f - (detector.focusX - width / 2f - panX) * ratio
            panY = detector.focusY - height / 2f - (detector.focusY - height / 2f - panY) * ratio
            zoom = next
            updateDestination()
            return true
        }
    })
    private val handleRadius get() = 12f * resources.displayMetrics.density

    fun setImage(value: Bitmap?) { image = value; zoom = 1f; panX = 0f; panY = 0f; multiTouch = false; clearSelection(); feedback.activate(); updateDestination() }
    fun clearSelection() { selected.setEmpty(); trail.reset(); feedback.stop(); invalidate() }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { feedback.resize(w,h); updateDestination() }
    private fun updateDestination() {
        val bitmap = image ?: return
        val scale = minOf(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height) * zoom
        val w = bitmap.width * scale
        val h = bitmap.height * scale
        val margin = 48 * resources.displayMetrics.density
        val maxX = ((width + w) / 2 - margin).coerceAtLeast(0f)
        val maxY = ((height + h) / 2 - margin).coerceAtLeast(0f)
        panX = panX.coerceIn(-maxX, maxX)
        panY = panY.coerceIn(-maxY, maxY)
        destination.set((width - w) / 2 + panX, (height - h) / 2 + panY, (width + w) / 2 + panX, (height + h) / 2 + panY)
        invalidate()
    }
    private fun displayRect() = RectF(
        destination.left + selected.left * destination.width() / (image?.width ?: 1),
        destination.top + selected.top * destination.height() / (image?.height ?: 1),
        destination.left + selected.right * destination.width() / (image?.width ?: 1),
        destination.top + selected.bottom * destination.height() / (image?.height ?: 1))

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.rgb(12, 14, 20))
        val bitmap = image ?: return
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        canvas.drawBitmap(bitmap, null, destination, paint)
        paint.color = 0x30000000
        canvas.drawRect(destination, paint)
        val animate = feedback.draw(canvas)
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeWidth = 2.5f * resources.displayMetrics.density
        paint.color = Color.WHITE
        canvas.drawPath(trail, paint)
        if (animate) postInvalidateOnAnimation()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        val bitmap = image ?: return false
        if (destination.isEmpty) return false
        scaleDetector.onTouchEvent(event)
        if (event.pointerCount >= 2) {
            if (!multiTouch) selected.set(beforeGesture)
            val cx = (event.getX(0) + event.getX(1)) / 2
            val cy = (event.getY(0) + event.getY(1)) / 2
            if (event.actionMasked == MotionEvent.ACTION_MOVE && multiTouch) {
                panX += cx - centroidX; panY += cy - centroidY
                updateDestination()
            }
            centroidX = cx; centroidY = cy
            multiTouch = true; trail.reset(); feedback.stop(); invalidate()
            return true
        }
        if (multiTouch) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) multiTouch = false
            return true
        }
        val x = ((event.x - destination.left) / destination.width() * bitmap.width).coerceIn(0f, bitmap.width.toFloat())
        val y = ((event.y - destination.top) / destination.height() * bitmap.height).coerceIn(0f, bitmap.height.toFloat())
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                onInteractionStarted?.invoke()
                if (!destination.contains(event.x, event.y)) return false
                beforeGesture.set(selected)
                downX = x; downY = y; lastX = x; lastY = y
                strokeDistance = 0f; strokeX = event.x; strokeY = event.y
                mode = 0
                if (!selected.isEmpty) {
                    val rect = displayRect()
                    val corners = arrayOf(rect.left to rect.top, rect.right to rect.top, rect.right to rect.bottom, rect.left to rect.bottom)
                    val corner = corners.indexOfFirst { (cx, cy) -> kotlin.math.hypot(event.x - cx, event.y - cy) < handleRadius * 2 }
                    mode = if (corner >= 0) corner + 2 else if (selected.contains(x, y)) 1 else 0
                }
                if (mode == 0) { selected.set(x, y, x, y); trail.reset(); trail.moveTo(event.x, event.y)
                    trailX = event.x; trailY = event.y; feedback.start(event.x,event.y) }
            }
            MotionEvent.ACTION_MOVE -> {
                strokeDistance += kotlin.math.hypot(event.x - strokeX, event.y - strokeY)
                strokeX = event.x; strokeY = event.y
                when (mode) {
                    0 -> {
                        for (i in 0 until event.historySize) {
                            val hx = event.getHistoricalX(i); val hy = event.getHistoricalY(i)
                            selected.union(((hx-destination.left)/destination.width()*bitmap.width).coerceIn(0f,bitmap.width.toFloat()),
                                ((hy-destination.top)/destination.height()*bitmap.height).coerceIn(0f,bitmap.height.toFloat()))
                            extendTrail(hx,hy,event.getHistoricalEventTime(i))
                        }
                        selected.union(x, y); extendTrail(event.x,event.y,event.eventTime)
                    }
                    1 -> selected.offset((x - lastX).coerceIn(-selected.left, bitmap.width - selected.right), (y - lastY).coerceIn(-selected.top, bitmap.height - selected.bottom))
                    else -> {
                        val min = 8f
                        if (mode == 2 || mode == 5) selected.left = x.coerceAtMost(selected.right - min).coerceAtLeast(0f)
                        else selected.right = x.coerceAtLeast(selected.left + min).coerceAtMost(bitmap.width.toFloat())
                        if (mode == 2 || mode == 3) selected.top = y.coerceAtMost(selected.bottom - min).coerceAtLeast(0f)
                        else selected.bottom = y.coerceAtLeast(selected.top + min).coerceAtMost(bitmap.height.toFloat())
                    }
                }
                lastX = x; lastY = y
            }
            MotionEvent.ACTION_UP -> {
                strokeDistance += kotlin.math.hypot(event.x - strokeX, event.y - strokeY)
                if (mode == 0) {
                    selected.union(x, y)
                    if (strokeDistance < ViewConfiguration.get(context).scaledTouchSlop) {
                        // A tap selects a local region; semantic object detection comes later.
                        val radius = minOf(bitmap.width, bitmap.height) * 0.15f
                        selected.set((downX-radius).coerceAtLeast(0f), (downY-radius).coerceAtLeast(0f), (downX+radius).coerceAtMost(bitmap.width.toFloat()), (downY+radius).coerceAtMost(bitmap.height.toFloat()))
                    } else {
                        val padding = 12 * resources.displayMetrics.density * bitmap.width / destination.width()
                        if (selected.width() < padding * 2) { selected.left -= padding; selected.right += padding }
                        if (selected.height() < padding * 2) { selected.top -= padding; selected.bottom += padding }
                        selected.intersect(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
                    }
                }
                trail.reset(); feedback.stop(); performClick()
                if (!selected.isEmpty) onSelectionFinished?.invoke()
            }
            MotionEvent.ACTION_CANCEL -> { trail.reset(); feedback.stop(); selected.set(beforeGesture) }
        }
        invalidate()
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    private fun extendTrail(x: Float, y: Float, time: Long) {
        trail.quadTo(trailX, trailY, (trailX+x)/2, (trailY+y)/2)
        trailX = x; trailY = y; feedback.move(x,y,time)
    }
    fun crop(source: Bitmap? = image): Bitmap? {
        val bitmap = source ?: return null
        val rect = if (selected.isEmpty) RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat()) else selected
        val left = rect.left.toInt().coerceIn(0, bitmap.width - 1)
        val top = rect.top.toInt().coerceIn(0, bitmap.height - 1)
        // Always copy: a full-image createBitmap may return the source bitmap itself.
        return Bitmap.createBitmap(bitmap, left, top, (rect.right.toInt()-left).coerceIn(1, bitmap.width-left), (rect.bottom.toInt()-top).coerceIn(1, bitmap.height-top)).copy(Bitmap.Config.ARGB_8888, false)
    }
}
