package org.circleopensearch

import android.app.Activity
import android.app.StatusBarManager
import android.app.role.RoleManager
import android.content.*
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.view.*
import android.widget.*

class MainActivity : Activity() {
    private lateinit var assistantSubtitle: TextView
    private lateinit var accessSubtitle: TextView
    private fun dp(value: Int) = Ui.dp(this, value)
    @Suppress("DEPRECATION")
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.statusBarColor = Color.rgb(15, 17, 21)
        window.navigationBarColor = Color.rgb(15, 17, 21)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(24), dp(24), dp(32))
            setBackgroundColor(Color.rgb(15, 17, 21))
        }
        val logo = IconButton(this, Glyph.LENS, "Circle Open Search", true).apply { isClickable = false; isFocusable = false }
        content.addView(logo, LinearLayout.LayoutParams(dp(64), dp(64)).apply { bottomMargin = dp(24) })
        content.addView(label("Circle Open\nSearch", 36f, Ui.foreground).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) })
        content.addView(label("Circle it. Find it. Pick it.", 16f, Ui.secondary).apply { setPadding(0, dp(12), 0, dp(32)) })
        content.addView(label("GET STARTED", 11f, Ui.accent).apply { letterSpacing = 0.14f; setPadding(0, 0, 0, dp(12)) })
        assistantSubtitle = addRow(content, Glyph.CHECK, "Default assistant", "Choose Circle Open Search") {
            val roles = getSystemService(RoleManager::class.java)
            if (roles.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) startActivityForResult(roles.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT), 1)
            else AssistAccess.openSettings(this)
        }
        accessSubtitle = addRow(content, Glyph.SETTINGS, "Screen access", "Allow screenshots in assistant settings") { AssistAccess.openSettings(this) }
        addRow(content, Glyph.TILE, "Quick Settings", "Add a Screen search tile") { addTile() }
        addRow(content, Glyph.SHORTCUT, "Home screen shortcut", "Search with a single tap") { addShortcut() }
        content.addView(label("HOW IT WORKS", 11f, Ui.accent).apply { letterSpacing = 0.14f; setPadding(0, dp(24), 0, dp(12)) })
        content.addView(label("Open any app and launch screen search. Circle an area to search with Google Lens. Tap the eyedropper to pick a color from the same screenshot. Use Google Lens for translation. Tap the music note to identify device audio, or hold it to identify music around you.", 14f, Ui.secondary).apply { setLineSpacing(dp(4).toFloat(), 1f) })
        content.addView(label("One screenshot per launch · Open source", 12f, Ui.secondary).apply { setPadding(0, dp(28), 0, 0) })
        setContentView(ScrollView(this).apply {
            isFillViewport = true; setBackgroundColor(Color.rgb(15,17,21)); addView(content)
            setOnApplyWindowInsetsListener { view, insets ->
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                    view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                } else view.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
                insets
            }
        })
    }
    private fun label(value: String, size: Float, color: Int) = TextView(this).apply { text = value; textSize = size; setTextColor(color) }
    private fun addRow(parent: LinearLayout, glyph: Glyph, title: String, subtitle: String, action: () -> Unit): TextView {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(12))
            background = Ui.ripple(this@MainActivity, Ui.surface); isClickable = true; isFocusable = true
            setOnClickListener { action() }
        }
        row.addView(IconButton(this, glyph, title).apply { isClickable = false; isFocusable = false; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, LinearLayout.LayoutParams(dp(44), dp(44)))
        val text = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8),0,dp(8),0) }
        text.addView(label(title,16f,Ui.foreground))
        val detail = label(subtitle,12f,Ui.secondary).apply { setPadding(0,dp(4),0,0) }
        text.addView(detail)
        row.addView(text, LinearLayout.LayoutParams(0,-2,1f))
        row.addView(IconButton(this,Glyph.EXTERNAL,"Open $title").apply { isClickable=false; isFocusable=false; importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO }, LinearLayout.LayoutParams(dp(32),dp(32)))
        parent.addView(row,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(8) })
        return detail
    }
    override fun onResume() {
        super.onResume()
        assistantSubtitle.text = if (VoiceInteractionService.isActiveService(this,ComponentName(this,SearchAssistant::class.java))) "Circle Open Search is selected" else "Choose Circle Open Search"
        accessSubtitle.text = if (AssistAccess.screenContextDisabled(this) || AssistAccess.screenshotsDisabled(this)) "Enable screen access" else "Screen access is enabled"
    }
    private fun addTile() {
        if (android.os.Build.VERSION.SDK_INT >= 33) getSystemService(StatusBarManager::class.java).requestAddTileService(
            ComponentName(this,SearchTile::class.java),"Screen search",Icon.createWithResource(this,android.R.drawable.ic_menu_search),mainExecutor
        ) { result -> if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED) Toast.makeText(this,"Add the tile from Quick Settings editing",Toast.LENGTH_LONG).show() }
        else Toast.makeText(this,"Open Quick Settings, tap Edit, and add Screen search",Toast.LENGTH_LONG).show()
    }
    private fun addShortcut() {
        val shortcuts = getSystemService(ShortcutManager::class.java)
        if (shortcuts.isRequestPinShortcutSupported) shortcuts.requestPinShortcut(ShortcutInfo.Builder(this,"screen_search")
            .setShortLabel("Screen search").setIcon(Icon.createWithResource(this,applicationInfo.icon))
            .setIntent(Intent(this,LaunchActivity::class.java).setAction("org.circleopensearch.SEARCH")).build(),null)
        else Toast.makeText(this,"Touch and hold the app icon to use the search shortcut",Toast.LENGTH_LONG).show()
    }
}
