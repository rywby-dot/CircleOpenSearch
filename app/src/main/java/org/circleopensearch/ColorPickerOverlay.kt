package org.circleopensearch

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast
import org.circleopensearch.colorpicker.MagnifierView
import org.circleopensearch.colorpicker.TargetView

/** Uses the assistant's existing bitmap. No MediaProjection, overlay window, or live capture. */
class ColorPickerOverlay(context: Context, private val bitmap: Bitmap,
    private val onClose: () -> Unit) : FrameLayout(context), MagnifierView.OnInteractionListener {
    private val mapping = PixelMapping(bitmap.width, bitmap.height)
    private val target = TargetView(context)
    private val magnifier = MagnifierView(context).apply { listener = this@ColorPickerOverlay; isFocusable = true }
    private val targetSize = Ui.dp(context, 60)
    private val magnifierSize = Ui.dp(context, 176)
    private var px = 0f
    private var py = 0f
    private var initialized = false
    private val backdrop = object : View(context) {
        private val source = Rect(0, 0, bitmap.width, bitmap.height)
        private val paint = Paint().apply { isFilterBitmap = false }
        override fun onDraw(canvas: Canvas) { canvas.drawBitmap(bitmap, source, Rect(0, 0, width, height), paint) }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_MOVE) moveTarget(event.x, event.y)
            if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
            return true
        }
        override fun performClick(): Boolean { super.performClick(); return true }
    }
    init {
        setBackgroundColor(Color.BLACK)
        isForceDarkAllowed = false
        addView(backdrop, LayoutParams(-1, -1))
        addView(target, LayoutParams(targetSize, targetSize))
        addView(magnifier, LayoutParams(magnifierSize, magnifierSize))
        target.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_MOVE) {
                val location = IntArray(2); getLocationOnScreen(location)
                moveTarget(event.rawX - location[0], event.rawY - location[1])
            }
            true
        }
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!initialized) { px = w / 2f; py = h / 2f; initialized = true }
        moveTarget(px, py)
    }
    private fun moveTarget(x: Float, y: Float) {
        if (width == 0 || height == 0) return
        px = x.coerceIn(0f, width - 1f); py = y.coerceIn(0f, height - 1f)
        target.x = px - targetSize / 2f; target.y = py - targetSize / 2f
        val size = minOf(magnifierSize, width, height)
        magnifier.layoutParams = LayoutParams(size, size)
        magnifier.x = (px - size / 2).coerceIn(0f, (width - size).toFloat())
        val above = py - targetSize / 2 - size - Ui.dp(context, 12)
        magnifier.y = if (above >= 0) above else (py + targetSize / 2 + Ui.dp(context, 12)).coerceAtMost((height - size).toFloat())
        val sx = mapping.x(px, width); val sy = mapping.y(py, height)
        val zoom = Bitmap.createBitmap(17, 17, Bitmap.Config.ARGB_8888)
        for (dy in 0..16) for (dx in 0..16) zoom.setPixel(dx, dy,
            bitmap.getPixel((sx + dx - 8).coerceIn(0, bitmap.width - 1), (sy + dy - 8).coerceIn(0, bitmap.height - 1)))
        val hex = PixelMapping.hex(bitmap.getPixel(sx, sy))
        magnifier.updateContent(zoom, hex, sx, sy)
        magnifier.contentDescription = "Color $hex. Tap to copy HEX."
    }
    override fun onCloseClicked() = onClose()
    override fun onHexClicked(hex: String) { copy("Color HEX", hex) }
    override fun onCoordsClicked(coords: String) { copy("Pixel coordinates", coords) }
    private fun copy(label: String, value: String) {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, value))
        if (android.os.Build.VERSION.SDK_INT < 33) Toast.makeText(context, "$value copied", Toast.LENGTH_SHORT).show()
    }
}
