package app.obdforge.core

import app.obdforge.core.alert.AlertCondition
import app.obdforge.core.alert.AlertEngine
import app.obdforge.core.alert.AlertRule
import app.obdforge.core.analysis.EngineAnalysis
import app.obdforge.core.analysis.Level
import app.obdforge.core.analysis.Series
import app.obdforge.core.analysis.Stats
import app.obdforge.core.assistant.AssistantInput
import app.obdforge.core.assistant.DiagnosticAssistant
import app.obdforge.core.assistant.Observation
import app.obdforge.core.assistant.QueryEngine
import app.obdforge.core.assistant.TestOutcome
import app.obdforge.core.dtc.Dtc
import app.obdforge.core.dtc.DtcKind
import app.obdforge.core.export.Exporters
import app.obdforge.core.export.SessionExport
import app.obdforge.core.live.VehicleSpec
import app.obdforge.core.perf.PerfTestType
import app.obdforge.core.perf.PerformanceTimer
import app.obdforge.core.sim.SimScenario
import app.obdforge.core.sim.VehicleModel
import app.obdforge.core.util.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

class AnalysisTest {
    /** Drives the vehicle model and records series at 10 Hz. */
    private fun record(scenario: SimScenario, seconds: Int, warm: Boolean = true): Map<String, Series> {
        val m = VehicleModel(scenario, 3)
        if (warm) repeat(3000) { m.step(0.1) }
        val acc = HashMap<String, MutableList<Pair<Long, Double>>>()
        fun put(k: String, t: Long, v: Double) { acc.getOrPut(k) { ArrayList() }.add(t to v) }
        for (i in 0 until seconds * 10) {
            m.step(0.1)
            val t = i * 100L
            put("rpm", t, m.rpm); put("speed", t, m.speed); put("stft1", t, m.stft1); put("ltft1", t, m.ltft1)
            put("maf", t, m.maf); put("map", t, m.map); put("baro", t, m.baro); put("tps", t, m.throttle)
            put("load", t, m.load); put("ect", t, m.ect); put("iat", t, m.iat); put("boost", t, m.map - m.baro)
            put("ecu_voltage", t, m.voltage); put("ambient", t, m.ambient)
        }
        return acc.mapValues { Series.of(it.key, it.value) }
    }

    @Test fun statsBasics() {
        val s = Series.of("x", listOf(0L to 1.0, 1000L to 3.0, 2000L to 2.0, 3000L to 10.0, 4000L to 4.0))
        val st = Stats.describe(s)
        assertEquals(1.0, st.min, 0.0); assertEquals(10.0, st.max, 0.0); assertEquals(4.0, st.mean, 1e-9); assertEquals(3.0, st.median, 0.0)
        assertEquals(3.5355, st.stdDev, 1e-3)
        assertEquals(8.0, st.maxRateOfChange, 1e-9)
        assertEquals(2000L, Stats.durationBeyond(s, 2.5, true))
        assertEquals(3000L, st.peaks.first().first)
        assertEquals(2.0, s.at(2500)!!, 0.0)
    }

    @Test fun richScenarioTrimAnalysis() {
        val ta = EngineAnalysis(record(SimScenario.RICH, 240)).fuelTrims()
        val idle = ta.bank1.first { it.condition == "Idle" }
        assertTrue("idle ltft ${idle.ltft}", idle.ltft < -18)
        assertTrue(ta.findings.any { it.title.contains("rich across conditions") || it.title.contains("rich mainly at idle") })
    }

    @Test fun leanVacuumLeakPattern() {
        val ta = EngineAnalysis(record(SimScenario.LEAN, 240)).fuelTrims()
        val idle = ta.bank1.first { it.condition == "Idle" }
        val cruise = ta.bank1.first { it.condition == "Cruise" }
        assertTrue("idle ${idle.total} cruise ${cruise.total}", idle.total > cruise.total + 8)
        assertTrue(ta.findings.any { it.title.contains("lean at idle, better at higher airflow") })
    }

    @Test fun normalScenarioIsQuiet() {
        val ta = EngineAnalysis(record(SimScenario.NORMAL, 240)).fuelTrims()
        assertTrue(ta.findings.none { it.level == Level.ALERT })
    }

    @Test fun coolingWarmupAndOverheat() {
        val cold = EngineAnalysis(record(SimScenario.NORMAL, 600, warm = false)).cooling()
        assertTrue(cold.any { it.title.startsWith("Warm-up to 80") })
        val hot = EngineAnalysis(record(SimScenario.OVERHEAT, 600)).cooling()
        assertTrue(hot.any { it.level == Level.ALERT })
    }

    @Test fun boostAnalysisDetectsUnderboost() {
        val spec = VehicleSpec(turbocharged = true)
        val good = EngineAnalysis(record(SimScenario.NORMAL, 240), spec).boost()
        val bad = EngineAnalysis(record(SimScenario.UNDERBOOST, 240), spec).boost()
        val peakGood = good.first { it.title.startsWith("Peak boost") }.evidence["Peak kPa"]!!
        val peakBad = bad.first { it.title.startsWith("Peak boost") }.evidence["Peak kPa"]!!
        assertTrue("good $peakGood bad $peakBad", peakGood > peakBad + 30)
        assertTrue(good.first { it.title.startsWith("Peak boost") }.estimated)
    }

    @Test fun assistantRichExampleFromSpec() {
        val input = AssistantInput(
            live = mapOf("ltft1" to -25.0, "stft1" to -5.0, "o2_b1s1_v" to 0.78, "ect" to 90.0),
            observations = setOf(Observation.SOOTY_PLUGS),
        )
        val r = DiagnosticAssistant().analyse(input)
        assertTrue(r.conditions.any { it.contains("rich", true) })
        assertTrue(r.hypotheses.first().category == "Rich running")
        assertTrue(r.hypotheses.none { it.strength.startsWith("Confirmed") })
        assertTrue(r.interpretations.any { it.reading.startsWith("Long term fuel trim") && it.meaning.contains("remove") })
        assertTrue(r.testPlan.isNotEmpty())
        assertTrue(r.testPlan.first().second.effort == 1) // cheapest test first
        assertTrue(r.missingData.any { it.contains("recorded session") })
        assertTrue(r.disclaimer.contains("not confirmed"))
    }

    @Test fun assistantUsesUserTestResults() {
        val base = AssistantInput(live = mapOf("ltft1" to -25.0, "stft1" to -5.0), dtcs = listOf(Dtc("P0172", DtcKind.STORED, 0x7E8)))
        val a = DiagnosticAssistant()
        val r1 = a.analyse(base)
        val top = r1.hypotheses.first().id
        val r2 = a.analyse(base.copy(testResults = mapOf(top to TestOutcome.ELIMINATED)))
        assertTrue(r2.hypotheses.first().id != top)
        assertEquals("Eliminated by your test", r2.hypotheses.last().strength)
        val r3 = a.analyse(base.copy(testResults = mapOf("rich_purge" to TestOutcome.CONFIRMED)))
        assertEquals("rich_purge", r3.hypotheses.first().id)
        assertEquals("Confirmed by your test", r3.hypotheses.first().strength)
    }

    @Test fun assistantLeanSeparatesLeakFromFuelDelivery() {
        val bins = EngineAnalysis(record(SimScenario.LEAN, 240)).fuelTrims().bank1
        val r = DiagnosticAssistant().analyse(AssistantInput(trimBins = bins))
        assertEquals("lean_vacuum_leak", r.hypotheses.first().id)
    }

    @Test fun queryEngineAnswers() {
        val q = QueryEngine()
        val input = AssistantInput(live = mapOf("ect" to 112.0, "boost" to -60.0, "ltft1" to -25.0, "stft1" to -5.0))
        assertTrue(q.answer("what does P0171 mean?", input).contains("System Too Lean"))
        assertTrue(q.answer("is it safe to drive", input).contains("Stop"))
        assertTrue(q.answer("what's my boost", input).contains("vacuum"))
        assertTrue(q.answer("why is my LTFT so negative?", input).contains("remove"))
        assertEquals("I don't know", q.answer("what colour is the sky", input))
    }

    @Test fun performanceTimer0to100() {
        val t = PerformanceTimer(PerfTestType.ZERO_100, VehicleSpec(massKg = 1500.0))
        // 2 s stationary, then constant 3.4722 m/s² (0-100 in exactly 8 s), sampled at 10 Hz
        var time = 0L
        repeat(20) { t.feed(time, 0.0, 800.0, "GPS"); time += 100 }
        val start = time
        for (i in 1..100) { val v = 100.0 * i / 80.0; t.feed(start + i * 100L, v, 1500 + v * 40, "GPS") }
        assertEquals(PerformanceTimer.State.DONE, t.state)
        val r = t.result!!
        assertEquals(8.0, r.elapsedS, 0.12)
        assertTrue(r.peakCrankKw!! > 50)
        assertNotNull(r.peakCrankNm)
        assertTrue(r.notes.any { it.contains("estimates") })
    }

    @Test fun performanceQuarterMileAndAbort() {
        val q = PerformanceTimer(PerfTestType.QUARTER_MILE, VehicleSpec())
        var time = 0L
        repeat(5) { q.feed(time, 0.0, null, "OBD"); time += 100 }
        val t0 = time
        // a = 4 m/s²: 402.336 m reached at t = sqrt(2*402.336/4) = 14.18 s
        for (i in 1..200) { val ts = i * 0.1; q.feed(t0 + i * 100L, 4 * ts * 3.6, null, "OBD") }
        assertEquals(PerformanceTimer.State.DONE, q.state)
        assertEquals(14.18, q.result!!.elapsedS, 0.25)
        val ab = PerformanceTimer(PerfTestType.ZERO_100, VehicleSpec())
        ab.feed(0, 0.0, null, "GPS"); ab.feed(100, 5.0, null, "GPS"); ab.feed(200, 20.0, null, "GPS"); ab.feed(300, 5.0, null, "GPS")
        assertEquals(PerformanceTimer.State.ABORTED, ab.state)
    }

    @Test fun exportsCsvJsonXlsx() {
        val series = mapOf(
            "rpm" to Series.of("rpm", listOf(0L to 800.0, 100L to 900.0, 200L to 1000.0)),
            "ect" to Series.of("ect", listOf(50L to 85.0, 250L to 86.0)),
            "boost" to Series.of("boost", listOf(0L to -60.0)),
        )
        val e = SessionExport("Test, run", "Forester", 1_700_000_000_000L, "note", true, series)
        val csv = ByteArrayOutputStream().also { Exporters.csv(e, it) }.toString(Charsets.UTF_8.name())
        val lines = csv.trim().lines()
        assertTrue(lines[0].contains("SIMULATED"))
        assertTrue(lines[2].contains("Engine speed (rpm)") && lines[2].contains("[calc]"))
        assertEquals(8, lines.size) // comment, notes, header + 5 distinct timestamps
        assertEquals("0.25", lines[7].split(",")[0])
        val json = Json.parse(Exporters.json(e)) as Map<*, *>
        assertEquals(true, json["simulated"])
        assertEquals(3, (json["channels"] as List<*>).size)
        val xbytes = ByteArrayOutputStream().also { Exporters.xlsx(e, it) }.toByteArray()
        val names = ArrayList<String>()
        ZipInputStream(xbytes.inputStream()).use { z -> while (true) { val en = z.nextEntry ?: break; names.add(en.name) } }
        assertTrue(names.containsAll(listOf("[Content_Types].xml", "xl/workbook.xml", "xl/worksheets/sheet1.xml", "xl/worksheets/sheet2.xml", "xl/styles.xml")))
    }

    @Test fun alertsSustainAndCooldown() {
        val eng = AlertEngine(listOf(AlertRule("t", "Hot", "ect", AlertCondition.ABOVE, 100.0, sustainMs = 2000, cooldownMs = 10_000)))
        assertTrue(eng.evaluate(mapOf("ect" to 105.0), 0).isEmpty())
        assertTrue(eng.evaluate(mapOf("ect" to 105.0), 1000).isEmpty())
        assertEquals(1, eng.evaluate(mapOf("ect" to 105.0), 2100).size)
        assertTrue(eng.evaluate(mapOf("ect" to 105.0), 5000).isEmpty()) // cooldown
        assertTrue(eng.evaluate(mapOf("ect" to 90.0), 6000).isEmpty())
        assertEquals(1, eng.checkDtcs(listOf("P0300"), 0).size)
        assertTrue(eng.checkDtcs(listOf("P0300"), 1).isEmpty())
        val volt = AlertEngine(listOf(AlertRule("v", "Low V", "ecu_voltage", AlertCondition.BELOW, 12.8, sustainMs = 0, minRpm = 600.0)))
        assertTrue(volt.evaluate(mapOf("ecu_voltage" to 12.2, "rpm" to 0.0), 0).isEmpty()) // engine off: ignored
        assertEquals(1, volt.evaluate(mapOf("ecu_voltage" to 12.2, "rpm" to 800.0), 1).size)
    }
}
