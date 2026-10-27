package org.circleopensearch

import android.util.Base64
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

data class SongMatch(val title: String, val artist: String, val url: String)

/** Unofficial Shazam fingerprint endpoint; no API key, account, or microphone. */
class SongRecognizer {
    private val cancelled = AtomicBoolean(false)
    @Volatile private var active: HttpURLConnection? = null
    fun cancel() { cancelled.set(true); active?.disconnect() }
    fun recognize(samples: ShortArray): SongMatch? {
        check(!cancelled.get())
        val signature = MusicFingerprint.generate(samples)
        if (signature.peaks == 0) return null
        val timestamp = System.currentTimeMillis()
        val body = JSONObject().put("timezone", TimeZone.getDefault().id).put("timestamp", timestamp)
            .put("context", JSONObject()).put("geolocation", JSONObject())
            .put("signature", JSONObject().put("samplems", signature.durationMs)
                .put("uri", "data:audio/vnd.shazam.sig;base64," + Base64.encodeToString(signature.bytes, Base64.NO_WRAP)))
        val url = URI("https://amp.shazam.com/discovery/v5/en-US/GB/android/-/tag/${UUID.randomUUID()}/${UUID.randomUUID()}?sync=true&webv3=true&sampling=true&shazamapiversion=v3&sharehub=true&hubv5minorversion=v5.1&hidelb=true&video=v3").toURL()
        val connection = url.openConnection() as HttpURLConnection
        active = connection
        try {
            check(!cancelled.get())
            connection.connectTimeout = 8000; connection.readTimeout = 12000
            connection.requestMethod = "POST"; connection.doOutput = true; connection.instanceFollowRedirects = false
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "CircleOpenSearch/0.4 (Android)")
            connection.setRequestProperty("X-Shazam-Platform", "ANDROID")
            connection.setRequestProperty("X-Shazam-AppVersion", "14.1.0")
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            if (connection.responseCode != 200) throw IOException("Recognition HTTP ${connection.responseCode}")
            val response = JSONObject(connection.inputStream.bufferedReader().use { reader ->
                val data = reader.readText(); check(data.length < 2_000_000); data })
            check(!cancelled.get())
            val matches = response.optJSONArray("matches") ?: return null
            if (matches.length() == 0) return null
            val track = response.optJSONObject("track") ?: return null
            val title = track.optString("title"); val artist = track.optString("subtitle")
            if (title.isBlank()) return null
            return SongMatch(title, artist, track.optString("url"))
        } finally { connection.disconnect(); if (active === connection) active = null }
    }
}
