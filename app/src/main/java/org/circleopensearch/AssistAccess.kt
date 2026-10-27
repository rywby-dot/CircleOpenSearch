package org.circleopensearch

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings

object AssistAccess {
    private fun enabled(context: Context, key: String): Boolean? = runCatching {
        Settings.Secure.getInt(context.contentResolver, key, 1) != 0
    }.getOrNull()
    fun screenContextDisabled(context: Context) = enabled(context, "assist_structure_enabled") == false
    fun screenshotsDisabled(context: Context) = enabled(context, "assist_screenshot_enabled") == false
    fun unavailableMessage(context: Context): String = when {
        screenContextDisabled(context) -> "Enable Use text from screen and Use screenshot in assistant settings."
        screenshotsDisabled(context) -> "Enable Use screenshot in assistant settings."
        else -> "Screenshot unavailable. This app may block screen capture."
    }
    fun openSettings(context: Context) {
        try {
            context.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
