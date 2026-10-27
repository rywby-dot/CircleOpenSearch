package org.circleopensearch

import java.net.URI

/** Only the image-search pages may receive the selected screenshot. */
object LensPolicy {
    fun mayUpload(url: String?): Boolean = host(url) in setOf("lens.google.com", "www.google.com", "images.google.com")
    fun mayBrowse(url: String?): Boolean {
        val host = host(url) ?: return false
        return host == "google.com" || host.endsWith(".google.com")
    }
    private fun host(url: String?): String? = try {
        val uri = URI(url ?: "")
        if (!uri.scheme.equals("https", true) || uri.rawUserInfo != null || uri.port !in setOf(-1, 443)) null
        else uri.host?.lowercase(java.util.Locale.ROOT)
    } catch (_: Exception) { null }
}
