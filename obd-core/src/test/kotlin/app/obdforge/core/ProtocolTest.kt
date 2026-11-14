package app.obdforge.core

import app.obdforge.core.elm.ElmError
import app.obdforge.core.elm.ObdProtocol
import app.obdforge.core.isotp.IsoTp
import app.obdforge.core.isotp.IsoTpReassembler
import app.obdforge.core.obd.FrameParser
import app.obdforge.core.util.Hex
import app.obdforge.core.util.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {
    @Test fun hexRoundTrip() {
        assertEquals("41 0C 1A F8", Hex.encode(intArrayOf(0x41, 0x0C, 0x1A, 0xF8).toList()))
        assertEquals(listOf(0x41, 0x0C, 0x1A, 0xF8), Hex.decode("410C1AF8")!!.toList())
        assertEquals(listOf(0x41, 0x0C), Hex.decode("41 0c")!!.toList())
        assertNull(Hex.decode("41 0G"))
        assertNull(Hex.decode("410"))
    }

    @Test fun jsonRoundTrip() {
        val src = mapOf("a" to 1, "b" to listOf(1.5, "x\"y", null, true), "c" to mapOf("d" to "°C λ\n"))
        val back = Json.parse(Json.write(src, pretty = true)) as Map<*, *>
        assertEquals(1L, back["a"])
        assertEquals("x\"y", (back["b"] as List<*>)[1])
        assertEquals("°C λ\n", (back["c"] as Map<*, *>)["d"])
    }

    @Test fun isoTpSegmentAndReassemble() {
        val vin = intArrayOf(0x49, 0x02, 0x01) + "1HGCM82633A004352".map { it.code }.toIntArray()
        val frames = IsoTp.segment(vin)
        assertEquals(3, frames.size)
        assertEquals(0x10, frames[0][0]); assertEquals(20, frames[0][1])
        assertEquals(0x21, frames[1][0]); assertEquals(0x22, frames[2][0])
        val r = IsoTpReassembler()
        frames.forEach { r.accept(0x7E8, it) }
        assertEquals(vin.toList(), r.messages().single().payload.toList())
    }

    @Test fun isoTpInterleavedEcus() {
        val a = IntArray(20) { it }; val b = IntArray(10) { 100 + it }
        val fa = IsoTp.segment(a); val fb = IsoTp.segment(b)
        val r = IsoTpReassembler()
        r.accept(0x7E8, fa[0]); r.accept(0x7E9, fb[0]); r.accept(0x7E8, fa[1]); r.accept(0x7E9, fb[1]); r.accept(0x7E8, fa[2])
        val msgs = r.messages().associateBy { it.canId }
        assertEquals(a.toList(), msgs[0x7E8]!!.payload.toList())
        assertEquals(b.toList(), msgs[0x7E9]!!.payload.toList())
    }

    @Test fun isoTpSequenceErrorDetected() {
        val f = IsoTp.segment(IntArray(30) { it })
        val r = IsoTpReassembler()
        r.accept(0x7E8, f[0]); r.accept(0x7E8, f[2]) // skipped frame 1
        assertTrue(r.messages().isEmpty())
        assertTrue(r.errors.any { it.contains("Sequence") })
    }

    @Test fun flowControlParsing() {
        val fc = IsoTp.parseFlowControl(intArrayOf(0x30, 0x08, 0xF3))!!
        assertEquals(IsoTp.FlowControl.Status.CONTINUE, fc.status)
        assertEquals(8, fc.blockSize); assertEquals(300, fc.separationTimeMicros)
        assertEquals(20_000, IsoTp.parseFlowControl(intArrayOf(0x30, 0, 20))!!.separationTimeMicros)
        assertEquals(IsoTp.FlowControl.Status.WAIT, IsoTp.parseFlowControl(intArrayOf(0x31, 0, 0))!!.status)
        assertNull(IsoTp.parseFlowControl(intArrayOf(0x21, 0, 0)))
    }

    @Test fun parseCan11SingleFramesTwoEcus() {
        val r = FrameParser.parse(listOf("7E8 06 41 00 BE 3F A8 13 00", "7E9 06 41 00 98 18 80 01 00"), ObdProtocol.CAN_11_500)
        assertEquals(2, r.messages.size)
        val ecm = r.messages.first { it.ecu == 0x7E8 }
        assertEquals(listOf(0x41, 0x00, 0xBE, 0x3F, 0xA8, 0x13), ecm.data.toList())
        assertEquals(0, r.corruptLines)
    }

    @Test fun parseCan11NoSpaces() {
        val r = FrameParser.parse(listOf("7E804410C1AF8"), ObdProtocol.CAN_11_500)
        assertEquals(listOf(0x41, 0x0C, 0x1A, 0xF8), r.messages.single().data.toList())
    }

    @Test fun parseCanMultiFrameVin() {
        val lines = listOf("7E8 10 14 49 02 01 31 48 47", "7E8 21 43 4D 38 32 36 33 33", "7E8 22 41 30 30 34 33 35 32")
        val r = FrameParser.parse(lines, ObdProtocol.CAN_11_500)
        val d = r.messages.single().data
        assertEquals(0x49, d[0])
        assertEquals("1HGCM82633A004352", d.drop(3).map { it.toChar() }.joinToString(""))
    }

    @Test fun parseCan29() {
        val r = FrameParser.parse(listOf("18 DA F1 10 03 41 0D 32 00 00 00 00"), ObdProtocol.CAN_29_500)
        assertEquals(0x10, r.messages.single().ecu)
        assertEquals(listOf(0x41, 0x0D, 0x32), r.messages.single().data.toList())
    }

    @Test fun parseLegacyIso9141() {
        // 48 6B 10 41 0C 1A F8 + checksum
        val bytes = listOf(0x48, 0x6B, 0x10, 0x41, 0x0C, 0x1A, 0xF8)
        val line = Hex.encode(bytes + (bytes.sum() and 0xFF))
        val r = FrameParser.parse(listOf(line), ObdProtocol.ISO_9141_2)
        assertEquals(0x10, r.messages.single().ecu)
        assertEquals(listOf(0x41, 0x0C, 0x1A, 0xF8), r.messages.single().data.toList())
    }

    @Test fun parseHeadersOffMultiLine() {
        val r = FrameParser.parse(listOf("014", "0: 49 02 01 31 48 47", "1: 43 4D 38 32 36 33 33", "2: 41 30 30 34 33 35 32"), ObdProtocol.CAN_11_500, headersOn = false)
        assertEquals(0x49, r.messages.single().data[0])
        assertEquals(20, r.messages.single().data.size)
    }

    @Test fun corruptLinesCounted() {
        val r = FrameParser.parse(listOf("7E8 03 41 0D Z\u0007", "7E8 03 41 0C 10"), ObdProtocol.CAN_11_500)
        assertEquals(1, r.corruptLines)
    }

    @Test fun elmErrorDetection() {
        assertEquals(ElmError.NO_DATA, ElmError.detect(listOf("NO DATA")))
        assertEquals(ElmError.UNABLE_TO_CONNECT, ElmError.detect(listOf("SEARCHING...", "UNABLE TO CONNECT")))
        assertEquals(ElmError.BUS_INIT, ElmError.detect(listOf("BUS INIT: ...ERROR")))
        assertEquals(ElmError.UNKNOWN_COMMAND, ElmError.detect(listOf("?")))
        assertNull(ElmError.detect(listOf("BUS INIT: ...OK", "41 00 BE 3F A8 13")))
        assertNotNull(ElmError.detect(listOf("CAN ERROR")))
    }

    @Test fun protocolFromDpn() {
        assertEquals(ObdProtocol.CAN_11_500, ObdProtocol.fromDpn("A6"))
        assertEquals(ObdProtocol.ISO_9141_2, ObdProtocol.fromDpn("3"))
        assertEquals(ObdProtocol.SAE_J1939, ObdProtocol.fromDpn("A"))
    }
}
