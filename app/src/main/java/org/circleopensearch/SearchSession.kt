package org.circleopensearch

import android.content.*
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionSession
import android.view.*
import android.widget.*
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

class SearchSession(context: Context) : VoiceInteractionSession(context) {
    private lateinit var root: FrameLayout
    private lateinit var selection: SelectionView
    private lateinit var status: TextView
    private lateinit var dock: LinearLayout
    private lateinit var search: IconButton
    private lateinit var colorPicker: IconButton
    private lateinit var music: IconButton
    private var picker: ColorPickerOverlay? = null
    private var musicOverlay: MusicOverlay? = null
    private val musicListener: (MusicState.State) -> Unit = {
        if (musicOverlay != null) {
            musicOverlay?.render(it)
            // Keep the assistant window attached: hiding and showing it triggers
            // the system's voice-window transition before our sheet animation.
            // During consent only the content disappears and all touches pass through.
            musicOverlay?.visibility = if (it.phase == MusicState.Phase.CONSENT) View.INVISIBLE else View.VISIBLE
            root.requestLayout()
        }
    }
    private var savedFitTypes: Int? = null
    private val savedAncestors = mutableListOf<Pair<View, IntArray>>()
    private lateinit var settings: IconButton
    private lateinit var close: IconButton
    private val worker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val capture = SingleCapture<Bitmap>()
    private var generation = 0
    private var preparing = false
    private var topInset = 0
    private var bottomInset = 0
    private var flags = SHOW_WITH_ASSIST or SHOW_WITH_SCREENSHOT
    private var results: LensPanel? = null
    private var original: Bitmap? = null
    private val autoSearch = Runnable { processImage() }
    private val captureTimeout = Runnable { capture.timeout(); renderCapture() }
    private fun dp(value: Int) = Ui.dp(context, value)

    override fun onCreate() {
        super.onCreate()
        @Suppress("DEPRECATION")
        window?.window?.decorView?.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        window?.window?.apply {
            clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
            addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            navigationBarColor = Color.TRANSPARENT
            isNavigationBarContrastEnforced = false
        }
    }

    override fun onCreateContentView(): View {
        root = FrameLayout(context)
        selection = SelectionView(context).apply {
            onInteractionStarted = { handler.removeCallbacks(autoSearch) }
            onSelectionFinished = { handler.removeCallbacks(autoSearch); autoSearch.run() }
        }
        root.addView(selection, FrameLayout.LayoutParams(-1, -1))
        close = IconButton(context, Glyph.CLOSE, "Close search", iconSizeDp = 16).apply {
            background = android.graphics.drawable.InsetDrawable(
                Ui.ripple(context, 0x993C4043.toInt()), Ui.dp(context, 6))
            setOnClickListener { hide() }
        }
        root.addView(close, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.START))
        dock = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        status = TextView(context).apply {
            text = "Getting screenshot…"; textSize = 14f; setTextColor(Ui.foreground)
            gravity = Gravity.CENTER; maxLines = 4
            setShadowLayer(dp(6).toFloat(), 0f, 1f, Color.BLACK)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        dock.addView(status)
        val pill = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = Ui.rounded(Ui.surface, dp(32).toFloat())
            elevation = dp(8).toFloat()
        }
        music = IconButton(context, Glyph.MUSIC, "Identify device audio").apply {
            setOnClickListener { showMusicOverlay() }
            tooltipText = "Tap for device audio; hold for nearby music"
            setOnLongClickListener { showMusicOverlay(microphone = true); true }
        }
        pill.addView(music, LinearLayout.LayoutParams(dp(56), dp(56)))
        pill.addView(View(context).apply { setBackgroundColor(0x33FFFFFF) }, LinearLayout.LayoutParams(dp(1), dp(24)))
        search = IconButton(context, Glyph.LENS, "Search with Google Lens").apply {
            setOnClickListener { processImage() }
            setOnLongClickListener { processImage(web = true); true }
        }
        pill.addView(search, LinearLayout.LayoutParams(0, dp(56), 1f))
        pill.addView(View(context).apply { setBackgroundColor(0x33FFFFFF) }, LinearLayout.LayoutParams(dp(1), dp(24)))
        colorPicker = IconButton(context, Glyph.COLOR_PICKER, "Pick a color").apply { setOnClickListener { showColorPicker() } }
        pill.addView(colorPicker, LinearLayout.LayoutParams(dp(56), dp(56)))
        settings = IconButton(context, Glyph.SETTINGS, "Screen access settings").apply {
            visibility = View.GONE
            setOnClickListener { hide(); AssistAccess.openSettings(context) }
        }
        pill.addView(settings, LinearLayout.LayoutParams(dp(56), dp(56)))
        dock.addView(pill, LinearLayout.LayoutParams(-1, dp(64)))
        root.addView(dock, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        root.setOnApplyWindowInsetsListener { _, insets ->
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                topInset = bars.top; bottomInset = bars.bottom
            } else {
                @Suppress("DEPRECATION")
                topInset = insets.systemWindowInsetTop
                @Suppress("DEPRECATION")
                bottomInset = insets.systemWindowInsetBottom
            }
            positionControls()
            insets
        }
        positionControls()
        renderCapture()
        return root
    }

    private fun positionControls() {
        (close.layoutParams as FrameLayout.LayoutParams).apply { setMargins(dp(8), topInset + dp(4), 0, 0); close.layoutParams = this }
        (dock.layoutParams as FrameLayout.LayoutParams).apply {
            gravity = Gravity.BOTTOM
            setMargins(dp(20), topInset + dp(68), dp(20), bottomInset + dp(20))
            dock.layoutParams = this
        }
        results?.setPadding(0, 0, 0, bottomInset)
        musicOverlay?.setBottomInset(bottomInset)
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        flags = showFlags
        android.util.Log.i("CircleSession", "show flags=$showFlags capture=${capture.phase}")
        renderCapture()
        if (capture.phase == SingleCapture.Phase.WAITING && flags and SHOW_WITH_SCREENSHOT != 0) handler.postDelayed(captureTimeout, 6000)
    }
    override fun onHandleScreenshot(screenshot: Bitmap?) {
        android.util.Log.i("CircleSession", "screenshot=${screenshot?.let { "${it.width}x${it.height}" } ?: "null"}")
        if (!capture.receive(screenshot)) return
        handler.removeCallbacks(captureTimeout)
        original = screenshot
        renderCapture()
    }
    private fun renderCapture() {
        if (!::selection.isInitialized || musicOverlay != null) return
        val ready = capture.phase == SingleCapture.Phase.READY
        search.isEnabled = ready; colorPicker.isEnabled = ready; music.isEnabled = true
        settings.visibility = if (capture.phase == SingleCapture.Phase.UNAVAILABLE || capture.phase == SingleCapture.Phase.TIMED_OUT) View.VISIBLE else View.GONE
        if (ready) selection.setImage(capture.value)
        status.text = when(capture.phase) {
            SingleCapture.Phase.READY -> "Circle anything to search"
            SingleCapture.Phase.UNAVAILABLE -> AssistAccess.unavailableMessage(context)
            SingleCapture.Phase.TIMED_OUT -> "Screenshot unavailable. Check screen access settings and try again."
            else -> if (flags and SHOW_WITH_SCREENSHOT == 0) "Try launching search from the Quick Settings tile." else "Getting screenshot…"
        }
    }
    private fun setBusy(busy: Boolean, message: String = "Circle anything to search") {
        preparing = busy
        search.isEnabled = !busy && capture.phase == SingleCapture.Phase.READY
        colorPicker.isEnabled = capture.phase == SingleCapture.Phase.READY && !busy
        music.isEnabled = !busy
        selection.isEnabled = !busy
        status.text = message
    }
    private fun writeImage(bitmap: Bitmap): Uri {
        context.cacheDir.listFiles()?.filter { it.name.startsWith("crop-") && System.currentTimeMillis() - it.lastModified() > 86_400_000 }?.forEach { it.delete() }
        val file = File(context.cacheDir, "crop-${UUID.randomUUID()}.png")
        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        return Uri.parse("content://org.circleopensearch.crops/${file.name}")
    }
    private fun processImage(web: Boolean = false) {
        handler.removeCallbacks(autoSearch)
        if (preparing) return
        val image = selection.crop(original ?: return) ?: return
        val version = generation
        setBusy(true, "Searching…")
        worker.execute {
            val uri = runCatching { writeImage(image) }.also { image.recycle() }
            handler.post {
                if (generation == version) {
                    setBusy(false)
                    uri.fold({ if (web) showResults(it) else openLens(it) }, { status.text = "Could not prepare the image. Try again." })
                }
            }
        }
    }
    private fun showMusicOverlay(microphone: Boolean = false) {
        if (musicOverlay != null || preparing) return
        handler.removeCallbacks(autoSearch); handler.removeCallbacks(captureTimeout)
        closeResults()
        // Music uses the live application behind this window, never the captured pixels.
        capture.close(); original = null; selection.setImage(null)
        selection.visibility = View.GONE; dock.visibility = View.GONE; close.visibility = View.GONE
        root.setBackgroundColor(Color.TRANSPARENT)
        window?.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0f)
            @Suppress("DEPRECATION")
            navigationBarColor = Color.TRANSPARENT
            isNavigationBarContrastEnforced = false
            attributes = attributes.apply { windowAnimations = 0 }
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                setDecorFitsSystemWindows(false)
                attributes = attributes.apply { setFitInsetsTypes(0) }
            }
        }
        var ancestor = root.parent
        while (ancestor is View) {
            ancestor.setBackgroundColor(Color.TRANSPARENT); ancestor.fitsSystemWindows = false
            ancestor.setPadding(0, 0, 0, 0); ancestor = ancestor.parent
        }
        musicOverlay = MusicOverlay(context, { openSong(it) }, { searchYouTube(it) }, { hide() }).also {
            root.addView(it, FrameLayout.LayoutParams(-1, -1)); it.setBottomInset(bottomInset)
        }
        MusicState.publish(MusicState.State(if (microphone) "Listening to music around you" else "Preparing device audio capture", true))
        MusicState.add(musicListener)
        requestMusic(microphone)
    }
    private fun requestMusic(microphone: Boolean) {
        if (musicOverlay == null) return
        if (MusicCaptureService.retry(microphone)) return
        if (microphone && context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            runCatching { MusicCaptureService.startMicrophone(context) }
                .onFailure { MusicState.publish(MusicState.State("Could not start microphone capture. Check microphone access and try again.", phase = MusicState.Phase.ERROR)) }
            return
        }
        if (MusicState.value.phase == MusicState.Phase.CONSENT) return
        MusicState.publish(MusicState.State(if (microphone) "Allow microphone access to identify nearby music" else "Allow device audio capture to identify this song", true, phase = MusicState.Phase.CONSENT))
        runCatching { context.startActivity(Intent(context, MusicPermissionActivity::class.java).putExtra("microphone", microphone).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { MusicState.publish(MusicState.State("Could not request audio capture. Close music search and try again.", phase = MusicState.Phase.ERROR)) }
    }
    private fun closeMusicOverlay() {
        if (musicOverlay == null) return
        MusicState.remove(musicListener)
        musicOverlay?.let { root.removeView(it) }; musicOverlay = null
        MusicState.publish(MusicState.State("Music search closed"))
        MusicCaptureService.stop(context)
    }
    private fun openSong(url: String) {
        val parsed = runCatching { Uri.parse(url) }.getOrNull() ?: return
        if (parsed.scheme != "https" || parsed.host !in setOf("www.shazam.com", "shazam.com")) return
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, parsed).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onSuccess { hide() }
    }
    private fun searchYouTube(song: SongMatch) {
        val query = listOf(song.artist, song.title).filter { it.isNotBlank() }.joinToString(" ")
        val url = Uri.Builder().scheme("https").authority("www.youtube.com").path("/results")
            .appendQueryParameter("search_query", query).build()
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onSuccess { hide() }
    }
    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        musicOverlay?.let {
            outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
            if (MusicState.value.phase == MusicState.Phase.CONSENT) outInsets.touchableRegion.setEmpty()
            else outInsets.touchableRegion.set(it.touchRegion())
        }
    }
    private fun showColorPicker() {
        val image = original ?: return
        if (picker != null || preparing) return
        handler.removeCallbacks(autoSearch)
        closeResults()
        picker = ColorPickerOverlay(context, image) { closeColorPicker() }.also {
            root.addView(it, FrameLayout.LayoutParams(-1, -1))
        }
        dock.visibility = View.GONE; close.visibility = View.GONE; selection.visibility = View.GONE
        setImmersive(true)
    }
    private fun closeColorPicker() {
        picker?.let { root.removeView(it) }; picker = null
        if (::dock.isInitialized) {
            dock.visibility = View.VISIBLE; close.visibility = View.VISIBLE; selection.visibility = View.VISIBLE
        }
        setImmersive(false)
    }
    private fun setImmersive(enabled: Boolean) {
        val assistantWindow = window?.window ?: return
        if (enabled) {
            var ancestor = root.parent
            while (ancestor is View) {
                savedAncestors.add(ancestor to intArrayOf(ancestor.paddingLeft, ancestor.paddingTop, ancestor.paddingRight, ancestor.paddingBottom, if (ancestor.fitsSystemWindows) 1 else 0))
                ancestor.fitsSystemWindows = false; ancestor.setPadding(0, 0, 0, 0)
                ancestor = ancestor.parent
            }
            assistantWindow.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        } else {
            for ((view, padding) in savedAncestors) {
                view.fitsSystemWindows = padding[4] == 1; view.setPadding(padding[0], padding[1], padding[2], padding[3])
            }
            savedAncestors.clear()
            assistantWindow.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }
        @Suppress("DEPRECATION")
        assistantWindow.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            (if (enabled) View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY else 0)
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            assistantWindow.attributes = assistantWindow.attributes.apply {
                if (enabled) { savedFitTypes = fitInsetsTypes; setFitInsetsTypes(0) }
                else savedFitTypes?.let { setFitInsetsTypes(it); savedFitTypes = null }
            }
            assistantWindow.setDecorFitsSystemWindows(false)
            assistantWindow.insetsController?.apply {
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                if (enabled) hide(WindowInsets.Type.systemBars()) else show(WindowInsets.Type.systemBars())
            }
        }
    }
    private fun showResults(uri: Uri) {
        closeResults()
        val panel = LensPanel(context, { closeResults() }, { openLens(it) })
        results = panel
        root.addView(panel, FrameLayout.LayoutParams(-1, (root.height * 0.58f).toInt(), Gravity.BOTTOM))
        panel.setPadding(0, 0, 0, bottomInset)
        panel.onDrag = { delta, finished ->
            val params = panel.layoutParams as FrameLayout.LayoutParams
            params.height = (params.height + delta.toInt()).coerceIn(minOf(dp(220), root.height), root.height)
            panel.layoutParams = params
            if (finished && params.height < root.height * 0.35f) closeResults()
        }
        panel.search(uri)
    }
    private fun closeResults() { results?.let { root.removeView(it); it.dispose() }; results = null }
    override fun onBackPressed() {
        if (musicOverlay != null) { hide(); return }
        if (picker != null) { closeColorPicker(); return }
        if (results?.back() == true) return
        if (results != null) closeResults() else hide()
    }
    private fun openLens(uri: Uri) {
        try { LensLauncher.open(context, uri); hide() }
        catch (_: ActivityNotFoundException) { status.text = "Install the Google app or Google Lens to search images." }
        catch (_: SecurityException) { status.text = "Google Lens could not open this image." }
    }
    override fun onHide() {
        generation++; capture.close(); handler.removeCallbacksAndMessages(null)
        closeResults(); closeColorPicker(); closeMusicOverlay(); original = null
        selection.setImage(null)
        super.onHide(); finish()
    }
    override fun onDestroy() {
        generation++; capture.close(); handler.removeCallbacksAndMessages(null)
        closeResults(); closeColorPicker(); closeMusicOverlay(); original = null
        worker.shutdown(); super.onDestroy()
    }
}
