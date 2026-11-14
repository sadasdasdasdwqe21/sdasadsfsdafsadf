package app.obdforge.core.dtc

enum class Severity(val label: String, val advice: String, val rank: Int) {
    LOW("Low", "Fix at the next convenient service. Usually no immediate risk to the engine.", 1),
    MODERATE("Moderate", "Diagnose soon. Driving is usually possible but economy, emissions or drivability may suffer.", 2),
    HIGH("High", "Diagnose before long trips. Continued driving can damage the engine, catalyst or transmission.", 3),
    CRITICAL("Critical", "Stop driving as soon as it is safe. Risk of serious engine damage or a safety problem.", 4),
}

/**
 * Everything the app shows for one code. All causes listed here are *possible* causes from the
 * generic meaning of the code; nothing becomes "confirmed" until the user records test results
 * in an investigation (see the diagnostic assistant).
 */
data class DtcInfo(
    val code: String,
    val description: String,
    val system: String,
    val severity: Severity,
    val symptoms: List<String>,
    val possibleCauses: List<String>,
    val diagnosticSteps: List<String>,
    val relevantChannels: List<String>,
    val related: List<String>,
    val repairNotes: List<String>,
    /** True when the details are generic guidance derived from the code type, not a specific entry. */
    val genericGuidance: Boolean,
)

object DtcKnowledge {
    private class K(
        val severity: Severity,
        val symptoms: List<String>,
        val causes: List<String>,
        val steps: List<String>,
        val channels: List<String>,
        val related: List<String>,
        val notes: List<String>,
    )

    private val kb = HashMap<String, K>()

    private fun put(codes: List<String>, k: K) = codes.forEach { kb[it] = k }

    init {
        put(listOf("P0171", "P0174"), K(
            Severity.MODERATE,
            listOf("Hesitation or flat spots on acceleration", "Rough or hunting idle", "Possible misfire under load", "Sometimes no symptoms"),
            listOf("Unmetered air entering after the MAF (vacuum/PCV hose, intake coupler, brake booster line, intake gasket)",
                "MAF sensor under-reading (contaminated element, air leak around sensor, wrong sensor)",
                "Low fuel pressure (weak pump, clogged filter, regulator)", "Restricted or dirty injectors",
                "Exhaust leak ahead of the upstream O2 sensor (pulls in air, sensor reads lean)",
                "Upstream O2/air-fuel sensor fault (less common: confirm before blaming)"),
            listOf("Check freeze frame: was it set at idle, cruise or high load?",
                "Compare total fuel trim (STFT+LTFT) at idle, 2500 rpm no load and at cruise. Lean at idle that improves with rpm points to a vacuum leak; lean that gets worse with load points to fuel delivery or MAF.",
                "Look for vacuum leaks: listen for hissing, smoke test the intake if possible.",
                "Compare MAF g/s at warm idle with the engine size (roughly 2–3 g/s per litre is typical at idle for many gasoline engines; check your service data).",
                "Measure fuel pressure with a gauge (or rail pressure PID on direct injection) against spec, including under load.",
                "On both-bank engines: if both banks are lean, look for a common cause (MAF, fuel pressure); one bank only points to a bank-specific leak or injector."),
            listOf("stft1", "ltft1", "stft2", "ltft2", "maf", "map", "rpm", "load", "frp", "o2_b1s1_v", "o2w1_lambda"),
            listOf("P0170", "P0173", "P0101", "P0300", "P2187", "P2177"),
            listOf("Do not replace the O2 sensor because of a lean code alone: the sensor is usually reporting a real lean mixture.",
                "After repairs, clear fuel trims (or let them relearn) and verify trims return near 0 ± 10 %.")))
        put(listOf("P0172", "P0175"), K(
            Severity.MODERATE,
            listOf("Poor fuel economy", "Fuel smell from exhaust", "Black/sooty spark plugs or exhaust", "Rough idle or stumble on restart"),
            listOf("Leaking or dripping injector(s)", "Fuel pressure too high (regulator, return restriction, high-pressure pump control on DI)",
                "MAF sensor over-reading", "EVAP purge valve stuck open (fuel vapour drawn in continuously)",
                "Fuel-diluted engine oil feeding vapour through the PCV", "Coolant temperature sensor reading colder than actual (extra enrichment)",
                "Upstream O2/air-fuel sensor biased rich (confirm before blaming)"),
            listOf("Check freeze frame load/rpm and whether it set at idle or under load.",
                "Watch LTFT/STFT at idle with the purge hose pinched or disconnected (capped): if trims move towards 0, the purge valve is passing vapour.",
                "Smell the oil dipstick for fuel; a fuel-diluted crankcase drives trims rich, worst after warm-up.",
                "Compare coolant temperature PID with an IR thermometer reading at the thermostat housing.",
                "Check fuel pressure / rail pressure against spec and check it holds pressure after shutdown (leak-down test).",
                "Read spark plugs: uniformly sooty points to a common cause, one sooty plug to that cylinder's injector."),
            listOf("stft1", "ltft1", "stft2", "ltft2", "maf", "ect", "evap_purge", "frp", "o2_b1s1_v", "lambda_cmd"),
            listOf("P0170", "P0173", "P2188", "P2178", "P0101", "P0128"),
            listOf("Large negative trims after an oil change can come from fuel in the oil; recheck after the oil is changed and the engine driven.",
                "Do not replace parts from one reading: confirm with a second measurement (fuel pressure leak-down, purge pinch test, injector balance).")))
        put(listOf("P0300"), K(
            Severity.HIGH,
            listOf("Shaking or rough running", "Flashing MIL (catalyst-damaging misfire)", "Loss of power"),
            listOf("Worn spark plugs or wrong gap", "Weak ignition coils", "Vacuum leak (affects all cylinders, worst at idle)",
                "Low fuel pressure or contaminated fuel", "Lean condition", "Mechanical: low compression, valve or timing problem"),
            listOf("Check Mode 06 misfire counters per cylinder to see whether it is really random or concentrated in one/two cylinders.",
                "Check fuel trims: a lean condition can cause multiple misfires.",
                "Inspect plugs and coils; swap a coil between cylinders and see if the misfire follows.",
                "If misfires persist with good ignition and fuel, do a compression or leak-down test."),
            listOf("rpm", "load", "stft1", "ltft1", "maf", "map", "ect"),
            listOf("P0301", "P0302", "P0303", "P0304", "P0171", "P0174"),
            listOf("A flashing MIL means unburnt fuel is reaching the catalyst: reduce load and stop driving as soon as possible.")))
        put((1..12).map { "P03" + it.toString().padStart(2, '0') }, K(
            Severity.HIGH,
            listOf("Rough running, shaking at idle or under load", "Flashing MIL during heavy misfire", "Power loss"),
            listOf("Spark plug in that cylinder (wear, fouling, cracked insulator)", "Ignition coil for that cylinder",
                "Injector for that cylinder (clogged, leaking, wiring)", "Local vacuum leak at that runner/intake gasket",
                "Low compression in that cylinder (valve, rings, head gasket)"),
            listOf("Read Mode 06 misfire counts for the cylinder to see how often it misfires and under which conditions.",
                "Swap the coil with a neighbouring cylinder, clear codes, drive: if the misfire moves, the coil is at fault.",
                "Swap the spark plug the same way; inspect it for oil, fuel fouling or cracks.",
                "If ignition is fine, swap injectors (or do an injector balance/noid light test).",
                "If the misfire stays with the cylinder after swaps, run a compression and leak-down test."),
            listOf("rpm", "load", "stft1", "ltft1", "maf"),
            listOf("P0300", "P0351", "P0352", "P0353", "P0354", "P0201", "P0202", "P0203", "P0204"),
            listOf("Swapping parts between cylinders is the cheapest way to separate coil, plug and injector without replacing anything.")))
        put(listOf("P0420", "P0430"), K(
            Severity.MODERATE,
            listOf("Usually none", "Sometimes rotten-egg smell or reduced power if the catalyst is blocked"),
            listOf("Catalyst efficiency degraded (age, oil/coolant contamination, misfire damage)", "Exhaust leak near the O2 sensors",
                "Downstream O2 sensor slow or biased", "Engine running rich or lean, or misfiring, upsetting the test"),
            listOf("First fix any misfire or fuel trim codes: they are the usual cause of catalyst damage.",
                "Graph upstream and downstream O2 sensors at steady 2500 rpm: the downstream sensor should be fairly steady while upstream switches.",
                "Check Mode 06 catalyst monitor result and its limit.",
                "Check for exhaust leaks before and between the sensors."),
            listOf("o2_b1s1_v", "o2_b1s2_v", "o2_b2s1_v", "o2_b2s2_v", "cat_b1s1", "stft1", "ltft1"),
            listOf("P0421", "P0300", "P0171", "P0172", "P0136", "P0137", "P0138"),
            listOf("Replacing the catalyst without curing the cause (misfire, oil burning, rich running) will damage the new one.")))
        put(listOf("P0128"), K(
            Severity.LOW,
            listOf("Slow warm-up", "Heater blows cool air", "Higher fuel consumption"),
            listOf("Thermostat stuck open or opening early", "Coolant temperature sensor reading low", "Low coolant level", "Cooling fan running all the time"),
            listOf("Log coolant temperature from a cold start: it should rise steadily and level off near the thermostat rating.",
                "Compare coolant PID with an IR thermometer at the thermostat housing.",
                "Check whether the cooling fan runs on a cold engine."),
            listOf("ect", "iat", "ambient", "runtime"),
            listOf("P0125", "P0116", "P0117", "P0118"),
            listOf("The warm-up analysis in this app measures the time to 80 °C from your logged session.")))
        put(listOf("P0217"), K(
            Severity.CRITICAL,
            listOf("Temperature gauge high", "Loss of power (engine protection)", "Steam or coolant smell"),
            listOf("Low coolant / leak", "Cooling fan not running", "Thermostat stuck closed", "Water pump failure", "Blocked radiator", "Head gasket leaking combustion gas into coolant"),
            listOf("Stop the engine and let it cool before opening the cooling system.", "Check the coolant level and look for leaks.",
                "With the engine warm, confirm the fan switches on near its set point.", "Check radiator hose temperatures above/below the thermostat."),
            listOf("ect", "oil_temp", "load", "speed"),
            listOf("P0115", "P0480", "P0128"),
            listOf("Continued driving while overheating can warp the cylinder head and destroy the head gasket.")))
        put(listOf("P0101", "P0100", "P0102", "P0103"), K(
            Severity.MODERATE,
            listOf("Hesitation, poor power", "Rough idle or stalling", "Fuel trims far from zero"),
            listOf("Dirty or contaminated MAF element", "Air leak between MAF and throttle", "Wiring/connector fault (circuit codes)", "Restricted air filter or intake", "Faulty MAF sensor"),
            listOf("Compare MAF g/s at warm idle and at 2500 rpm with expected values for the engine size.",
                "Check the intake ducting after the MAF for cracks and loose clamps.",
                "For circuit codes, check sensor power, ground and signal at the connector.",
                "Clean the MAF element with dedicated MAF cleaner and re-test before replacing."),
            listOf("maf", "map", "rpm", "load", "iat", "stft1", "ltft1", "vol_eff"),
            listOf("P0171", "P0172", "P0068"),
            emptyList()))
        put(listOf("P0113", "P0112", "P0110"), K(
            Severity.LOW,
            listOf("Usually minor drivability changes"),
            listOf("Sensor unplugged or open circuit (P0113 high input usually = open circuit)", "Wiring short to ground (low input)", "Faulty IAT sensor"),
            listOf("Read the IAT PID: -40 °C usually means an open circuit, very high values a short.", "Check the connector and wiring, then the sensor resistance against spec."),
            listOf("iat", "ambient"), listOf("P0111", "P0114"), emptyList()))
        put(listOf("P0117", "P0118", "P0115", "P0116"), K(
            Severity.MODERATE,
            listOf("Hard starting", "Rich running (sensor reads cold)", "Fan running constantly (fail-safe)"),
            listOf("Wiring or connector fault", "Faulty coolant temperature sensor", "Low coolant level exposing the sensor"),
            listOf("Compare the ECT PID with an IR thermometer reading.", "Check wiring and connector; measure sensor resistance hot and cold."),
            listOf("ect", "iat", "ltft1", "stft1"), listOf("P0128", "P0125"), emptyList()))
        put(listOf("P0134", "P0133", "P0130", "P0131", "P0132", "P0150", "P0151", "P0152", "P0153", "P0154"), K(
            Severity.MODERATE,
            listOf("Possible poor economy", "Often no drivability symptom"),
            listOf("O2 sensor ageing/contamination", "Heater not working (sensor too cold)", "Wiring fault", "Exhaust leak near the sensor", "Real mixture problem the sensor is reporting"),
            listOf("Graph the sensor at 2500 rpm: a healthy narrowband sensor switches between about 0.1 and 0.9 V several times per second.",
                "Check the heater circuit (resistance) and any heater codes.", "Check for exhaust leaks upstream of the sensor.",
                "Check fuel trims: a real rich/lean condition can look like a sensor fault."),
            listOf("o2_b1s1_v", "o2_b2s1_v", "stft1", "ltft1", "o2w1_lambda"),
            listOf("P0135", "P0155", "P0171", "P0172"), emptyList()))
        put(listOf("P0135", "P0141", "P0155", "P0161", "P0030", "P0031", "P0032", "P0036", "P0037", "P0038", "P0050", "P0051", "P0052"), K(
            Severity.LOW,
            listOf("Usually none; slower closed loop after cold start"),
            listOf("O2 sensor heater element open", "Blown heater fuse/relay", "Wiring or connector fault"),
            listOf("Measure the heater resistance at the sensor connector and compare with spec.", "Check heater power supply (fuse) with ignition on."),
            listOf("o2_b1s1_v", "o2_b1s2_v"), listOf("P0134", "P0140"), emptyList()))
        put(listOf("P0299"), K(
            Severity.HIGH,
            listOf("Lack of power, especially mid-range", "Limp mode on some vehicles", "Whistling/hissing under boost"),
            listOf("Boost leak (intercooler hoses, couplers, intercooler, intake gaskets)", "Wastegate stuck open or its control (solenoid/actuator) faulty",
                "Turbocharger damage (shaft play, damaged wheels)", "Restricted air filter or exhaust", "Boost pressure sensor fault"),
            listOf("Log boost (MAP − baro) against RPM in a full-throttle pull in 3rd/4th gear and compare with spec.",
                "Compare commanded vs actual boost if the ECU reports PID 70 or a manufacturer profile provides it.",
                "Pressure-test the intake tract (boost leak test) with the engine off.",
                "Check wastegate actuator movement and the control solenoid hoses."),
            listOf("map", "baro", "boost", "boost_cmd_a", "boost_act_a", "rpm", "tps", "load", "maf"),
            listOf("P0234", "P0243", "P0245", "P0246", "P0236"),
            listOf("A boost leak usually also shows high MAF-calculated airflow versus MAP; logging both helps.")))
        put(listOf("P0234"), K(
            Severity.CRITICAL,
            listOf("Sudden power cut (fuel cut)", "Surging under load"),
            listOf("Wastegate stuck closed or seized", "Wastegate control solenoid stuck / hoses swapped or blocked", "Boost pressure sensor reading high", "Aftermarket boost controller/tune"),
            listOf("Log boost vs RPM and compare peak with spec.", "Check the wastegate actuator rod moves freely.", "Check control hoses and solenoid operation."),
            listOf("map", "baro", "boost", "rpm", "tps"), listOf("P0299", "P0243"),
            listOf("Overboost can damage pistons and the turbo; avoid full load until fixed.")))
        put(listOf("P0087"), K(
            Severity.HIGH,
            listOf("Hesitation or stall under load", "Hard start", "Limp mode"),
            listOf("Weak low-pressure fuel pump", "Clogged fuel filter", "High-pressure pump wear (direct injection)", "Fuel pressure sensor fault", "Wiring to pump"),
            listOf("Log fuel rail pressure and its target (if available) during acceleration.", "Check low-side pressure with a gauge.", "Check pump supply voltage under load."),
            listOf("frp", "frp_abs", "fuel_pressure", "rpm", "load"), listOf("P0088", "P0191", "P0171"), emptyList()))
        put(listOf("P0088"), K(
            Severity.HIGH,
            listOf("Rich running", "Rough idle"),
            listOf("Fuel pressure regulator / high-pressure pump control fault", "Fuel pressure sensor reading high", "Blocked return line (return systems)"),
            listOf("Compare rail pressure PID with a mechanical gauge where possible.", "Check the regulator/pump control signal."),
            listOf("frp", "frp_abs", "ltft1", "stft1"), listOf("P0087", "P0191", "P0172"), emptyList()))
        put(listOf("P0191", "P0190", "P0192", "P0193"), K(
            Severity.HIGH,
            listOf("Hard start, stalling, limp mode"),
            listOf("Fuel rail pressure sensor fault", "Wiring/connector", "Actual pressure problem"),
            listOf("Compare rail pressure with key-on engine-off and running values against spec.", "Check sensor 5 V reference, ground and signal."),
            listOf("frp", "frp_abs"), listOf("P0087", "P0088"), emptyList()))
        put(listOf("P0335", "P0336", "P0337", "P0338"), K(
            Severity.HIGH,
            listOf("Crank-no-start", "Stalling", "Tachometer drops out"),
            listOf("Crankshaft position sensor", "Wiring/connector", "Damaged reluctor (tone) ring", "Excessive sensor air gap"),
            listOf("Watch the RPM PID while cranking: 0 rpm while cranking points to the crank sensor or its wiring.", "Inspect connector and wiring; check sensor resistance/output per service data."),
            listOf("rpm"), listOf("P0340", "P0016"), emptyList()))
        put(listOf("P0340", "P0341", "P0342", "P0343", "P0345", "P0365", "P0390"), K(
            Severity.MODERATE,
            listOf("Long crank time", "Possible stalling"),
            listOf("Camshaft position sensor", "Wiring/connector", "Timing chain/belt stretch or jumped tooth (especially with P0016-P0019)"),
            listOf("Check wiring and sensor.", "If correlation codes are also present, verify mechanical timing."),
            listOf("rpm"), listOf("P0016", "P0335"), emptyList()))
        put(listOf("P0016", "P0017", "P0018", "P0019"), K(
            Severity.HIGH,
            listOf("Rough running", "Rattle on start-up", "Reduced power"),
            listOf("Timing chain/belt stretched or jumped a tooth", "Variable valve timing actuator stuck", "Low or dirty oil affecting VVT", "Sensor or reluctor fault"),
            listOf("Check oil level and condition first.", "Check VVT-related codes and live cam angle data if your profile provides it.", "Verify mechanical timing marks per service manual."),
            listOf("rpm", "timing"), listOf("P0011", "P0014", "P0340", "P0335"),
            listOf("Running with incorrect cam timing risks valve/piston contact on interference engines.")))
        put(listOf("P0011", "P0012", "P0014", "P0015", "P0021", "P0022", "P0024", "P0025", "P0010", "P0013", "P0020", "P0023"), K(
            Severity.MODERATE,
            listOf("Rough idle", "Poor power or economy"),
            listOf("Low oil level or wrong viscosity", "Oil control valve (OCV) sticking or its filter screen blocked", "Cam phaser fault", "Timing chain wear", "Wiring (circuit codes)"),
            listOf("Check oil level/condition.", "For circuit codes check OCV resistance and wiring.", "Inspect the OCV and its screen."),
            listOf("rpm", "timing"), listOf("P0016", "P0017"), emptyList()))
        put(listOf("P0442", "P0456", "P0455", "P0457", "P0440"), K(
            Severity.LOW,
            listOf("Usually none", "Fuel smell (large leaks)"),
            listOf("Loose or worn fuel cap seal", "Cracked EVAP hoses", "Leaking purge or vent valve", "Charcoal canister damage", "Filler neck corrosion"),
            listOf("Check and re-seat the fuel cap first; inspect its seal.", "Smoke test the EVAP system.", "Check purge and vent valve operation."),
            listOf("evap_vp", "evap_purge", "fuel_level"), listOf("P0441", "P0446", "P0455"),
            listOf("Many EVAP monitors only run with fuel level between about 15 % and 85 % and a cold soak.")))
        put(listOf("P0500", "P0501", "P0502", "P0503"), K(
            Severity.MODERATE,
            listOf("Speedometer erratic or dead", "Harsh/incorrect shifting", "Cruise control inoperative"),
            listOf("Vehicle speed sensor", "Wiring/connector", "ABS wheel speed data problem (on cars that use ABS for VSS)"),
            listOf("Compare OBD speed with GPS speed in the live data screen.", "Check ABS codes if the car uses wheel speed for VSS."),
            listOf("speed", "gps_speed"), listOf("P0720", "U0121"), emptyList()))
        put(listOf("P0505", "P0506", "P0507"), K(
            Severity.LOW,
            listOf("Idle too low/high or hunting", "Stalling when stopping"),
            listOf("Dirty throttle body", "Vacuum leak (high idle)", "Idle air control fault", "Electronic throttle relearn needed"),
            listOf("Check idle RPM warm with loads off/on.", "Clean the throttle body and perform the idle relearn if specified.", "Look for vacuum leaks if idle is high."),
            listOf("rpm", "tps", "maf", "stft1", "ltft1"), listOf("P0171", "P2111", "P2112"), emptyList()))
        put(listOf("P0562", "P0563", "P0560"), K(
            Severity.MODERATE,
            listOf("Dim lights", "Hard starting", "Other electrical codes"),
            listOf("Charging system fault (alternator, regulator, belt)", "Weak battery", "Poor battery/ground connections"),
            listOf("Compare adapter voltage and ECU voltage: engine running should be about 13.5–14.7 V.", "Load-test the battery; check belt and connections."),
            listOf("ecu_voltage", "adapter_voltage", "rpm"), listOf("P0620", "P0622"), emptyList()))
        put(listOf("P0401", "P0400", "P0402", "P0404"), K(
            Severity.MODERATE,
            listOf("Pinging/knock under load (insufficient flow)", "Rough idle (excess flow)"),
            listOf("Carbon-blocked EGR passages", "EGR valve stuck", "EGR control solenoid/vacuum fault"),
            listOf("Command/observe EGR position if available.", "Inspect and clean passages."),
            listOf("egr_cmd", "egr_err", "map", "rpm"), emptyList(), emptyList()))
        put(listOf("P0700"), K(
            Severity.MODERATE,
            listOf("Depends on the transmission fault", "Possible limp mode (fixed gear)"),
            listOf("The transmission control module has its own code stored"),
            listOf("Read codes from the transmission module (ECU scan / enhanced codes) to find the actual fault."),
            listOf("speed", "rpm"), listOf("P0710", "P0715", "P0720"),
            listOf("P0700 itself is only a 'see the TCM' flag; the real code lives in the transmission ECU.")))
        put(listOf("U0100", "U0101", "U0121", "U0155", "U0140"), K(
            Severity.HIGH,
            listOf("Several warning lights", "Modules not responding"),
            listOf("Module power or ground loss", "CAN bus wiring fault (open/short)", "Module failure", "Low battery voltage during cranking"),
            listOf("Check battery voltage and the module's fuses.", "Run an ECU scan to see which modules answer.", "Measure CAN bus termination (about 60 Ω across CAN-H/CAN-L with the battery disconnected)."),
            listOf("ecu_voltage", "adapter_voltage"), emptyList(), emptyList()))
        put(listOf("P0325", "P0327", "P0328", "P0330", "P0332", "P0333"), K(
            Severity.MODERATE,
            listOf("Reduced power (timing retarded as a precaution)", "Higher fuel consumption"),
            listOf("Knock sensor or its wiring", "Incorrect sensor torque", "Real knock from low-octane fuel or carbon"),
            listOf("Check wiring and connector.", "Check sensor mounting torque.", "Watch ignition timing under load: a sensor fault often holds timing low."),
            listOf("timing", "rpm", "load"), emptyList(), emptyList()))
        put(listOf("P0351", "P0352", "P0353", "P0354", "P0355", "P0356", "P0357", "P0358"), K(
            Severity.HIGH,
            listOf("Misfire on the affected cylinder", "Rough running"),
            listOf("Ignition coil", "Coil connector/wiring", "ECM driver fault (rare)"),
            listOf("Swap the coil with another cylinder and see if the code follows.", "Check coil power and trigger signal."),
            listOf("rpm", "load"), listOf("P0300", "P0301", "P0302", "P0303", "P0304"), emptyList()))
        put((1..12).map { "P02" + it.toString().padStart(2, '0') }, K(
            Severity.HIGH,
            listOf("Misfire on the affected cylinder"),
            listOf("Injector coil open/shorted", "Wiring or connector fault", "ECM driver fault (rare)"),
            listOf("Measure injector resistance and compare with others.", "Check the injector pulse with a noid light or scope."),
            listOf("rpm", "stft1", "ltft1"), listOf("P0300"), emptyList()))
    }

    fun info(code: String, make: String? = null): DtcInfo {
        val c = code.uppercase()
        val desc = DtcDatabase.describe(c, make)
        val d = Dtc(c, DtcKind.STORED, 0)
        val k = kb[c]
        if (k != null) {
            return DtcInfo(c, desc ?: "Unknown", d.subsystem, k.severity, k.symptoms, k.causes, k.steps, k.channels, k.related, k.notes, false)
        }
        return generic(c, desc, d)
    }

    fun hasDetailedEntry(code: String) = kb.containsKey(code.uppercase())

    /** Guidance built only from what the code text says (circuit low/high, range/performance...). */
    private fun generic(c: String, desc: String?, d: Dtc): DtcInfo {
        val text = (desc ?: "").lowercase()
        val causes = ArrayList<String>()
        val steps = ArrayList<String>()
        var sev = Severity.MODERATE
        when {
            desc == null -> {
                causes.add("This code is manufacturer-specific and its meaning is not in the generic database.")
                steps.add("Look up the code in the manufacturer's service information for this vehicle, or import a vehicle profile that defines it.")
                steps.add("Check the freeze frame data for the conditions when it set.")
            }
            text.contains("circuit low") || text.contains("low input") || text.contains("low voltage") -> {
                causes += listOf("Signal shorted to ground", "Open sensor reference/supply", "Faulty sensor/actuator", "Connector corrosion or damage")
                steps += listOf("Inspect the connector and wiring for damage or corrosion.", "With the ignition on, check reference voltage, ground and signal at the component against service data.", "Check the live value: a reading pinned at the minimum often means a circuit fault.")
            }
            text.contains("circuit high") || text.contains("high input") || text.contains("high voltage") -> {
                causes += listOf("Open signal or ground circuit", "Signal shorted to voltage", "Faulty sensor/actuator", "Connector problem")
                steps += listOf("Inspect the connector and wiring.", "Check ground and signal circuits for opens.", "A live value pinned at the maximum often means an open circuit.")
            }
            text.contains("range/performance") || text.contains("performance") -> {
                causes += listOf("Sensor reading implausible compared with other sensors", "Mechanical problem in the monitored system", "Intermittent connection")
                steps += listOf("Compare the sensor's live value with related sensors and with a known-good reference measurement.", "Check freeze frame conditions and try to reproduce them while logging.")
            }
            text.contains("circuit") || text.contains("open") -> {
                causes += listOf("Wiring or connector fault", "Faulty component", "Blown fuse or relay")
                steps += listOf("Check the fuse/relay feeding the circuit.", "Inspect wiring and connector.", "Test the component per service data.")
            }
            text.contains("lost communication") -> {
                sev = Severity.HIGH
                causes += listOf("Module power/ground loss", "Network wiring fault", "Module failure")
                steps += listOf("Check module fuses and battery voltage.", "Run an ECU scan to see which modules respond.")
            }
            else -> {
                causes += listOf("See the code description; follow the manufacturer's diagnostic procedure for this vehicle.")
                steps += listOf("Check the freeze frame data.", "Log the related sensors while reproducing the conditions.")
            }
        }
        if (text.contains("misfire") || text.contains("overtemperature") || text.contains("overspeed")) sev = Severity.HIGH
        if (c.startsWith("P07") || c.startsWith("P08")) steps.add("Read the transmission module codes and fluid temperature/level.")
        return DtcInfo(c, desc ?: "Manufacturer-specific code (no description in the generic database)", d.subsystem, sev,
            emptyList(), causes, steps, emptyList(), emptyList(), emptyList(), true)
    }
}
