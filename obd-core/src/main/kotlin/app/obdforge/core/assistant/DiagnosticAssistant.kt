package app.obdforge.core.assistant

import app.obdforge.core.analysis.EngineAnalysis
import app.obdforge.core.analysis.TrimBin
import app.obdforge.core.dtc.Dtc
import app.obdforge.core.dtc.DtcKind
import app.obdforge.core.live.VehicleSpec
import app.obdforge.core.obd.FreezeFrame
import app.obdforge.core.pid.Mode06
import kotlin.math.abs

/** Things the user can see, hear or smell that the ECU cannot report. */
enum class Observation(val label: String) {
    SOOTY_PLUGS("Spark plugs black / sooty"),
    FUEL_SMELL("Raw fuel smell from exhaust"),
    BLACK_SMOKE("Black smoke"),
    WHITE_SMOKE("White smoke (sweet smell)"),
    BLUE_SMOKE("Blue smoke"),
    FUEL_IN_OIL("Oil smells of fuel / level rising"),
    RECENT_OIL_CHANGE("Oil was changed recently"),
    ROUGH_IDLE("Rough or shaky idle"),
    HESITATION("Hesitation / stumble on acceleration"),
    LOW_POWER("Low power"),
    MISFIRE_FEEL("Misfire felt (jerking/shaking)"),
    HARD_START("Hard starting / long crank"),
    STUMBLE_HOT_RESTART("Stumble on hot restart"),
    HISSING("Hissing noise from engine bay"),
    WHISTLE_BOOST("Whistle / whoosh under boost"),
    POOR_ECONOMY("Fuel economy worse than usual"),
    OVERHEAT_GAUGE("Temperature gauge high"),
    COOLANT_LOSS("Coolant level dropping"),
    HEATER_COLD("Heater blows cold"),
    DIM_LIGHTS("Dim lights / slow cranking"),
    RECENT_MAF_CLEAN("MAF was cleaned/replaced recently"),
}

enum class TestOutcome(val label: String) { CONFIRMED("Confirmed by test"), ELIMINATED("Eliminated by test"), NOT_DONE("Not tested") }

data class AssistantInput(
    val dtcs: List<Dtc> = emptyList(),
    val freezeFrames: List<FreezeFrame> = emptyList(),
    /** Latest live values by channel key. */
    val live: Map<String, Double> = emptyMap(),
    /** Fuel trim bins from a logged session (better than single live values). */
    val trimBins: List<TrimBin> = emptyList(),
    val mode06: List<Mode06.TestResult> = emptyList(),
    val observations: Set<Observation> = emptySet(),
    /** hypothesis id -> outcome of the user's own tests */
    val testResults: Map<String, TestOutcome> = emptyMap(),
    val spec: VehicleSpec = VehicleSpec(),
)

data class DiagnosticTest(
    val action: String,
    val confirmsIf: String,
    val eliminatesIf: String,
    /** 1 = quick and free, 2 = needs simple tools, 3 = needs special equipment/disassembly */
    val effort: Int,
)

data class Hypothesis(
    val id: String,
    val title: String,
    val category: String,
    val explanation: String,
    val score: Double,
    val evidenceFor: List<String>,
    val evidenceAgainst: List<String>,
    val measurementsNeeded: List<String>,
    val tests: List<DiagnosticTest>,
    val outcome: TestOutcome,
) {
    val strength: String get() = when {
        outcome == TestOutcome.CONFIRMED -> "Confirmed by your test"
        outcome == TestOutcome.ELIMINATED -> "Eliminated by your test"
        score >= 6 -> "Strongly supported by the data"
        score >= 3.5 -> "Supported"
        score >= 1.5 -> "Possible"
        else -> "Weak"
    }
}

data class Interpretation(val reading: String, val meaning: String)

data class AssistantReport(
    val headline: String,
    val interpretations: List<Interpretation>,
    val conditions: List<String>,
    val hypotheses: List<Hypothesis>,
    val testPlan: List<Pair<String, DiagnosticTest>>,
    val missingData: List<String>,
    val disclaimer: String,
)

/**
 * Rule-based diagnostic reasoning. It explains readings, scores possible causes by the evidence
 * available, lists the measurements that would separate them, and orders tests cheapest and
 * most informative first. It never recommends replacing a part on one reading, and it never
 * presents a hypothesis as a confirmed fault unless the user recorded a test that confirms it.
 */
class DiagnosticAssistant {

    private class Ctx(val i: AssistantInput) {
        val codes = i.dtcs.map { it.code.take(5) }.toSet()
        fun has(vararg c: String) = c.any { it in codes }
        fun live(k: String) = i.live[k]
        fun obs(o: Observation) = o in i.observations
        val idle = i.trimBins.firstOrNull { it.condition == "Idle" }
        val cruise = i.trimBins.firstOrNull { it.condition == "Cruise" } ?: i.trimBins.firstOrNull { it.condition == "2500 rpm no load" }
        val ltft: Double? = i.trimBins.takeIf { it.isNotEmpty() }?.let { b -> b.sumOf { it.ltft * it.samples } / b.sumOf { it.samples } } ?: live("ltft1")
        val stft: Double? = i.trimBins.takeIf { it.isNotEmpty() }?.let { b -> b.sumOf { it.stft * it.samples } / b.sumOf { it.samples } } ?: live("stft1")
        val total: Double? = if (ltft != null && stft != null) ltft + stft else null
        val rich get() = (total != null && total < -10) || has("P0172", "P0175", "P2188", "P2178")
        val lean get() = (total != null && total > 10) || has("P0171", "P0174", "P2187", "P2177")
        val misfire get() = codes.any { it.startsWith("P030") || it.startsWith("P031") } || obs(Observation.MISFIRE_FEEL)
    }

    private class Rule(val weight: Double, val text: String, val test: (Ctx) -> Boolean)

    private class Def(
        val id: String, val title: String, val category: String, val explanation: String,
        val applies: (Ctx) -> Boolean,
        val forRules: List<Rule>, val againstRules: List<Rule>,
        val measurements: List<String>, val tests: List<DiagnosticTest>,
    )

    private val defs: List<Def> = listOf(
        // ---------------------------------------------------------------- rich
        Def("rich_injector", "Leaking or dripping injector(s)", "Rich running",
            "An injector that leaks adds fuel the ECU did not ask for. The effect is biggest at idle and after shutdown (hot restart stumble).",
            { it.rich },
            listOf(Rule(2.0, "Fuel trims are removing fuel") { it.rich },
                Rule(1.5, "Rich correction is larger at idle than at cruise") { c -> c.idle != null && c.cruise != null && c.idle.total < c.cruise.total - 5 },
                Rule(1.5, "Stumble on hot restart reported") { it.obs(Observation.STUMBLE_HOT_RESTART) },
                Rule(1.0, "Sooty spark plugs reported") { it.obs(Observation.SOOTY_PLUGS) },
                Rule(1.0, "Fuel smell in oil reported (injector wash-down can dilute oil)") { it.obs(Observation.FUEL_IN_OIL) },
                Rule(1.0, "Misfire present together with rich trims") { it.misfire }),
            listOf(Rule(1.5, "Rich correction is the same at idle and cruise (points to a proportional cause like MAF or pressure)") { c -> c.idle != null && c.cruise != null && abs(c.idle.total - c.cruise.total) < 3 }),
            listOf("Fuel rail pressure leak-down after shutdown", "Spark plug condition per cylinder", "Injector balance / leak test"),
            listOf(DiagnosticTest("Read each spark plug: compare colour cylinder by cylinder.", "One or two plugs much sootier than the rest.", "All plugs look the same (cause is common to all cylinders).", 2),
                DiagnosticTest("Fuel pressure leak-down: note rail pressure at shutdown and after 10–20 minutes (gauge, or rail pressure PID with key on).", "Pressure falls much faster than the specification allows.", "Pressure holds within specification.", 2),
                DiagnosticTest("Injector leak/balance test on a bench or with the rail lifted (workshop).", "An injector drips or flows more than the others.", "All injectors seal and flow within tolerance.", 3))),
        Def("rich_purge", "EVAP purge valve stuck open", "Rich running",
            "A purge valve that does not close feeds fuel vapour from the charcoal canister into the intake all the time, worst at idle and with a full tank in warm weather.",
            { it.rich },
            listOf(Rule(1.5, "Fuel trims are removing fuel") { it.rich },
                Rule(1.0, "Rich mainly at idle") { c -> c.idle != null && c.idle.total < -10 && (c.cruise?.total ?: 0.0) > -8 },
                Rule(1.0, "Hard starting after refuelling or when hot") { it.obs(Observation.HARD_START) || it.obs(Observation.STUMBLE_HOT_RESTART) },
                Rule(1.0, "EVAP codes present") { it.has("P0441", "P0443", "P0455", "P0496") }),
            emptyList(),
            listOf("Fuel trims at idle with the purge line pinched/capped"),
            listOf(DiagnosticTest("Warm idle: watch STFT/LTFT, then pinch or disconnect-and-cap the purge hose at the intake for 1–2 minutes.", "Trims move several percent towards zero (or STFT jumps positive) when the line is closed.", "Trims do not change.", 1),
                DiagnosticTest("Engine off: apply vacuum/air to the purge valve with it unpowered.", "Valve passes air while it should be closed.", "Valve holds.", 2))),
        Def("rich_oil_dilution", "Fuel-diluted engine oil", "Rich running",
            "Fuel in the crankcase evaporates when the oil is hot and is drawn in through the PCV system. The ECU sees extra fuel and trims rich. It is often a consequence of another rich/misfire problem, especially on direct injection engines.",
            { it.rich || it.obs(Observation.FUEL_IN_OIL) },
            listOf(Rule(2.5, "Fuel smell in oil reported") { it.obs(Observation.FUEL_IN_OIL) },
                Rule(1.0, "Rich trims") { it.rich },
                Rule(-1.0, "Oil changed recently (fresh oil should not be diluted yet)") { it.obs(Observation.RECENT_OIL_CHANGE) }),
            listOf(Rule(1.5, "Oil was changed recently and the condition remains") { it.obs(Observation.RECENT_OIL_CHANGE) }),
            listOf("Fuel trims before and after an oil change", "Trims with the PCV hose briefly disconnected (capped) at warm idle"),
            listOf(DiagnosticTest("Warm idle: note LTFT/STFT, then temporarily disconnect and cap the PCV/breather hose for a minute.", "Trims move strongly towards zero with the PCV path closed.", "No change.", 1),
                DiagnosticTest("Change the oil and drive 50–100 km, logging trims.", "Trims return close to normal with fresh oil.", "Trims unchanged with fresh oil.", 2))),
        Def("rich_pressure", "Fuel pressure too high", "Rich running",
            "Higher than specified fuel pressure pushes more fuel through every injector, so the error is roughly proportional at all loads.",
            { it.rich },
            listOf(Rule(1.5, "Fuel trims are removing fuel") { it.rich },
                Rule(1.5, "Rich correction similar at idle and cruise") { c -> c.idle != null && c.cruise != null && abs(c.idle.total - c.cruise.total) < 4 && c.idle.total < -8 },
                Rule(2.0, "Fuel pressure code present") { it.has("P0088", "P0089", "P0193") }),
            emptyList(),
            listOf("Fuel pressure (gauge or rail pressure PID) vs specification"),
            listOf(DiagnosticTest("Measure fuel pressure / read rail pressure at idle and at 2500 rpm, compare with specification.", "Pressure is above specification.", "Pressure is within specification.", 2))),
        Def("maf_over", "MAF sensor over-reading", "Rich running",
            "If the MAF reports more air than really enters, the ECU injects too much fuel and then trims it away.",
            { it.rich },
            listOf(Rule(1.0, "Fuel trims are removing fuel") { it.rich },
                Rule(1.5, "MAF code present") { it.has("P0101", "P0103") },
                Rule(1.0, "Rich correction similar across loads") { c -> c.idle != null && c.cruise != null && abs(c.idle.total - c.cruise.total) < 4 && c.idle.total < -8 }),
            listOf(Rule(1.0, "MAF recently cleaned/replaced and problem remains") { it.obs(Observation.RECENT_MAF_CLEAN) }),
            listOf("MAF g/s at warm idle and 2500 rpm vs expected for the engine size", "Estimated volumetric efficiency at full throttle"),
            listOf(DiagnosticTest("Compare warm-idle MAF with the expected value for the engine (service data) and log a full-throttle pull to check estimated VE.", "MAF reads clearly high for the conditions.", "MAF reading is plausible.", 1),
                DiagnosticTest("Unplug the MAF at warm idle (the ECU switches to a calculated airflow) and watch trims for a minute.", "Trims move towards zero with the MAF unplugged.", "No change or worse.", 1))),
        Def("ect_cold", "Coolant sensor reading colder than actual", "Rich running",
            "The ECU adds warm-up enrichment when it thinks the engine is cold.",
            { it.rich },
            listOf(Rule(2.0, "Coolant temperature reads low with a warm engine") { c -> (c.live("ect") ?: 90.0) < 70 && (c.live("runtime") ?: 0.0) > 900 },
                Rule(1.5, "Coolant sensor codes") { it.has("P0116", "P0117", "P0125", "P0128") }),
            emptyList(),
            listOf("Coolant PID vs IR thermometer at the thermostat housing"),
            listOf(DiagnosticTest("Fully warm engine: compare the coolant PID with an IR thermometer reading near the sensor.", "PID reads more than ~10 °C below actual.", "PID matches actual temperature.", 1))),
        // ---------------------------------------------------------------- lean
        Def("lean_vacuum_leak", "Unmetered air (vacuum / intake leak)", "Lean running",
            "Air entering after the MAF is not measured, so the mixture goes lean. A fixed leak matters most at low airflow (idle) and less at higher RPM.",
            { it.lean },
            listOf(Rule(2.0, "Fuel trims are adding fuel") { it.lean },
                Rule(2.5, "Lean at idle but much better at cruise") { c -> c.idle != null && c.cruise != null && c.idle.total > 10 && c.idle.total - c.cruise.total > 8 },
                Rule(1.5, "Hissing noise reported") { it.obs(Observation.HISSING) },
                Rule(1.0, "Rough idle reported") { it.obs(Observation.ROUGH_IDLE) },
                Rule(1.0, "Idle RPM higher than expected code") { it.has("P0507") }),
            listOf(Rule(1.5, "Lean gets worse with load (fuel delivery pattern instead)") { c -> c.idle != null && c.cruise != null && c.cruise.total > c.idle.total + 5 }),
            listOf("Fuel trims at idle vs 2500 rpm vs cruise", "Smoke test of the intake"),
            listOf(DiagnosticTest("Compare total trim at idle and at steady 2500 rpm (no load).", "Trims improve by 8 % or more at 2500 rpm.", "Trims stay the same or get worse with RPM.", 1),
                DiagnosticTest("Listen and inspect vacuum hoses, PCV, intake couplers and brake booster line; spray-test carefully or smoke test.", "A leak is found (trims drop when it is sealed).", "Smoke test shows no leak.", 2))),
        Def("lean_fuel_delivery", "Low fuel pressure / weak pump / clogged filter", "Lean running",
            "If the fuel system cannot keep pressure at higher flow, the mixture goes lean mostly under load.",
            { it.lean || it.has("P0087") },
            listOf(Rule(1.5, "Fuel trims are adding fuel") { it.lean },
                Rule(2.5, "Lean gets worse as load increases") { c -> c.idle != null && c.cruise != null && c.cruise.total > c.idle.total + 5 },
                Rule(2.5, "Low fuel pressure code") { it.has("P0087", "P0191", "P0190") },
                Rule(1.0, "Hesitation or low power reported") { it.obs(Observation.HESITATION) || it.obs(Observation.LOW_POWER) }),
            listOf(Rule(1.5, "Lean only at idle (leak pattern instead)") { c -> c.idle != null && c.cruise != null && c.idle.total - c.cruise.total > 8 }),
            listOf("Fuel pressure under load", "Pump supply voltage under load"),
            listOf(DiagnosticTest("Log fuel rail pressure (or connect a gauge) during a full-throttle pull.", "Pressure drops below specification under load.", "Pressure holds specification.", 2))),
        Def("maf_under", "MAF sensor under-reading (dirty element)", "Lean running",
            "A contaminated MAF usually reports less air than really enters, so the ECU under-fuels and trims positive, often at all loads.",
            { it.lean },
            listOf(Rule(1.5, "Fuel trims are adding fuel") { it.lean },
                Rule(1.5, "MAF code") { it.has("P0101", "P0102") },
                Rule(1.0, "Lean at all loads") { c -> c.idle != null && c.cruise != null && c.idle.total > 8 && c.cruise.total > 8 }),
            listOf(Rule(1.0, "MAF recently cleaned and the problem remains") { it.obs(Observation.RECENT_MAF_CLEAN) }),
            listOf("MAF g/s at warm idle and full throttle", "Estimated volumetric efficiency"),
            listOf(DiagnosticTest("Clean the MAF element with MAF cleaner only (no touching), reset/relearn trims and log again.", "Trims return towards zero after cleaning.", "No change after cleaning.", 1))),
        Def("exhaust_leak_pre_o2", "Exhaust leak before the upstream O2 sensor", "Lean reading",
            "A leak upstream of the sensor lets outside air in on exhaust pulses; the sensor reads lean and the ECU adds fuel, so the engine may actually run rich.",
            { it.lean },
            listOf(Rule(1.0, "Fuel trims are adding fuel") { it.lean },
                Rule(1.0, "Ticking exhaust noise / sooty plugs while trims say lean") { it.obs(Observation.SOOTY_PLUGS) && it.lean }),
            emptyList(),
            listOf("Visual/sound check of manifold and joints at cold start"),
            listOf(DiagnosticTest("Cold start: listen and feel (carefully) around the manifold and joints ahead of the sensor for ticking/puffing.", "Leak found.", "No leak at any joint.", 1))),
        // ---------------------------------------------------------------- misfire
        Def("misfire_ignition", "Ignition (coil or spark plug) on the misfiring cylinder", "Misfire",
            "Weak sparks are the most common single-cylinder misfire cause, especially under load.",
            { it.misfire },
            listOf(Rule(2.5, "Single-cylinder misfire code") { c -> c.codes.any { Regex("P03(0[1-9]|1[0-2])").matches(it) } },
                Rule(2.0, "Ignition coil circuit code") { c -> c.codes.any { it.startsWith("P035") || it.startsWith("P230") } },
                Rule(1.5, "Mode 06 shows one cylinder dominating") { c -> dominantCylinder(c.i.mode06) != null },
                Rule(1.0, "Misfire felt") { it.obs(Observation.MISFIRE_FEEL) }),
            listOf(Rule(1.0, "Random misfire across cylinders (common cause more likely)") { it.has("P0300") && !it.codes.any { c -> Regex("P03(0[1-9])").matches(c) } }),
            listOf("Mode 06 misfire counts per cylinder", "Plug condition"),
            listOf(DiagnosticTest("Swap the coil of the misfiring cylinder with a neighbour, clear codes, drive and re-read Mode 06/DTCs.", "The misfire moves to the other cylinder with the coil.", "Misfire stays in the same cylinder.", 1),
                DiagnosticTest("Remove and inspect the spark plug (gap, cracks, oil/fuel fouling); swap plugs between cylinders.", "Misfire follows the plug or the plug is visibly damaged.", "Plug looks normal and swapping changes nothing.", 2))),
        Def("misfire_injector", "Injector on the misfiring cylinder", "Misfire",
            "A clogged or leaking injector upsets one cylinder's mixture.",
            { it.misfire },
            listOf(Rule(1.5, "Single-cylinder misfire") { c -> c.codes.any { Regex("P03(0[1-9]|1[0-2])").matches(it) } },
                Rule(2.0, "Injector circuit code") { c -> c.codes.any { Regex("P02(0[1-9]|1[0-2])").matches(it) } },
                Rule(1.0, "Rich trims together with misfire") { it.rich && it.misfire }),
            emptyList(),
            listOf("Injector resistance / balance"),
            listOf(DiagnosticTest("After ruling out the coil and plug: swap injectors between cylinders (or balance test).", "Misfire follows the injector.", "Misfire stays with the cylinder.", 3))),
        Def("misfire_mechanical", "Low compression / mechanical problem", "Misfire",
            "If ignition and fuel are fine, the cylinder may not be sealing (valves, rings, head gasket).",
            { it.misfire },
            listOf(Rule(1.0, "Misfire present") { it.misfire },
                Rule(1.5, "White smoke or coolant loss reported") { it.obs(Observation.WHITE_SMOKE) || it.obs(Observation.COOLANT_LOSS) },
                Rule(1.0, "Blue smoke reported") { it.obs(Observation.BLUE_SMOKE) }),
            emptyList(),
            listOf("Compression and leak-down test"),
            listOf(DiagnosticTest("Compression test all cylinders (then leak-down on the low one).", "One cylinder clearly lower than the others.", "Compression even and within specification.", 3))),
        // ---------------------------------------------------------------- cooling
        Def("cool_overheat", "Cooling system fault (coolant, fan, thermostat stuck closed, pump)", "Cooling",
            "Coolant temperature is higher than the thermostat should allow.",
            { c -> (c.live("ect") ?: 0.0) > 104 || c.has("P0217") || c.obs(Observation.OVERHEAT_GAUGE) },
            listOf(Rule(3.0, "Coolant above 104 °C") { (it.live("ect") ?: 0.0) > 104 },
                Rule(3.0, "Overtemperature code") { it.has("P0217") },
                Rule(1.5, "Coolant loss reported") { it.obs(Observation.COOLANT_LOSS) },
                Rule(1.5, "Fan circuit code") { it.has("P0480", "P0481") }),
            emptyList(),
            listOf("Coolant level", "Fan switch-on temperature", "Upper/lower hose temperatures"),
            listOf(DiagnosticTest("Cold engine: check coolant level and look for leaks.", "Level low / leak found.", "Level correct, no leaks.", 1),
                DiagnosticTest("Idle to operating temperature watching the coolant PID: note when the fan starts.", "Fan does not start before ~100–105 °C.", "Fan starts normally.", 1),
                DiagnosticTest("Pressure test the cooling system / combustion gas (block) test.", "Pressure drops or combustion gas detected.", "System holds pressure, no combustion gas.", 3))),
        Def("cool_thermostat_open", "Thermostat stuck open / opening early", "Cooling",
            "The engine takes a long time to warm or never reaches normal temperature.",
            { c -> c.has("P0128", "P0125") || c.obs(Observation.HEATER_COLD) || ((c.live("ect") ?: 90.0) < 75 && (c.live("runtime") ?: 0.0) > 900) },
            listOf(Rule(3.0, "Thermostat code") { it.has("P0128") },
                Rule(1.5, "Coolant below 75 °C after 15+ minutes running") { (it.live("ect") ?: 90.0) < 75 && (it.live("runtime") ?: 0.0) > 900 },
                Rule(1.0, "Heater blows cold") { it.obs(Observation.HEATER_COLD) }),
            emptyList(),
            listOf("Warm-up curve from a cold start (record a session)"),
            listOf(DiagnosticTest("Record coolant temperature from a cold start for 15 minutes of driving.", "Temperature stalls well below the thermostat rating.", "Normal warm-up to ~85–95 °C.", 1))),
        // ---------------------------------------------------------------- boost
        Def("boost_leak", "Boost leak (intercooler hoses, couplers, intercooler)", "Boost",
            "Pressurised air escaping between the turbo and the throttle lowers boost; air measured by the MAF never reaches the engine.",
            { c -> c.has("P0299", "P2263") || c.obs(Observation.WHISTLE_BOOST) || (c.i.spec.turbocharged && c.obs(Observation.LOW_POWER)) },
            listOf(Rule(2.5, "Underboost code") { it.has("P0299", "P2263") },
                Rule(1.5, "Whistle/whoosh under boost") { it.obs(Observation.WHISTLE_BOOST) },
                Rule(1.0, "Low power") { it.obs(Observation.LOW_POWER) }),
            emptyList(),
            listOf("Boost vs RPM in a full-throttle pull", "Boost leak (pressure) test"),
            listOf(DiagnosticTest("Log a 3rd-gear full-throttle pull (on a safe, closed road, with a passenger operating the phone) and compare peak boost with specification.", "Boost clearly below specification.", "Boost reaches specification.", 1),
                DiagnosticTest("Pressure test the intake tract from the turbo outlet to the throttle body.", "Leak found.", "System holds pressure.", 2))),
        Def("boost_wastegate", "Wastegate stuck open or boost control fault", "Boost",
            "If the wastegate cannot close, exhaust bypasses the turbine and boost stays low.",
            { c -> c.has("P0299", "P0243", "P0245", "P0246", "P2263") },
            listOf(Rule(1.5, "Underboost code") { it.has("P0299", "P2263") },
                Rule(2.5, "Wastegate solenoid code") { it.has("P0243", "P0245", "P0246") }),
            emptyList(),
            listOf("Wastegate actuator movement", "Solenoid operation and hoses"),
            listOf(DiagnosticTest("Check the wastegate actuator rod moves freely and its hoses are intact and routed correctly.", "Rod seized/hose split.", "Moves freely, hoses fine.", 2))),
        Def("overboost", "Wastegate stuck closed / boost control fault (overboost)", "Boost",
            "Boost above the limit; the ECU may cut fuel to protect the engine.",
            { c -> c.has("P0234") || (c.live("boost") ?: 0.0) > c.i.spec.boostLimitKpa },
            listOf(Rule(3.0, "Overboost code") { it.has("P0234") }, Rule(2.0, "Boost above your configured limit") { (it.live("boost") ?: 0.0) > it.i.spec.boostLimitKpa }),
            emptyList(), listOf("Boost vs RPM log"),
            listOf(DiagnosticTest("Check wastegate actuator and control solenoid/hoses.", "Actuator seized or hoses wrong.", "Actuator and control normal.", 2))),
        // ---------------------------------------------------------------- electrical / O2 / catalyst
        Def("charging", "Charging system problem (alternator, belt, battery, connections)", "Electrical",
            "Voltage outside the normal running range.",
            { c -> c.has("P0562", "P0563", "P0560", "P0620") || c.obs(Observation.DIM_LIGHTS) || ((c.live("ecu_voltage") ?: c.live("adapter_voltage") ?: 14.0) < 13.0 && (c.live("rpm") ?: 0.0) > 500) },
            listOf(Rule(2.5, "System voltage code") { it.has("P0562", "P0563", "P0560") },
                Rule(2.0, "Running voltage below 13 V") { (it.live("ecu_voltage") ?: it.live("adapter_voltage") ?: 14.0) < 13.0 && (it.live("rpm") ?: 0.0) > 500 },
                Rule(1.0, "Dim lights / slow cranking") { it.obs(Observation.DIM_LIGHTS) }),
            emptyList(), listOf("Battery voltage engine off, cranking and running"),
            listOf(DiagnosticTest("Measure battery voltage engine off (≈12.6 V), cranking (should stay above ~9.6 V) and at 2000 rpm (≈13.5–14.7 V).", "Running voltage out of range / cranking voltage collapses.", "All values normal.", 1))),
        Def("o2_inactive", "Upstream O2 sensor slow or inactive", "Sensors",
            "The upstream sensor should switch quickly; a stuck or slow sensor gives the ECU poor feedback.",
            { c -> c.has("P0133", "P0134", "P0153", "P0154", "P2195", "P2196") },
            listOf(Rule(3.0, "O2 sensor activity/response code") { it.has("P0133", "P0134", "P0153", "P0154") },
                Rule(1.0, "Live O2 voltage near 0.45 V (bias voltage = sensor not active)") { c -> c.live("o2_b1s1_v")?.let { abs(it - 0.45) < 0.03 } ?: false }),
            listOf(Rule(1.0, "Heater code also present (a cold sensor stays inactive)") { it.has("P0135", "P0155") }),
            listOf("Upstream O2 voltage graph at 2500 rpm"),
            listOf(DiagnosticTest("Graph the upstream O2 sensor at steady 2500 rpm once fully warm.", "Signal flat or switching very slowly.", "Switches cleanly about 0.1–0.9 V several times per second.", 1),
                DiagnosticTest("Check the O2 sensor heater resistance and supply.", "Heater open / no supply.", "Heater within specification.", 2))),
        Def("catalyst", "Catalytic converter efficiency reduced", "Emissions",
            "The downstream sensor starts copying the upstream sensor when the catalyst no longer stores oxygen.",
            { c -> c.has("P0420", "P0430") },
            listOf(Rule(3.0, "Catalyst efficiency code") { it.has("P0420", "P0430") },
                Rule(1.0, "Mode 06 catalyst test failed") { c -> c.i.mode06.any { it.mid in 0x21..0x24 && !it.passed } }),
            listOf(Rule(1.5, "Misfire or fuel trim faults present (may have caused or skewed the result)") { it.misfire || it.rich || it.lean }),
            listOf("Upstream vs downstream O2 graph", "Mode 06 catalyst monitor"),
            listOf(DiagnosticTest("Fix misfire and fuel trim problems first, then graph upstream vs downstream O2 at 2500 rpm.", "Downstream switches like the upstream sensor.", "Downstream stays steady.", 1))),
    )

    companion object {
        fun dominantCylinder(m06: List<Mode06.TestResult>): Int? {
            val cyl = m06.filter { Mode06.isMisfireCylinder(it.mid) }.groupBy { it.mid - 0xA1 }.mapValues { e -> e.value.maxOf { it.value } }
            val total = cyl.values.sum()
            if (total < 10) return null
            return cyl.entries.firstOrNull { it.value / total > 0.5 }?.key
        }
    }

    fun analyse(input: AssistantInput): AssistantReport {
        val c = Ctx(input)
        val interp = interpret(c)
        val conditions = ArrayList<String>()
        if (c.rich) conditions.add("Possible rich-running condition: the ECU is removing fuel" + (c.total?.let { " (total trim ${fmt(it)} %)" } ?: "") + ".")
        if (c.lean) conditions.add("Possible lean condition: the ECU is adding fuel" + (c.total?.let { " (total trim ${fmt(it)} %)" } ?: "") + ".")
        if (c.misfire) conditions.add("Misfire reported by the ECU or by you.")
        if ((c.live("ect") ?: 0.0) > 104) conditions.add("Engine running hot (${fmt(c.live("ect")!!)} °C).")

        val hyps = defs.filter { it.applies(c) }.map { d ->
            val forEv = d.forRules.filter { r -> runCatching { r.test(c) }.getOrDefault(false) }
            val againstEv = d.againstRules.filter { r -> runCatching { r.test(c) }.getOrDefault(false) }
            var score = forEv.sumOf { it.weight } - againstEv.sumOf { it.weight }
            val outcome = input.testResults[d.id] ?: TestOutcome.NOT_DONE
            if (outcome == TestOutcome.CONFIRMED) score += 100 else if (outcome == TestOutcome.ELIMINATED) score -= 100
            Hypothesis(d.id, d.title, d.category, d.explanation, score,
                forEv.filter { it.weight > 0 }.map { it.text }, againstEv.map { it.text } + forEv.filter { it.weight < 0 }.map { it.text },
                d.measurements, d.tests, outcome)
        }.sortedByDescending { it.score }

        // Test plan: from the hypotheses still open, cheapest tests of the best-supported first.
        val open = hyps.filter { it.outcome == TestOutcome.NOT_DONE }.take(6)
        val plan = open.flatMapIndexed { rank, h -> h.tests.map { Triple(rank, h, it) } }
            .sortedWith(compareBy({ it.third.effort }, { it.first }))
            .map { it.second.title to it.third }

        val missing = ArrayList<String>()
        if (c.ltft == null) missing.add("Fuel trims (record a session including idle and steady cruise)")
        if (input.trimBins.isEmpty() && c.ltft != null) missing.add("A recorded session: idle vs cruise trims separate a vacuum leak from a fuel delivery problem")
        if (input.dtcs.isEmpty()) missing.add("Trouble codes (read stored, pending and permanent codes)")
        if (c.misfire && input.mode06.none { Mode06.isMisfireCylinder(it.mid) }) missing.add("Mode 06 misfire counts per cylinder (if your ECU supports them)")
        if (input.observations.isEmpty()) missing.add("Your own observations (plugs, smells, noises): they often decide between causes")

        val headline = when {
            hyps.isEmpty() && input.dtcs.isEmpty() -> "No fault pattern found in the available data."
            hyps.isEmpty() -> "Codes present but no specific pattern rule applies; see each code's details."
            else -> "Most supported: ${hyps.first().title} (${hyps.first().strength.lowercase()})."
        }
        return AssistantReport(headline, interp, conditions, hyps, plan, missing,
            "These are hypotheses ranked by the evidence available, not confirmed faults. Confirm each with the listed tests before replacing parts, and never replace a part on a single reading.")
    }

    private fun interpret(c: Ctx): List<Interpretation> {
        val out = ArrayList<Interpretation>()
        c.ltft?.let {
            out.add(Interpretation("Long term fuel trim ${fmt(it)} %",
                if (it < -10) "The ECU has learned to remove about ${fmt(-it)} % fuel. Without that correction the engine would run rich, so something is adding fuel or the airflow reading is too high."
                else if (it > 10) "The ECU has learned to add about ${fmt(it)} % fuel. Something is letting in unmeasured air, under-delivering fuel, or the airflow reading is too low."
                else "Within the normal ±10 % band."))
        }
        c.stft?.let {
            out.add(Interpretation("Short term fuel trim ${fmt(it)} %",
                if (abs(it) > 10) "The ECU is still correcting right now on top of the learned trim; the condition is active." else "Small momentary correction: the learned trim is carrying most of the correction."))
        }
        c.total?.let { out.add(Interpretation("Total trim ${fmt(it)} %", "STFT + LTFT is the total correction. Beyond ±10 % deserves investigation; beyond ±20 % usually sets a lean/rich code.")) }
        if (c.idle != null && c.cruise != null) out.add(Interpretation(
            "Idle ${fmt(c.idle.total)} % vs cruise ${fmt(c.cruise.total)} %",
            "Comparing conditions is the key separator: a constant leak/drip matters most at idle; pressure and MAF errors scale with flow."))
        c.live("o2_b1s1_v")?.let { out.add(Interpretation("Upstream O2 ${String.format("%.2f", it)} V", "Narrowband sensors switch about 0.1 V (lean) to 0.9 V (rich). A single reading says little: watch the graph.")) }
        c.live("ect")?.let { out.add(Interpretation("Coolant ${fmt(it)} °C", if (it > 104) "Higher than a thermostat-controlled engine should run." else if (it < 70) "Engine not at operating temperature; trims and many monitors behave differently until warm." else "Normal operating range.")) }
        for (d in c.i.dtcs.filter { it.kind == DtcKind.STORED || it.kind == DtcKind.PENDING }) {
            out.add(Interpretation("${d.code} (${d.kind.label})", app.obdforge.core.dtc.DtcDatabase.describe(d.code) ?: "Manufacturer-specific code"))
        }
        for (o in c.i.observations) out.add(Interpretation(o.label, "Reported by you."))
        return out
    }

    private fun fmt(v: Double) = EngineAnalysis.fmt(v)
}
