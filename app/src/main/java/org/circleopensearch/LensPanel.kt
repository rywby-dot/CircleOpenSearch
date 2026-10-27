package org.circleopensearch

import android.annotation.SuppressLint
import android.content.*
import android.graphics.Color
import android.net.Uri
import android.view.*
import android.webkit.*
import android.widget.*

/** Browser surface for the public Lens upload UI. No private HTTP endpoints or JS bridge. */
@SuppressLint("SetJavaScriptEnabled")
class LensPanel(context: Context, private val onClose: () -> Unit, private val onExternal: (Uri) -> Unit) : LinearLayout(context) {
    private val web = WebView(context)
    private val externalButton: IconButton
    private val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal)
    private val address = TextView(context)
    private val hint = TextView(context)
    private val uploadBox = LinearLayout(context).apply { orientation = VERTICAL; visibility = GONE }
    private var image: Uri? = null
    private var alive = true
    var onDrag: ((Float, Boolean) -> Unit)? = null
    private var lastY = 0f

    init {
        orientation = VERTICAL
        background = android.graphics.drawable.GradientDrawable().apply { setColor(Color.rgb(26, 29, 36)); cornerRadius = 24 * resources.displayMetrics.density }
        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        val handle = TextView(context).apply {
            text = "━━"; textSize = 22f; gravity = Gravity.CENTER; setTextColor(Color.LTGRAY)
            contentDescription = "Resize results"
            setOnTouchListener { _, event ->
                when(event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> lastY = event.rawY
                    MotionEvent.ACTION_MOVE -> { onDrag?.invoke(lastY - event.rawY, false); lastY = event.rawY }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { onDrag?.invoke(0f, true); performClick() }
                }
                true
            }
        }
        header.addView(handle, LayoutParams(0, -2, 1f))
        externalButton = IconButton(context, Glyph.EXTERNAL, "Open selection in Google Lens").apply { setOnClickListener { image?.let(onExternal) } }
        header.addView(externalButton, LayoutParams(Ui.dp(context,48), Ui.dp(context,48)))
        header.addView(IconButton(context,Glyph.CLOSE,"Close results").apply { setOnClickListener { onClose() } }, LayoutParams(Ui.dp(context,48), Ui.dp(context,48)))
        addView(header)
        address.apply { textSize = 12f; setTextColor(Color.LTGRAY); setPadding(16, 0, 16, 4) }
        addView(address)
        hint.apply {
            text = "Tap the camera icon, then Upload a file to search this selection."
            textSize = 13f; setTextColor(Color.WHITE); setPadding(16, 4, 16, 8)
        }
        addView(hint)
        addView(uploadBox, LayoutParams(-1, -2))
        addView(progress, LayoutParams(-1, 4))
        addView(web, LayoutParams(-1, 0, 1f))
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false)
            useWideViewPort = true
            loadWithOverviewMode = true
            // Google's documented upload-file workflow is the desktop interface.
            userAgentString = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                if (LensPolicy.mayBrowse(request.url.toString())) return false
                if (request.url.scheme == "https") {
                    try { context.startActivity(Intent(Intent.ACTION_VIEW, request.url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    catch (_: ActivityNotFoundException) { hint.text = "No browser is available to open this link." }
                }
                return true
            }
            override fun onPageFinished(view: WebView, url: String?) { address.text = Uri.parse(url ?: "").host ?: "Google Lens" }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) hint.text = "Page unavailable. Check your connection or open Google Lens."
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, value: Int) { progress.progress = value; progress.visibility = if (value == 100) GONE else VISIBLE }
            override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                // WebView does not expose the requesting frame's origin.
                // FileChooser is permitted only through a native, explicit confirmation.
                val crop = image
                if (!alive || crop == null || !LensPolicy.mayUpload(view.url)) { callback.onReceiveValue(null); return true }
                pending?.onReceiveValue(null)
                pending = callback
                val preview = ImageView(context).apply {
                    setImageURI(crop); adjustViewBounds = true; maxHeight = (180 * resources.displayMetrics.density).toInt()
                    contentDescription = "Selected image to send to Google Lens"
                }
                uploadBox.removeAllViews()
                uploadBox.addView(preview, LayoutParams(-1, (180 * resources.displayMetrics.density).toInt()))
                uploadBox.addView(IconButton(context,Glyph.SEARCH,"Search with Google Lens").apply {
                    setOnClickListener { completeUpload(arrayOf(crop)) }
                })
                uploadBox.addView(IconButton(context,Glyph.CLOSE,"Cancel upload").apply {
                    setOnClickListener { completeUpload(null) }
                })
                uploadBox.visibility = VISIBLE
                return true
            }
        }
    }
    private var pending: ValueCallback<Array<Uri>>? = null
    private fun completeUpload(value: Array<Uri>?) { val cb = pending; pending = null; uploadBox.visibility = GONE; uploadBox.removeAllViews(); cb?.onReceiveValue(value) }
    fun search(uri: Uri) {
        image = uri
        externalButton.isEnabled = true
        hint.visibility = VISIBLE
        hint.text = "Tap the camera icon, then Upload a file to search this selection."
        web.loadUrl("https://www.google.com/imghp?hl=en")
    }
    fun searchText(text: String) {
        image = null
        externalButton.isEnabled = false
        hint.visibility = GONE
        val url = Uri.Builder().scheme("https").authority("www.google.com").path("/search").appendQueryParameter("q", text).build()
        web.loadUrl(url.toString())
    }
    fun back(): Boolean { if (!web.canGoBack()) return false; web.goBack(); return true }
    fun dispose() {
        alive = false
        completeUpload(null)
        image = null
        web.stopLoading(); web.loadUrl("about:blank"); web.removeAllViews(); web.destroy()
    }
}
