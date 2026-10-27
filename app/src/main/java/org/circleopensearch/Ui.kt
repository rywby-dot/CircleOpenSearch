package org.circleopensearch

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo

object Ui {
    val surface = Color.rgb(31, 33, 38)
    val surfaceRaised = Color.rgb(43, 46, 53)
    val foreground = Color.rgb(241, 243, 244)
    val secondary = Color.rgb(178, 184, 196)
    val accent = Color.rgb(168, 199, 250)
    fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()
    fun rounded(color: Int, radius: Float) = GradientDrawable().apply { setColor(color); cornerRadius = radius }
    fun ripple(context: Context, color: Int = surface) = RippleDrawable(
        ColorStateList.valueOf(0x33FFFFFF), rounded(color, dp(context, 32).toFloat()), null)
}

enum class Glyph { LENS, SEARCH, COLOR_PICKER, MUSIC, COPY, CLOSE, SETTINGS, EXTERNAL, CHECK, TILE, SHORTCUT, BACK }

/** Vector controls with consistent 48dp touch targets and spoken English labels. */
class IconButton(context: Context, val glyph: Glyph, label: String, filled: Boolean = false, private val iconSizeDp: Int = 24) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    init {
        contentDescription = label
        tooltipText = label
        isClickable = true
        isFocusable = true
        minimumWidth = Ui.dp(context, 48)
        minimumHeight = Ui.dp(context, 48)
        background = Ui.ripple(context, if (filled) Ui.surface else Color.TRANSPARENT)
    }
    override fun setEnabled(enabled: Boolean) { super.setEnabled(enabled); alpha = if (enabled) 1f else 0.35f }
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.ImageButton"
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = Ui.dp(context, iconSizeDp).toFloat()
        canvas.save()
        canvas.translate((width - size) / 2, (height - size) / 2)
        canvas.scale(size / 24, size / 24)
        paint.color = Ui.foreground
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.8f
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = canvas.drawLine(x1, y1, x2, y2, paint)
        fun path(vararg points: Float) {
            val path = Path(); path.moveTo(points[0], points[1])
            for (i in 2 until points.size step 2) path.lineTo(points[i], points[i + 1])
            canvas.drawPath(path, paint)
        }
        when (glyph) {
            Glyph.LENS -> {
                val colors = intArrayOf(0xFF4285F4.toInt(), 0xFFEA4335.toInt(), 0xFFFBBC04.toInt(), 0xFF34A853.toInt())
                val segments = arrayOf(floatArrayOf(4f,9f,4f,5f,9f,5f), floatArrayOf(15f,5f,20f,5f,20f,9f), floatArrayOf(20f,15f,20f,19f,15f,19f), floatArrayOf(9f,19f,4f,19f,4f,15f))
                segments.forEachIndexed { index, points -> paint.color = colors[index]; path(*points) }
                paint.color = Ui.foreground; canvas.drawCircle(12f, 12f, 3f, paint)
            }
            Glyph.SEARCH -> { canvas.drawCircle(10.5f, 10.5f, 6f, paint); line(15f,15f,20f,20f) }
            Glyph.COLOR_PICKER -> {
                path(5f,16f,3f,21f,8f,19f,19f,8f,16f,5f,5f,16f)
                line(13f,4f,20f,11f); path(16f,5f,19f,2f,22f,5f,19f,8f)
            }
            Glyph.MUSIC -> {
                path(9f,17f,9f,5f,20f,3f,20f,15f)
                line(9f,9f,20f,7f)
                canvas.drawOval(RectF(3f,16f,9f,21f),paint)
                canvas.drawOval(RectF(14f,14f,20f,19f),paint)
            }
            Glyph.COPY -> {
                canvas.drawRoundRect(RectF(8f,8f,20f,21f),2f,2f,paint)
                path(15f,4f,4f,4f,4f,16f)
            }
            Glyph.CLOSE -> { line(6f,6f,18f,18f); line(18f,6f,6f,18f) }
            Glyph.SETTINGS -> {
                canvas.drawCircle(12f,12f,7f,paint); canvas.drawCircle(12f,12f,2.5f,paint)
                for (i in 0..7) { canvas.save(); canvas.rotate(i*45f,12f,12f); line(12f,2f,12f,5f); canvas.restore() }
            }
            Glyph.EXTERNAL -> { path(14f,4f,20f,4f,20f,10f); line(20f,4f,10f,14f); path(10f,5f,5f,5f,5f,19f,19f,19f,19f,14f) }
            Glyph.CHECK -> path(4f,12f,9f,17f,20f,6f)
            Glyph.TILE -> { canvas.drawRoundRect(RectF(3f,4f,21f,20f),4f,4f,paint); line(7f,9f,12f,9f); line(7f,15f,17f,15f) }
            Glyph.SHORTCUT -> { path(12f,3f,3f,11f,5f,11f,5f,21f,19f,21f,19f,11f,21f,11f,12f,3f); line(9f,15f,15f,15f); line(12f,12f,12f,18f) }
            Glyph.BACK -> { path(12f,5f,5f,12f,12f,19f); line(5f,12f,21f,12f) }
        }
        canvas.restore()
    }
}
