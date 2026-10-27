package org.circleopensearch

import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.view.*
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.*

/** Edge-to-edge music sheet in the assistant window, with a drag-to-dismiss handle. */
class MusicOverlay(context: Context, private val open: (String) -> Unit,
    private val youtube: (SongMatch) -> Unit, private val close: () -> Unit) : FrameLayout(context) {
    private val sheet = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), 0, dp(24), dp(24))
        background = GradientDrawable().apply {
            setColor(SURFACE)
            val r = dp(28).toFloat()
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        }
    }
    private val visual = MusicPulseView(context)
    private val heading = label("Identify music", 20f, Ui.foreground)
    private val detail = label("", 14f, Ui.secondary).apply { setPadding(dp(8), dp(8), dp(8), dp(16)) }
    private val songTitle = label("", 24f, Ui.foreground).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); maxLines = 4
    }
    private val artist = label("", 16f, Ui.secondary).apply { maxLines = 3 }
    private val titleRow = copyRow(songTitle, "Copy song title") { state.song?.title.orEmpty() }
    private val artistRow = copyRow(artist, "Copy artist") { state.song?.artist.orEmpty() }
    private val actions = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
    private var state = MusicState.value
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var startedInSheet = false
    private var dragging = false
    private var dismissing = false
    private var velocity: VelocityTracker? = null
    private val easing = PathInterpolator(0.2f, 0f, 0f, 1f)
    private fun dp(value: Int) = Ui.dp(context, value)
    private fun label(value: String, size: Float, color: Int) = TextView(context).apply {
        text = value; textSize = size; setTextColor(color); gravity = Gravity.CENTER
        setPadding(0, dp(4), 0, dp(4))
    }
    private fun copyRow(text: TextView, description: String, value: () -> String) = FrameLayout(context).apply {
        // Equal padding keeps the label centered on the sheet, independent of the icon.
        text.setPadding(dp(48), dp(6), dp(48), dp(6))
        addView(text, LayoutParams(-1, -2, Gravity.CENTER).apply { topMargin = dp(4); bottomMargin = dp(4) })
        addView(IconButton(context, Glyph.COPY, description, iconSizeDp = 20).apply {
            setOnClickListener {
                val content = value()
                if (content.isNotBlank()) {
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(description, content))
                    if (android.os.Build.VERSION.SDK_INT < 33) Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                }
            }
        }, LayoutParams(dp(48), dp(48), Gravity.END or Gravity.CENTER_VERTICAL))
        minimumHeight = dp(48)
    }
    private fun action(label: String, run: () -> Unit) = TextView(context).apply {
        text = label; textSize = 14f; setTextColor(Ui.foreground)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER; isClickable = true; isFocusable = true
        background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x22FFFFFF),
            GradientDrawable().apply { setColor(0xFF34363B.toInt()); cornerRadius = dp(24).toFloat(); setStroke(dp(1), 0xFF55575C.toInt()) }, null)
        setOnClickListener { run() }
    }
    init {
        val handle = object : FrameLayout(context) {
            override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(info)
                info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_DISMISS)
            }
            override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
                if (action == AccessibilityNodeInfo.ACTION_DISMISS) { dismiss(); return true }
                return super.performAccessibilityAction(action, arguments)
            }
        }.apply {
            contentDescription = "Drag down to close music search"
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            addView(View(context).apply { background = Ui.rounded(0xFFE3E3E6.toInt(), dp(2).toFloat()) },
                LayoutParams(dp(36), dp(4), Gravity.CENTER))
        }
        sheet.addView(handle, LinearLayout.LayoutParams(-1, dp(32)))
        sheet.addView(visual, LinearLayout.LayoutParams(-1, dp(96)))
        sheet.addView(heading); sheet.addView(detail)
        sheet.addView(titleRow); sheet.addView(artistRow)
        actions.addView(action("Open song") { state.song?.url?.takeIf { it.isNotBlank() }?.let(open) },
            LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(8) })
        actions.addView(action("Search YouTube") { state.song?.let(youtube) }, LinearLayout.LayoutParams(0, dp(48), 1f))
        sheet.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        addView(sheet, LayoutParams(-1, -2, Gravity.BOTTOM))
        render(state)
        // Wait for the first visible, measured frame after the consent dialog.
        // Posting from the constructor can run before layout or while this window is hidden.
        sheet.alpha = 0f
        sheet.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (!sheet.isShown || sheet.height == 0 || state.phase == MusicState.Phase.CONSENT) return true
                sheet.viewTreeObserver.removeOnPreDrawListener(this)
                sheet.translationY = sheet.height.toFloat()
                sheet.alpha = 1f
                sheet.animate().translationY(0f).setDuration(300).setInterpolator(easing).start()
                return true
            }
        })
    }
    fun setBottomInset(inset: Int) { sheet.setPadding(dp(24), 0, dp(24), maxOf(dp(24), inset + dp(16))) }
    fun render(value: MusicState.State) {
        state = value
        val found = value.song != null
        val title = when (value.phase) {
            MusicState.Phase.CONSENT -> "Allow audio capture"
            MusicState.Phase.LISTENING -> "Listening…"
            MusicState.Phase.MATCHING -> "Finding the song…"
            MusicState.Phase.ERROR -> "Song not identified"
            else -> "Identify music"
        }
        if (heading.text.toString() != title) heading.text = title
        if (detail.text.toString() != value.message) detail.text = value.message
        songTitle.text = value.song?.title.orEmpty(); artist.text = value.song?.artist.orEmpty()
        titleRow.visibility = if (found) View.VISIBLE else View.GONE
        artistRow.visibility = titleRow.visibility
        actions.visibility = titleRow.visibility
        heading.visibility = if (found) View.GONE else View.VISIBLE
        detail.visibility = heading.visibility
        visual.visibility = if (!found && value.running) View.VISIBLE else View.GONE
        visual.update(value)
    }
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (dismissing) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y
                startedInSheet = event.y >= sheet.top + sheet.translationY
                dragging = false
                velocity?.recycle(); velocity = VelocityTracker.obtain().apply { addMovement(event) }
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(event)
                val dy = event.y - downY
                if (startedInSheet && dy > touchSlop && dy > abs(event.x - downX)) {
                    dragging = true; sheet.animate().cancel(); return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { velocity?.recycle(); velocity = null }
        }
        return dragging
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (dismissing) return true
        if (!dragging && event.actionMasked == MotionEvent.ACTION_MOVE) {
            val dy = event.y - downY
            if (startedInSheet && dy > touchSlop && dy > abs(event.x - downX)) {
                dragging = true
                sheet.animate().cancel()
            }
        }
        if (!dragging) return startedInSheet
        velocity?.addMovement(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> sheet.translationY = (event.y - downY).coerceAtLeast(0f)
            MotionEvent.ACTION_UP -> {
                velocity?.computeCurrentVelocity(1000)
                val fast = (velocity?.yVelocity ?: 0f) > dp(800) && sheet.translationY > touchSlop * 2
                if (sheet.translationY > minOf(dp(96).toFloat(), sheet.height * 0.25f) || fast) dismiss()
                else sheet.animate().translationY(0f).setDuration(200).setInterpolator(easing).start()
                releaseGesture()
            }
            MotionEvent.ACTION_CANCEL -> {
                sheet.animate().translationY(0f).setDuration(200).setInterpolator(easing).start(); releaseGesture()
            }
        }
        return true
    }
    private fun releaseGesture() { dragging = false; velocity?.recycle(); velocity = null }
    private fun dismiss() {
        if (dismissing) return
        dismissing = true; visual.stop()
        sheet.animate().translationY(sheet.height.toFloat()).setDuration(220).setInterpolator(easing).withEndAction { close() }.start()
    }
    fun touchRegion(): Rect {
        val location = IntArray(2); sheet.getLocationInWindow(location)
        return Rect(location[0], location[1], location[0] + sheet.width, location[1] + sheet.height)
    }
    override fun onDetachedFromWindow() {
        sheet.animate().cancel(); visual.stop(); releaseGesture(); super.onDetachedFromWindow()
    }
    companion object { val SURFACE: Int = Color.rgb(40, 42, 46) }
}

/** Small, monochrome audio-reactive waveform; no glow or decorative orbit. */
private class MusicPulseView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var level = 0f
    private var target = 0f
    private var started = SystemClock.elapsedRealtime()
    private val animation = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2000; repeatCount = ValueAnimator.INFINITE
        addUpdateListener { level += (target - level) * 0.15f; invalidate() }
    }
    fun update(state: MusicState.State) {
        target = state.level
        if (state.running && !animation.isStarted) { started = SystemClock.elapsedRealtime(); animation.start() }
        else if (!state.running) stop()
        invalidate()
    }
    fun stop() { animation.cancel() }
    override fun onDetachedFromWindow() { stop(); super.onDetachedFromWindow() }
    override fun onDraw(canvas: Canvas) {
        val time = (SystemClock.elapsedRealtime() - started) / 1000f
        val cx = width / 2f; val cy = height / 2f
        val density = resources.displayMetrics.density
        paint.color = Ui.foreground; paint.strokeWidth = 3f * density; paint.strokeCap = Paint.Cap.ROUND
        for (i in -3..3) {
            val wave = (sin(time * 4.2f + i * 0.7f) + 1f) / 2f
            val h = density * (4f + wave * (8f + level * 18f))
            val x = cx + i * 8f * density
            canvas.drawLine(x, cy - h, x, cy + h, paint)
        }
    }
}
