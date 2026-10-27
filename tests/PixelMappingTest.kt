package org.circleopensearch.pickerTests
import org.circleopensearch.PixelMapping
fun main() {
 val map = PixelMapping(1080, 2400, 90, 60)
 check(map.x(0f,540)==0); check(map.x(540f,540)==1079)
 check(map.y(0f,1200)==90); check(map.y(1200f,1200)==2339)
 check(map.y(600f,1200)==1215); check(map.x(-100f,540)==0)
 check(map.y(-10f,1200)==90); check(map.y(1300f,1200)==2339)
 check(PixelMapping.hex(0xff0123ab.toInt())=="#0123AB")
 check(PixelMapping.hex(0x00123456)=="#123456")
 check(runCatching { PixelMapping(10,10,6,6) }.isFailure)
 println("PixelMapping: crop edges, scaled coordinates, clamps and HEX passed")
}