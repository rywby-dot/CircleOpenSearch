package org.circleopensearch.musicTests
import org.circleopensearch.MusicFingerprint
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import kotlin.math.*
fun main() {
 val silent=MusicFingerprint.generate(ShortArray(96000))
 check(silent.peaks==0);check(silent.bytes.size==56);check(silent.durationMs==6000)
 val samples=ShortArray(96000) { i ->
  val time=i/16000.0
  val phase=(time*3).toInt()%4
  val frequency=listOf(350.0,850.0,2200.0,4400.0)[phase]
  (18000*sin(2*PI*(frequency*time+40*time*time))*sin(PI*((time*3)%1))).toInt().toShort()
 }
 val result=MusicFingerprint.generate(samples)
 check(result.peaks>=12) { "Spectral landmarks missing: ${result.peaks}" }
 val header=ByteBuffer.wrap(result.bytes).order(ByteOrder.LITTLE_ENDIAN)
 check(header.getInt(0)==0xcafe2580.toInt());check(header.getInt(12)==0x94119c00.toInt())
 check(header.getInt(8)==result.bytes.size-48);check(header.getInt(52)==result.bytes.size-48)
 check(header.getInt(28)==3 shl 27);check(header.getInt(40)==96000+3840)
 check(header.getInt(4)==CRC32().apply {update(result.bytes,8,result.bytes.size-8)}.value.toInt())
 check(result.bytes.contentEquals(MusicFingerprint.generate(samples).bytes))
 check(runCatching {MusicFingerprint.generate(ShortArray(1))}.isFailure)
 println("MusicFingerprint: silence, swept tones, header, CRC and deterministic landmarks passed (${result.peaks} peaks)")
}