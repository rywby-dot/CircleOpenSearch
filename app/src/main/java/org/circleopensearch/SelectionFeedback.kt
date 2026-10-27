package org.circleopensearch

import android.graphics.*
import android.os.SystemClock
import kotlin.math.exp
import kotlin.math.pow

/** Transparent colour washes with a time-based touch trail; no particles or stroke glow. */
internal class SelectionFeedback(private val density: Float) {
    private data class Sample(val x: Float, val y: Float, val time: Long)
    private data class Bloom(val x: Float, val y: Float, val rx: Float, val ry: Float, val color: Int)
    private val samples = ArrayDeque<Sample>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val matrix = Matrix()
    private val colors = intArrayOf(0x4285F4, 0xEA4335, 0xFBBC05, 0x34A853)
    private val shaders = colors.map { color ->
        // A Gaussian falloff reaches zero with a flat slope: no visible disc boundary.
        val stops = FloatArray(33) { it / 32f }
        val shades = stops.map { t ->
            val envelope = (exp(-6f*t*t) - exp(-6f)) / (1f-exp(-6f))
            val fade = (1f-t*t).coerceAtLeast(0f)
            ((72f*envelope*fade).toInt() shl 24) or color
        }.toIntArray()
        RadialGradient(0f, 0f, 1f, shades, stops, Shader.TileMode.CLAMP)
    }
    private var edges = emptyList<Bloom>()
    private var activated = 0L
    private var stopped = 0L
    private var pointerX = 0f
    private var pointerY = 0f
    private var touching = false
    private val trailDuration = 500L

    fun activate() { activated = SystemClock.uptimeMillis(); touching = false; samples.clear() }
    fun resize(w: Int, h: Int) {
        val width = w.toFloat(); val height = h.toFloat()
        val depth = 160f*density
        // Overlapping elongated washes, rather than four straight gradient strips.
        edges = listOf(
            Bloom(0f,height*0.12f,depth,height*0.40f,0),
            Bloom(0f,height*0.48f,depth,height*0.44f,3),
            Bloom(0f,height*0.86f,depth,height*0.40f,1),
            Bloom(width,height*0.12f,depth,height*0.40f,1),
            Bloom(width,height*0.48f,depth,height*0.44f,2),
            Bloom(width,height*0.86f,depth,height*0.40f,0),
            Bloom(width*0.12f,0f,width*0.48f,depth,0),
            Bloom(width*0.54f,0f,width*0.52f,depth,1),
            Bloom(width*0.94f,0f,width*0.42f,depth,2),
            Bloom(width*0.12f,height,width*0.48f,depth,1),
            Bloom(width*0.54f,height,width*0.52f,depth,2),
            Bloom(width*0.94f,height,width*0.42f,depth,3))
    }
    fun start(x: Float, y: Float) {
        touching = true; samples.clear(); pointerX = x; pointerY = y
        samples.addLast(Sample(x,y,SystemClock.uptimeMillis()))
    }
    fun move(x: Float, y: Float, time: Long = SystemClock.uptimeMillis()) {
        pointerX = x; pointerY = y
        if (samples.isNotEmpty() && time <= samples.last().time) samples.removeLast()
        samples.addLast(Sample(x,y,time))
        trim(time)
    }
    fun stop() { touching = false; stopped = SystemClock.uptimeMillis() }
    private fun trim(now: Long) {
        // Retain one sample before the cutoff for interpolation at exactly 500 ms.
        while (samples.size > 2 && samples.elementAt(1).time < now-trailDuration) samples.removeFirst()
    }
    private fun bloom(canvas: Canvas, x: Float, y: Float, rx: Float, ry: Float, color: Int, intensity: Float) {
        val shader = shaders[color]
        matrix.setScale(rx,ry); matrix.postTranslate(x,y); shader.setLocalMatrix(matrix)
        paint.shader = shader; paint.alpha = (255f*intensity).toInt().coerceIn(0,255)
        canvas.drawOval(x-rx,y-ry,x+rx,y+ry,paint)
    }
    private fun touchGlow(canvas: Canvas, x: Float, y: Float, intensity: Float) {
        val radius = 45f*density; val offset = 13f*density
        bloom(canvas,x-offset,y-offset,radius,radius,0,intensity)
        bloom(canvas,x+offset,y-offset,radius,radius,1,intensity)
        bloom(canvas,x+offset,y+offset,radius,radius,2,intensity)
        bloom(canvas,x-offset,y+offset,radius,radius,3,intensity)
    }
    fun draw(canvas: Canvas): Boolean {
        val now = SystemClock.uptimeMillis()
        val elapsed = now-activated
        val entry = (elapsed/650f).coerceIn(0f,1f).let { it*it*(3f-2f*it) }
        paint.style = Paint.Style.FILL
        for (edge in edges) bloom(canvas,edge.x,edge.y,edge.rx,edge.ry,edge.color,entry*0.85f)
        trim(now)
        if (samples.isNotEmpty()) {
            // Fixed time slices keep the tail's length in time, independent of input rate or speed.
            val points = samples.toList()
            for (age in trailDuration downTo 50L step 50L) {
                val time = now-age
                if (time < points.first().time || time > points.last().time) continue
                val right = points.indexOfFirst { it.time >= time }
                val b = points[right]; val a = points[(right-1).coerceAtLeast(0)]
                val mix = if (a.time == b.time) 0f else (time-a.time).toFloat()/(b.time-a.time)
                val strength = (1f-age.toFloat()/trailDuration).pow(2)*0.14f
                touchGlow(canvas,a.x+(b.x-a.x)*mix,a.y+(b.y-a.y)*mix,strength)
            }
            val head = if (touching) 1f else (1f-(now-stopped).toFloat()/trailDuration).coerceIn(0f,1f).pow(2)
            if (head > 0f) touchGlow(canvas,pointerX,pointerY,head)
            if (!touching && now-stopped >= trailDuration) samples.clear()
        }
        paint.shader = null; paint.alpha = 255
        return touching || samples.isNotEmpty() || elapsed < 650
    }
}
