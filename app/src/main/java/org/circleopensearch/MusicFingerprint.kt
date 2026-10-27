/*
 * Fingerprint protocol adapted from shazamio-core (MIT).
 * Copyright (c) 2024 dotX12. See assets/THIRD_PARTY_NOTICES.txt.
 */
package org.circleopensearch

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import kotlin.math.*

data class MusicSignature(val bytes: ByteArray, val durationMs: Int, val peaks: Int)

/** Local spectral fingerprint of PCM16 mono at 16 kHz. Raw audio is never uploaded. */
object MusicFingerprint {
    private data class Peak(val pass: Int, val magnitude: Int, val frequency: Int)
    fun generate(samples: ShortArray): MusicSignature {
        require(samples.size in 16000..192000)
        val pcm = ShortArray(2048)
        val powers = Array(256) { FloatArray(1025) }
        val spread = Array(256) { FloatArray(1025) }
        val window = FloatArray(2048) { (0.5 - 0.5 * cos(2 * PI * (it + 1) / 2049)).toFloat() }
        val real = DoubleArray(2048)
        val imaginary = DoubleArray(2048)
        val bands = Array(4) { mutableListOf<Peak>() }
        var sampleIndex = 0
        var frameIndex = 0
        var passes = 0
        for (offset in 0..samples.size - 128 step 128) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            samples.copyInto(pcm, sampleIndex, offset, offset + 128)
            sampleIndex = (sampleIndex + 128) and 2047
            for (i in 0..2047) { real[i] = (pcm[(i + sampleIndex) and 2047] * window[i]).toDouble(); imaginary[i] = 0.0 }
            fft(real, imaginary)
            val power = powers[frameIndex]
            for (i in 0..1024) power[i] = ((real[i] * real[i] + imaginary[i] * imaginary[i]) / 131072).toFloat().coerceAtLeast(1e-10f)
            val current = spread[frameIndex]
            power.copyInto(current)
            for (i in 0..1022) current[i] = maxOf(current[i], current[i + 1], current[i + 2])
            for (previous in intArrayOf(1, 3, 6)) {
                val former = spread[(frameIndex - previous) and 255]
                for (i in 0..1024) former[i] = maxOf(former[i], current[i])
            }
            frameIndex = (frameIndex + 1) and 255
            passes++
            if (passes < 46) continue
            val center = powers[(frameIndex - 46) and 255]
            val comparison = spread[(frameIndex - 49) and 255]
            for (bin in 10..1014) {
                val value = center[bin]
                if (value < 1f / 64 || value < comparison[bin - 1]) continue
                var neighbor = 0f
                for (delta in intArrayOf(-10, -7, -4, -3, 1, 2, 5, 8)) neighbor = maxOf(neighbor, comparison[bin + delta])
                if (value <= neighbor) continue
                for (delta in intArrayOf(-53, -45, 165, 172, 179, 186, 193, 200, 214, 221, 228, 235, 242, 249)) {
                    neighbor = maxOf(neighbor, spread[(frameIndex + delta) and 255][bin - 1])
                }
                if (value <= neighbor) continue
                fun magnitude(v: Float) = ln(v).coerceAtLeast(1f / 64) * 1477.3f + 6144f
                val middle = magnitude(value)
                val before = magnitude(center[bin - 1]); val after = magnitude(center[bin + 1])
                val curvature = middle * 2 - before - after
                if (curvature <= 0) continue
                val frequency = bin * 64 + ((after - before) * 32 / curvature).toInt()
                val hz = (frequency * (8000f / 1024 / 64)).toInt()
                val band = when (hz) { in 250..519 -> 0; in 520..1449 -> 1; in 1450..3499 -> 2; in 3500..5500 -> 3; else -> continue }
                bands[band].add(Peak(passes - 46, middle.toInt().coerceIn(0, 65535), frequency))
            }
        }
        val body = ByteArrayOutputStream()
        fun ByteArrayOutputStream.int32(value: Int) { for (i in 0..3) write(value ushr (8 * i) and 255) }
        fun ByteArrayOutputStream.int16(value: Int) { write(value and 255); write(value ushr 8 and 255) }
        body.int32(0x40000000); body.int32(0)
        for (band in 0..3) {
            if (bands[band].isEmpty()) continue
            val chunk = ByteArrayOutputStream()
            var previous = 0
            for (peak in bands[band]) {
                if (peak.pass - previous >= 255) { chunk.write(255); chunk.int32(peak.pass); previous = peak.pass }
                chunk.write(peak.pass - previous); chunk.int16(peak.magnitude); chunk.int16(peak.frequency)
                previous = peak.pass
            }
            body.int32(0x60030040 + band); body.int32(chunk.size()); chunk.writeTo(body)
            repeat((4 - chunk.size() % 4) % 4) { body.write(0) }
        }
        val payload = body.toByteArray()
        ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).putInt(4, payload.size)
        val encoded = ByteBuffer.allocate(48 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        encoded.putInt(0xcafe2580.toInt()).putInt(0).putInt(payload.size).putInt(0x94119c00.toInt())
        repeat(3) { encoded.putInt(0) }
        encoded.putInt(3 shl 27).putInt(0).putInt(0).putInt(samples.size + 3840).putInt((15 shl 19) + 0x40000)
        encoded.put(payload)
        val bytes = encoded.array()
        encoded.putInt(4, CRC32().apply { update(bytes, 8, bytes.size - 8) }.value.toInt())
        return MusicSignature(bytes, samples.size * 1000 / 16000, bands.sumOf { it.size })
    }
    private fun fft(real: DoubleArray, imaginary: DoubleArray) {
        val size = real.size
        var reversed = 0
        for (i in 1 until size) {
            var bit = size shr 1
            while (reversed and bit != 0) { reversed = reversed xor bit; bit = bit shr 1 }
            reversed = reversed xor bit
            if (i < reversed) { val r = real[i]; real[i] = real[reversed]; real[reversed] = r
                val im = imaginary[i]; imaginary[i] = imaginary[reversed]; imaginary[reversed] = im }
        }
        var length = 2
        while (length <= size) {
            val angle = -2 * PI / length
            val stepReal = cos(angle); val stepImaginary = sin(angle)
            for (start in 0 until size step length) {
                var wr = 1.0; var wi = 0.0
                for (j in 0 until length / 2) {
                    val a = start + j; val b = a + length / 2
                    val r = real[b] * wr - imaginary[b] * wi
                    val im = real[b] * wi + imaginary[b] * wr
                    real[b] = real[a] - r; imaginary[b] = imaginary[a] - im
                    real[a] += r; imaginary[a] += im
                    val next = wr * stepReal - wi * stepImaginary
                    wi = wr * stepImaginary + wi * stepReal; wr = next
                }
            }
            length *= 2
        }
    }
}
