package app.obdforge.core

import app.obdforge.core.dtc.Dtc
import app.obdforge.core.dtc.DtcDatabase
import app.obdforge.core.dtc.DtcKnowledge
import app.obdforge.core.dtc.Severity
import app.obdforge.core.mfr.Expression
import app.obdforge.core.mfr.ManufacturerProfile
import app.obdforge.core.mfr.Profiles
import app.obdforge.core.pid.Mode06
import app.obdforge.core.pid.Pids
import app.obdforge.core.pid.Readiness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DecodeTest {
    private fun v(key: String, vararg b: Int) = Pids.channel(key)!!.decode(b)!!

    @Test fun standardPidFormulas() {
        assertEquals(1726.0, v("rpm", 0x1A, 0xF8), 0.001)
        assertEquals(50.0, v("ect", 90), 0.001)
        assertEquals(-40.0, v("ect", 0), 0.001)
        assertEquals(0.0, v("stft1", 128), 0.001)
        assertEquals(-25.0, v("ltft1", 96), 0.001)
        assertEquals(99.2, v("ltft1", 255), 0.05)
        assertEquals(12.34, v("maf", 0x04, 0xD2), 0.001)
        assertEquals(100.0, v("load", 255), 0.001)
        assertEquals(-64.0, v("timing", 0), 0.001)
        assertEquals(10.0, v("timing", 148), 0.001)
        assertEquals(14.2, v("ecu_voltage", 0x37, 0x78), 0.001)
        assertEquals(1.0, v("lambda_cmd", 0x80, 0x00), 0.001)
        assertEquals(0.45, v("o2_b1s1_v", 90, 0xFF), 0.001)
        assertNull(Pids.channel("o2_b1s1_trim")!!.decode(intArrayOf(90, 0xFF)))
        assertEquals(41000.0, v("frp", 0x10, 0x04), 0.001)
        assertEquals(-10.0, v("evap_vp", 0xFF, 0xD8), 0.001)
        assertEquals(410.0, v("cat_b1s1", 0x11, 0x94), 0.001)
        assertEquals(1.0, v("o2c1_lambda", 0x80, 0x00, 0x80, 0x00), 0.001)
        assertEquals(0.0, v("o2c1_ma", 0x80, 0x00, 0x80, 0x00), 0.001)
        assertEquals(842151.3, v("odometer", 0x00, 0x80, 0x80, 0x89), 0.01)
    }

    @Test fun supportBitmap() {
        val s = Pids.decodeSupport(0x00, intArrayOf(0xBE, 0x3F, 0xA8, 0x13))
        assertTrue(0x01 in s); assertFalse(0x02 in s); assertTrue(0x0C in s); assertTrue(0x0D in s)
        assertTrue(0x20 in s); assertEquals(setOf(0x01, 0x03, 0x04, 0x05, 0x06, 0x07, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x11, 0x13, 0x15, 0x1C, 0x1F, 0x20), s)
    }

    @Test fun textPids() {
        assertEquals("System 1: Closed loop (using O2 sensor feedback)", Pids[0x03]!!.text!!(intArrayOf(2, 0)))
        assertEquals("JOBD and OBD-II", Pids.obdStandard(11))
        assertEquals("B1S1, B1S2", Pids.o2Layout(0x03, false))
    }

    @Test fun dtcDecodeEncode() {
        assertEquals("P0301", Dtc.decode(0x03, 0x01))
        assertEquals("C0035", Dtc.decode(0x40, 0x35))
        assertEquals("B1234", Dtc.decode(0x92, 0x34))
        assertEquals("U0100", Dtc.decode(0xC1, 0x00))
        assertNull(Dtc.decode(0, 0))
        for (code in listOf("P0171", "P2A00", "C1234", "B0001", "U3FFF", "P3400")) {
            val b = Dtc.encode(code); assertEquals(code, Dtc.decode(b[0], b[1]))
        }
    }

    @Test fun dtcClassificationAndDatabase() {
        assertTrue(Dtc("P0171", app.obdforge.core.dtc.DtcKind.STORED, 0).isGeneric)
        assertTrue(Dtc("P1171", app.obdforge.core.dtc.DtcKind.STORED, 0).isManufacturerSpecific)
        assertEquals("System Too Rich (Bank 1)", DtcDatabase.describe("P0172"))
        assertNull(DtcDatabase.describe("P1491"))
        assertTrue(DtcDatabase.size > 400)
        val info = DtcKnowledge.info("P0172")
        assertFalse(info.genericGuidance)
        assertTrue(info.possibleCauses.any { it.contains("injector", true) })
        assertEquals(Severity.CRITICAL, DtcKnowledge.info("P0217").severity)
        val unknown = DtcKnowledge.info("P1491")
        assertTrue(unknown.genericGuidance)
        assertTrue(unknown.description.contains("Manufacturer-specific"))
        val circuit = DtcKnowledge.info("P0463")
        assertTrue(circuit.possibleCauses.isNotEmpty())
    }

    @Test fun readinessDecode() {
        // MIL on, 2 DTCs; spark; misfire/fuel/comp available, comp incomplete; cat, evap, O2, O2 heater available; evap + O2 incomplete
        val s = Readiness.decode(intArrayOf(0x82, 0b0100_0111, 0b0110_0101, 0b0010_0100))
        assertTrue(s.milOn); assertEquals(2, s.dtcCount); assertFalse(s.compressionIgnition)
        assertEquals(setOf("Comprehensive components", "Evaporative system", "Oxygen sensor"), s.incomplete.map { it.name }.toSet())
        assertEquals(7, s.supported.size)
    }

    @Test fun mode06Decode() {
        val data = intArrayOf(0xA2, 0x0B, 0x24, 0x00, 0x05, 0x00, 0x00, 0xFF, 0xFF, 0x01, 0x05, 0x10, 0x00, 0x48, 0x00, 0x00, 0x01, 0x90)
        val r = Mode06.decodeCan(data)
        assertEquals(2, r.size)
        assertEquals("Misfire cylinder 1", r[0].monitorName)
        assertEquals(5.0, r[0].value, 0.0)
        assertEquals(72.0, r[1].value, 0.0); assertEquals("ms", r[1].unit); assertTrue(r[1].passed)
        val fail = Mode06.decodeCan(intArrayOf(0x21, 0x80, 0x24, 0x00, 0x78, 0x00, 0x00, 0x00, 0x64)).single()
        assertFalse(fail.passed); assertEquals("FAIL", fail.status)
        val unknown = Mode06.decodeCan(intArrayOf(0x21, 0x80, 0xEE, 0x00, 0x01, 0x00, 0x00, 0x00, 0x64)).single()
        assertFalse(unknown.scaled)
    }

    @Test fun expressions() {
        assertEquals(1726.0, Expression("((A*256)+B)/4").eval(intArrayOf(0x1A, 0xF8)), 1e-9)
        assertEquals(-2.0, Expression("SIGNED(A)").eval(intArrayOf(0xFE)), 1e-9)
        assertEquals(1.0, Expression("BIT(A,3)").eval(intArrayOf(0x08)), 1e-9)
        assertEquals(-7.0, Expression("-A+B*2^2/4").eval(intArrayOf(10, 3)), 1e-9)
        assertEquals(255.0, Expression("0xFF").eval(intArrayOf()), 1e-9)
        try { Expression("A+"); throw AssertionError("should fail") } catch (e: IllegalArgumentException) { }
    }

    @Test fun profileRoundTrip() {
        val json = Profiles.template("Subaru")
        val p = ManufacturerProfile.fromJson(json)
        assertEquals("Subaru", p.make)
        assertEquals(1, p.enhancedPids.size)
        assertEquals(0x1234, p.enhancedPids[0].identifier)
        assertEquals("221234", p.enhancedPids[0].requestHex())
        Profiles.install(p)
        assertEquals("Example manufacturer code text (replace)", DtcDatabase.describe("P1XXX", "Subaru"))
        assertTrue(Profiles.builtIn.none { it.enhancedPids.isNotEmpty() }) // built-ins never invent manufacturer PIDs
    }
}
