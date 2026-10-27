package org.circleopensearch

import android.app.Activity
import android.app.role.RoleManager
import android.content.*
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.TileService
import android.service.voice.*
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.widget.*

class SearchAssistant : VoiceInteractionService() {
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            showSession(Bundle(), VoiceInteractionSession.SHOW_WITH_ASSIST or VoiceInteractionSession.SHOW_WITH_SCREENSHOT)
        }
    }
    override fun onReady() {
        super.onReady()
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, IntentFilter(ACTION), "org.circleopensearch.permission.LAUNCH", null, Context.RECEIVER_NOT_EXPORTED)
        else { @Suppress("DEPRECATION") registerReceiver(receiver, IntentFilter(ACTION), "org.circleopensearch.permission.LAUNCH", null) }
    }
    override fun onShutdown() { unregisterReceiver(receiver); super.onShutdown() }
    companion object { const val ACTION = "org.circleopensearch.INTERNAL_SEARCH" }
}

fun launchSearch(context: Context) {
    if (!VoiceInteractionService.isActiveService(context, ComponentName(context, SearchAssistant::class.java))) {
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return
    }
    context.sendBroadcast(Intent(SearchAssistant.ACTION).setPackage(context.packageName))
}

class LaunchActivity : Activity() {
    override fun onCreate(state: Bundle?) { super.onCreate(state); finish(); Handler(Looper.getMainLooper()).postDelayed({ launchSearch(this) }, 250) }
}

class SearchTile : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            label = "Screen search"
            contentDescription = "Search this screen"
            state = if (VoiceInteractionService.isActiveService(this@SearchTile, ComponentName(this@SearchTile, SearchAssistant::class.java))) android.service.quicksettings.Tile.STATE_ACTIVE else android.service.quicksettings.Tile.STATE_INACTIVE
            updateTile()
        }
    }
    override fun onClick() {
        super.onClick()
        val intent = Intent(this, LaunchActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (android.os.Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(android.app.PendingIntent.getActivity(this, 0, intent, android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT))
        else { @Suppress("DEPRECATION") startActivityAndCollapse(intent) }
    }
}

class NoSpeechService : RecognitionService() {
    override fun onStartListening(intent: Intent?, listener: Callback) { listener.error(SpeechRecognizer.ERROR_CLIENT) }
    override fun onStopListening(listener: Callback) {}
    override fun onCancel(listener: Callback) {}
}
class SearchSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = SearchSession(this)
}
