package org.circleopensearch

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/** Keeps consent only while the assistant music panel is open. Idle sessions do not record audio. */
class MusicCaptureService : Service() {
    private class Attempt {
        val done = AtomicBoolean(false)
        val recognizer = SongRecognizer()
        @Volatile var audio: AudioRecord? = null
        var hadAudio = false
    }
    private val main = Handler(Looper.getMainLooper())
    private val captureWorker = Executors.newSingleThreadExecutor()
    private val recognitionWorker = Executors.newSingleThreadExecutor()
    private var projection: MediaProjection? = null
    private var current: Attempt? = null
    private var microphone = false
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, id: Int): Int {
        if (intent?.action == "stop") { stopSelf(); return START_NOT_STICKY }
        if (active === this) { startAttempt(); return START_NOT_STICKY }
        microphone = intent?.getBooleanExtra("microphone", false) == true
        try {
            val notifications = getSystemService(NotificationManager::class.java)
            notifications.createNotificationChannel(NotificationChannel("music_capture", "Music recognition", NotificationManager.IMPORTANCE_LOW))
            updateNotification(true)
            if (microphone) {
                active = this
                startAttempt()
                return START_NOT_STICKY
            }
            @Suppress("DEPRECATION")
            val data = intent?.getParcelableExtra<Intent>("projection") ?: error("Missing consent")
            val token = getSystemService(MediaProjectionManager::class.java).getMediaProjection(Activity.RESULT_OK, data)
            projection = token; active = this
            token.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    if (projection != null) {
                        MusicState.publish(MusicState.State("Audio capture was stopped. Close music search and try again.", phase = MusicState.Phase.ERROR))
                        stopSelf()
                    }
                }
            }, main)
            // The consent relay finishes before playback capture begins.
            main.postDelayed({ if (projection != null) startAttempt() }, 200)
        } catch (error: Exception) {
            android.util.Log.w("CircleMusic", "Audio capture unavailable", error)
            MusicState.publish(MusicState.State(if (microphone) "Microphone capture is unavailable. Check microphone access and try again." else "Device audio capture is unavailable. Check audio access and try again.", phase = MusicState.Phase.ERROR))
            stopSelf()
        }
        return START_NOT_STICKY
    }
    private fun updateNotification(listening: Boolean) {
        val stop = PendingIntent.getService(this, 1, Intent(this, MusicCaptureService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, "music_capture").setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(if (listening) { if (microphone) "Identifying nearby music" else "Identifying device audio" } else "Music recognition ready")
            .setContentText(if (listening) { if (microphone) "Listening with the microphone" else "Playback capture only · Microphone off" } else "No audio is being recorded · Close the music panel to stop")
            .setOngoing(true).addAction(Notification.Action.Builder(null, "Stop", stop).build()).build()
        val type = if (!microphone) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            else if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        startForeground(41, notification, type)
    }
    private fun startAttempt() {
        if (!microphone && projection == null) return
        if (current?.done?.get() == false) return
        val attempt = Attempt(); current = attempt
        try {
            val format = AudioFormat.Builder().setSampleRate(16000).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()
            val minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            require(minimum > 0)
            val builder = AudioRecord.Builder().setAudioFormat(format).setBufferSizeInBytes(maxOf(minimum * 4, 16384))
            if (microphone) builder.setAudioSource(MediaRecorder.AudioSource.MIC)
            else builder.setAudioPlaybackCaptureConfig(AudioPlaybackCaptureConfiguration.Builder(projection!!)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA).addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).excludeUid(android.os.Process.myUid()).build())
            val audio = builder.build()
            attempt.audio = audio
            check(audio.state == AudioRecord.STATE_INITIALIZED)
            MusicState.publish(MusicState.State(if (microphone) "Listening to music around you" else "Listening to the music playing on your device", true,
                phase = MusicState.Phase.LISTENING, ready = true))
            updateNotification(true)
            captureWorker.execute { capture(attempt, audio) }
            main.postDelayed({ if (current === attempt && !attempt.done.get()) finishAttempt(attempt, "Recognition timed out. Try again while music is playing.") }, 40000)
        } catch (error: Exception) {
            android.util.Log.w("CircleMusic", "Audio capture unavailable", error)
            finishAttempt(attempt, if (microphone) "Microphone capture is unavailable. Check microphone access." else "Device audio capture is unavailable. Try another app or track.")
        }
    }
    private fun capture(attempt: Attempt, audio: AudioRecord) {
        val samples = ShortArray(192000)
        try {
            var count = 0
            var queried = false
            var lastMeter = 0L
            audio.startRecording()
            check(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING)
            while (!attempt.done.get() && count < samples.size) {
                val n = audio.read(samples, count, minOf(2048, samples.size - count), AudioRecord.READ_BLOCKING)
                if (n < 0) error("Audio read failed: $n")
                if (n == 0) continue
                var energy = 0.0
                for (i in count until count + n) {
                    val value = samples[i].toDouble(); energy += value * value
                    if (kotlin.math.abs(value) > 10) attempt.hadAudio = true
                }
                val now = SystemClock.elapsedRealtime()
                if (now - lastMeter > 80) {
                    lastMeter = now
                    val level = (sqrt(energy / n) / 9000).toFloat().coerceIn(0f, 1f)
                    main.post { if (current === attempt && !attempt.done.get() && MusicState.value.phase == MusicState.Phase.LISTENING)
                        MusicState.publish(MusicState.value.copy(level = level)) }
                }
                count += n
                if (!queried && count >= 96000) { queried = true; submit(attempt, samples.copyOf(96000), false) }
            }
            if (!attempt.done.get()) {
                main.post {
                    stopAudio(attempt)
                    if (current === attempt && !attempt.done.get()) MusicState.publish(MusicState.State("Finding a match for your music…", true,
                        phase = MusicState.Phase.MATCHING, ready = true))
                }
                submit(attempt, samples.copyOf(count), true)
            }
        } catch (error: Exception) {
            if (!attempt.done.get()) {
                android.util.Log.w("CircleMusic", "Audio capture failed", error)
                finishAttempt(attempt, if (microphone) "Could not read microphone audio. Check microphone access." else "Could not read device audio. Try another app or track.")
            }
        } finally { samples.fill(0) }
    }
    private fun submit(attempt: Attempt, samples: ShortArray, final: Boolean) {
        if (attempt.done.get()) { samples.fill(0); return }
        recognitionWorker.execute {
            try {
                if (attempt.done.get()) return@execute
                val startedAt = SystemClock.elapsedRealtime()
                val result = attempt.recognizer.recognize(samples)
                android.util.Log.i("CircleMusic", "Fingerprint lookup ${samples.size / 16000}s: matched=${result != null}, ${SystemClock.elapsedRealtime() - startedAt}ms")
                if (result != null) finishAttempt(attempt, "Found your song", result)
                else if (final) finishAttempt(attempt, if (attempt.hadAudio) "No match yet. Try another part of the song."
                    else if (microphone) "No microphone audio received. Check microphone access and move closer to the music."
                    else "No device audio received. Play music; the app may block audio capture.")
            } catch (error: Exception) {
                if (!attempt.done.get()) {
                    android.util.Log.w("CircleMusic", "Recognition failed", error)
                    finishAttempt(attempt, "Could not reach the recognition service. Check your connection and try again.")
                }
            } finally { samples.fill(0) }
        }
    }
    private fun finishAttempt(attempt: Attempt, message: String, song: SongMatch? = null) {
        if (!attempt.done.compareAndSet(false, true)) return
        main.post {
            if (current !== attempt || active !== this) return@post
            stopAudio(attempt)
            attempt.recognizer.cancel()
            MusicState.publish(MusicState.State(message, song = song,
                phase = if (song != null) MusicState.Phase.FOUND else MusicState.Phase.ERROR, ready = true))
            updateNotification(false)
        }
    }
    private fun stopAudio(attempt: Attempt) {
        val audio = attempt.audio; attempt.audio = null
        runCatching { audio?.stop() }; runCatching { audio?.release() }
    }
    override fun onDestroy() {
        active = null
        main.removeCallbacksAndMessages(null)
        current?.let { it.done.set(true); it.recognizer.cancel(); stopAudio(it) }; current = null
        val token = projection; projection = null
        runCatching { token?.stop() }
        captureWorker.shutdownNow(); recognitionWorker.shutdownNow()
        val state = MusicState.value
        MusicState.publish(if (state.running) MusicState.State("Audio capture was stopped. Close music search and try again.", phase = MusicState.Phase.ERROR)
            else state.copy(ready = false, level = 0f))
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    companion object {
        private var active: MusicCaptureService? = null
        fun retry(microphone: Boolean): Boolean {
            val service = active ?: return false
            if (service.microphone != microphone || (!microphone && service.projection == null)) return false
            service.startAttempt(); return true
        }
        fun startMicrophone(context: Context) {
            context.startForegroundService(Intent(context, MusicCaptureService::class.java).putExtra("microphone", true))
        }
        fun stop(context: Context) { context.stopService(Intent(context, MusicCaptureService::class.java)) }
    }
}
