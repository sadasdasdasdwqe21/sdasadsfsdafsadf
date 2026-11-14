package app.obdforge.core

import app.obdforge.core.dtc.DtcKind
import app.obdforge.core.elm.ObdProtocol
import app.obdforge.core.live.DerivedCalculator
import app.obdforge.core.live.LivePoller
import app.obdforge.core.obd.ObdSession
import app.obdforge.core.obd.Priority
import app.obdforge.core.sim.SimScenario
import app.obdforge.core.sim.SimulatedElm327
import app.obdforge.core.transport.TransportException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SimulatorIntegrationTest {
    private fun session(scenario: SimScenario = SimScenario.NORMAL, legacy: Boolean = false): Pair<ObdSession, SimulatedElm327> {
        val sim = SimulatedElm327(scenario, legacyProtocol = legacy, latencyMs = 0)
        val s = ObdSession(sim)
        return s to sim
    }

    @Test fun connectCanDiscoversEverything() {
        val (s, _) = session()
        val r = s.connect()
        assertEquals(ObdProtocol.CAN_11_500, r.protocol)
        assertEquals("ELM327 v1.4b", r.adapter.idString)
        assertFalse(r.adapter.isStn)
        assertEquals(listOf(0x7E8, 0x7E9), r.ecus.map { it.id })
        assertTrue(0x0C in r.supportedPids && 0x5C in r.supportedPids && 0x44 in r.supportedPids)
        assertFalse(0x0A in r.supportedPids) // not supported by the sim ECU: must not appear
        assertEquals("SIMULATED0VIN0001", r.vin)
        assertEquals("JOBD and OBD-II", r.obdStandard)
        s.close()
    }

    @Test fun readLivePidsMultiRequest() {
        val (s, sim) = session()
        s.connect()
        sim.fastForward(300.0)
        val data = s.client.readPids(listOf(0x0C, 0x0D, 0x05, 0x0B, 0x33, 0x10, 0x11))
        assertEquals(7, data.size)
        val ect = app.obdforge.core.pid.Pids.channel("ect")!!.decode(data[0x05]!!)!!
        assertTrue("coolant warmed up: $ect", ect > 80)
        s.close()
    }

    @Test fun dtcFreezeFrameAndClear() {
        val (s, sim) = session(SimScenario.RICH)
        s.connect()
        sim.fastForward(400.0)
        val stored = s.client.readDtcs(DtcKind.STORED)
        assertEquals(listOf("P0172"), stored.map { it.code })
        assertEquals(0x7E8, stored.single().ecu)
        assertEquals(listOf("P0172"), s.client.readDtcs(DtcKind.PERMANENT).map { it.code })
        val ff = s.client.readFreezeFrame()
        assertEquals("P0172", ff.single().dtc)
        assertTrue(ff.single().values.containsKey("rpm"))
        assertTrue(ff.single().values["ltft1"]!! < -10)
        val mil = s.client.readPids(listOf(0x01))[0x01]!!
        assertTrue(app.obdforge.core.pid.Readiness.decode(mil).milOn)
        val clr = s.client.clearDtcs()
        assertTrue(clr.answered)
        assertTrue(s.client.readDtcs(DtcKind.STORED).isEmpty())
        // Permanent codes survive a clear until the monitor passes.
        assertEquals(listOf("P0172"), s.client.readDtcs(DtcKind.PERMANENT).map { it.code })
        val after = app.obdforge.core.pid.Readiness.decode(s.client.readPids(listOf(0x01))[0x01]!!)
        assertFalse(after.allReady)
        s.close()
    }

    @Test fun mode06AndMode09() {
        val (s, sim) = session(SimScenario.MISFIRE)
        s.connect()
        sim.fastForward(120.0)
        val m6 = s.client.readMode06()
        assertTrue(m6.any { it.mid == 0xA3 })
        val cyl2 = m6.filter { it.mid == 0xA3 }.maxOf { it.value }
        val cyl1 = m6.filter { it.mid == 0xA2 }.maxOf { it.value }
        assertTrue("cyl2 $cyl2 > cyl1 $cyl1", cyl2 > cyl1 * 3)
        val info = s.client.readVehicleInfo()
        assertEquals("SIMULATED0VIN0001", info.first { it.infoType == 0x02 }.value)
        assertEquals("ECM-EngineControl", info.first { it.infoType == 0x0A && it.ecu == 0x7E8 }.value)
        assertTrue(info.any { it.infoType == 0x0A && it.ecu == 0x7E9 })
        assertTrue(info.first { it.infoType == 0x08 }.value.contains("Ignition cycles: 1630"))
        s.close()
    }

    @Test fun udsEnhancedReads() {
        val (s, sim) = session(SimScenario.LEAN)
        s.connect()
        sim.fastForward(200.0)
        val (codes, err) = s.client.udsReadDtcs(0x7E0)
        assertEquals(null, err)
        assertTrue(codes.any { it.code == "P0171" })
        val (did, derr) = s.client.udsReadDid(0x7E0, 0xF190)
        assertEquals(null, derr)
        assertEquals("SIMULATED0VIN0001", s.client.asciiOrHex(did!!))
        val (bad, berr) = s.client.udsReadDid(0x7E0, 0x1234)
        assertEquals(null, bad)
        assertEquals("Request out of range", berr)
        // Functional addressing restored afterwards
        assertEquals(2, s.client.discoverEcus().size)
        s.close()
    }

    @Test fun legacyIso9141() {
        val (s, sim) = session(legacy = true)
        val r = s.connect()
        assertEquals(ObdProtocol.ISO_9141_2, r.protocol)
        assertEquals("SIMULATED0VIN0001", r.vin)
        sim.injectDtc("P0420"); sim.injectDtc("P0301"); sim.injectDtc("P0128"); sim.injectDtc("P0442")
        val codes = s.client.readDtcs(DtcKind.STORED).map { it.code }.toSet()
        assertEquals(setOf("P0420", "P0301", "P0128", "P0442"), codes)
        val o2 = s.client.readMode05()
        assertTrue(o2.any { it.tid == 0x01 && it.unit == "V" })
        s.close()
    }

    @Test fun forcedWrongProtocolFallsThroughToError() {
        val (s, _) = session()
        try { s.connect(preferred = ObdProtocol.J1850_PWM); throw AssertionError("should not connect") }
        catch (e: app.obdforge.core.elm.ElmException) { assertTrue(e.message!!.contains("Could not connect")) }
        s.close()
    }

    @Test fun livePollerAndDerived() {
        val (s, sim) = session()
        val r = s.connect()
        sim.fastForward(250.0)
        val poller = LivePoller(s.client, r.supportedPids)
        poller.select(mapOf("rpm" to 0L, "speed" to 0L, "boost" to 0L, "ect" to 1000L, "fuel_pressure" to 0L, "economy_l100" to 0L))
        val samples = poller.cycle()
        val keys = samples.map { it.key }.toSet()
        assertTrue(keys.containsAll(listOf("rpm", "speed", "map", "baro", "ect", "maf")))
        assertFalse("unsupported PID 0A must never be produced", "fuel_pressure" in keys)
        val latest = samples.associate { it.key to it.value }
        val derived = DerivedCalculator().compute(latest, 0).associate { it.key to it.value }
        assertEquals(latest["map"]!! - latest["baro"]!!, derived["boost"]!!, 1e-9)
        // Coolant has a 1 s interval: an immediate second cycle must not re-request it.
        val again = poller.cycle().map { it.key }.toSet()
        assertFalse("ect" in again)
        assertTrue("rpm" in again)
        s.close()
    }

    @Test fun commandPriorityThroughExecutor() {
        val (s, _) = session()
        s.submit(Priority.HIGH) { it.elm.info }.get()
        val f = s.submit(Priority.HIGH) { s.connect() }
        assertNotNull(f.get())
        val v = s.submit(Priority.LOW) { it.elm.readVoltage() }.get()
        assertNotNull(v)
        s.close()
    }

    @Test fun linkDropSurfacesAsTransportException() {
        val (s, sim) = session()
        s.connect()
        sim.dropLink()
        try { s.client.readPids(listOf(0x0C)); throw AssertionError("expected failure") }
        catch (e: TransportException) { assertNotNull(e.message) }
        // Reopen = reconnect
        sim.open()
        s.connect()
        assertTrue(s.client.readPids(listOf(0x0C)).containsKey(0x0C))
        s.close()
    }

    @Test fun commFailureScenarioRecoversWithRetries() {
        val (s, sim) = session(SimScenario.COMM_FAILURE)
        s.connect()
        var ok = 0; var failures = 0
        repeat(60) {
            try { if (s.client.readPids(listOf(0x0C, 0x0D)).isNotEmpty()) ok++ }
            catch (e: TransportException) { failures++; sim.open() }
        }
        assertTrue("most requests should succeed despite noise: ok=$ok", ok > 35)
        s.close()
    }
}
