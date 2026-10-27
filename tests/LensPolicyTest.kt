package org.circleopensearch

fun main() {
    val upload = listOf("https://lens.google.com/", "https://www.google.com/imghp?hl=ru", "https://images.google.com:443/", "HTTPS://LENS.GOOGLE.COM/")
    val rejected = listOf(null, "", "http://lens.google.com/", "https://lens.google.com.evil.test/", "https://evil.test/lens.google.com", "https://lens.google.com@evil.test/", "https://evil@lens.google.com/", "https://lens.google.com:8443/", "file:///tmp/crop.png", "content://org.circleopensearch.crops/crop.png", "javascript:alert(1)", "https://accounts.google.com/", "https://lens.google.com%2e.evil.test/", "https://lens.google.com\\@evil.test/")
    upload.forEach { check(LensPolicy.mayUpload(it)) { "Rejected trusted page: $it" } }
    rejected.forEach { check(!LensPolicy.mayUpload(it)) { "Allowed upload: $it" } }
    check(LensPolicy.mayBrowse("https://consent.google.com/"))
    check(!LensPolicy.mayBrowse("https://notgoogle.com/"))
    check(!LensPolicy.mayBrowse("https://google.com.evil.test/"))
    println("LensPolicy: ${upload.size + rejected.size + 3} checks passed")
}
