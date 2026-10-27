package org.circleopensearch

/** Maps a full-screen preview to the original screenshot's content pixels. */
class PixelMapping(val width: Int, val height: Int, val top: Int = 0, val bottom: Int = 0) {
    init { require(width > 0 && height > 0 && top >= 0 && bottom >= 0 && top + bottom < height) }
    fun x(position: Float, viewWidth: Int) = (position / viewWidth.coerceAtLeast(1) * width).toInt().coerceIn(0, width - 1)
    fun y(position: Float, viewHeight: Int) = (top + position / viewHeight.coerceAtLeast(1) * (height - top - bottom)).toInt().coerceIn(top, height - bottom - 1)
    companion object { fun hex(color: Int) = "#%06X".format(java.util.Locale.ROOT, color and 0xFFFFFF) }
}
