package org.circleopensearch

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Bundle

/** Transparent, short-lived relay for platform consent. All music UI stays in the assistant. */
class MusicPermissionActivity : Activity() {
    private var answered = false
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        if (state != null) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        } else beginCapture()
    }
    private fun beginCapture() {
        if (!intent.getBooleanExtra("microphone", false)) { requestProjection(); return }
        answered = true
        if (MusicState.value.phase == MusicState.Phase.CONSENT) {
            runCatching { MusicCaptureService.startMicrophone(this) }
                .onFailure { MusicState.publish(MusicState.State("Could not start microphone capture.", phase = MusicState.Phase.ERROR)) }
        }
        finishAndRemoveTask()
    }
    private fun requestProjection() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        val intent = if (android.os.Build.VERSION.SDK_INT >= 34)
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        else manager.createScreenCaptureIntent()
        @Suppress("DEPRECATION")
        startActivityForResult(intent, 2)
    }
    override fun onRequestPermissionsResult(request: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(request, permissions, results)
        if (request != 1) return
        if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) beginCapture()
        else fail("Audio access was denied. Close music search and try again.")
    }
    @Deprecated("Platform activity result API")
    override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        super.onActivityResult(request, result, data)
        if (request != 2) return
        if (result != RESULT_OK || data == null) { fail("Audio capture was cancelled. Close music search and try again."); return }
        answered = true
        // Closing the assistant while a consent dialog was open cancels the pending request.
        if (MusicState.value.phase == MusicState.Phase.CONSENT) {
            runCatching { startForegroundService(Intent(this, MusicCaptureService::class.java).putExtra("projection", data)) }
                .onFailure { MusicState.publish(MusicState.State("Could not start device audio capture.", phase = MusicState.Phase.ERROR)) }
        }
        finishAndRemoveTask()
    }
    private fun fail(message: String) {
        answered = true
        if (MusicState.value.phase == MusicState.Phase.CONSENT)
            MusicState.publish(MusicState.State(message, phase = MusicState.Phase.ERROR))
        finishAndRemoveTask()
    }
    override fun onDestroy() {
        if (isFinishing && !answered && MusicState.value.phase == MusicState.Phase.CONSENT)
            MusicState.publish(MusicState.State("Audio capture was cancelled.", phase = MusicState.Phase.ERROR))
        super.onDestroy()
    }
}
