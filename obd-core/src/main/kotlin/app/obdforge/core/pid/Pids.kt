package app.obdforge.core.pid

enum class Category(val label: String) {
    ENGINE("Engine"),
    FUEL("Fuel system"),
    AIR("Intake & airflow"),
    TURBO("Turbo & boost"),
    COOLING("Cooling"),
    EMISSIONS("Emissions"),
    OXYGEN("Oxygen sensors"),
    ELECTRICAL("Electrical"),
    TRANSMISSION("Transmission"),
    VEHICLE("Vehicle"),
    STATUS("Status"),
    CALCULATED("Calculated"),
}

/**
 * A single numeric value shown to the user (gauge, graph, log column).
 *
 * [source] tells where the value comes from:
 *  - [Source.ECU]        read directly from the ECU (standard Mode 01 PID)
 *  - [Source.CALCULATED] computed by this app from ECU values (e.g. boost = MAP - baro)
 *  - [Source.ENHANCED]   manufacturer-specific request defined in a vehicle profile
 *  - [Source.GPS]        from the phone
 * The UI always labels anything that is not [Source.ECU] so estimates are never shown as
 * measured ECU parameters.
 */
class Channel(
    val key: String,
    val name: String,
    val shortName: String,
    val unit: String,
    val min: Double,
    val max: Double,
    val decimals: Int,
    val category: Category,
    val pid: Int,
    val source: Source = Source.ECU,
    val description: String = "",
    internal val extract: (IntArray) -> Double?,
) {
    enum class Source(val badge: String) { ECU("ECU"), CALCULATED("calc"), ENHANCED("enh"), GPS("GPS") }

    fun decode(data: IntArray): Double? = try { extract(data) } catch (e: IndexOutOfBoundsException) { null }

    fun format(v: Double?): String = if (v == null || v.isNaN()) "--" else String.format("%.${decimals}f", v)

    override fun toString() = key
}

/** A Mode 01 PID: how many data bytes it returns and the channels decoded from them. */
class PidSpec(
    val pid: Int,
    val name: String,
    val bytes: Int,
    val channels: List<Channel>,
    val text: ((IntArray) -> String)? = null,
)

private fun A(d: IntArray) = d[0].toDouble()
private fun B(d: IntArray) = d[1].toDouble()
private fun AB(d: IntArray) = (d[0] * 256 + d[1]).toDouble()
private fun CD(d: IntArray) = (d[2] * 256 + d[3]).toDouble()
private fun pct(v: Double) = v * 100.0 / 255.0
private fun trim(v: Double) = (v - 128.0) * 100.0 / 128.0

object Pids {
    private val specs = LinkedHashMap<Int, PidSpec>()
    private val channelMap = LinkedHashMap<String, Channel>()

    val all: Collection<PidSpec> get() = specs.values
    val channels: Collection<Channel> get() = channelMap.values

    operator fun get(pid: Int): PidSpec? = specs[pid]
    fun channel(key: String): Channel? = channelMap[key]

    /** Bit-mask PIDs used for discovery (00, 20, 40 ... E0). */
    val supportPids = listOf(0x00, 0x20, 0x40, 0x60, 0x80, 0xA0, 0xC0, 0xE0)

    private fun ch(
        key: String, name: String, short: String, unit: String, min: Double, max: Double, dec: Int,
        cat: Category, pid: Int, desc: String = "", f: (IntArray) -> Double?,
    ) = Channel(key, name, short, unit, min, max, dec, cat, pid, Channel.Source.ECU, desc, f)

    private fun add(pid: Int, name: String, bytes: Int, vararg chans: Channel, text: ((IntArray) -> String)? = null) {
        val spec = PidSpec(pid, name, bytes, chans.toList(), text)
        specs[pid] = spec
        chans.forEach { channelMap[it.key] = it }
    }

    private fun addDerived(c: Channel) { channelMap[c.key] = c }

    init {
        add(0x01, "Monitor status since DTCs cleared", 4, text = { Readiness.describe(it) })
        add(0x02, "DTC that caused freeze frame", 2, text = { app.obdforge.core.dtc.Dtc.decode(it[0], it[1]) ?: "None" })
        add(0x03, "Fuel system status", 2, ch("fuel_status_code", "Fuel system 1 status code", "Fuel sys", "", 0.0, 16.0, 0, Category.FUEL, 0x03) { A(it) },
            text = { "System 1: ${fuelStatus(it[0])}" + if (it.size > 1 && it[1] != 0) ", System 2: ${fuelStatus(it[1])}" else "" })
        add(0x04, "Calculated engine load", 1, ch("load", "Calculated engine load", "Load", "%", 0.0, 100.0, 1, Category.ENGINE, 0x04) { pct(A(it)) })
        add(0x05, "Engine coolant temperature", 1, ch("ect", "Coolant temperature", "Coolant", "°C", -40.0, 140.0, 0, Category.COOLING, 0x05) { A(it) - 40 })
        add(0x06, "Short term fuel trim—Bank 1", 1, ch("stft1", "Short term fuel trim B1", "STFT B1", "%", -25.0, 25.0, 1, Category.FUEL, 0x06) { trim(A(it)) })
        add(0x07, "Long term fuel trim—Bank 1", 1, ch("ltft1", "Long term fuel trim B1", "LTFT B1", "%", -25.0, 25.0, 1, Category.FUEL, 0x07) { trim(A(it)) })
        add(0x08, "Short term fuel trim—Bank 2", 1, ch("stft2", "Short term fuel trim B2", "STFT B2", "%", -25.0, 25.0, 1, Category.FUEL, 0x08) { trim(A(it)) })
        add(0x09, "Long term fuel trim—Bank 2", 1, ch("ltft2", "Long term fuel trim B2", "LTFT B2", "%", -25.0, 25.0, 1, Category.FUEL, 0x09) { trim(A(it)) })
        add(0x0A, "Fuel pressure (gauge)", 1, ch("fuel_pressure", "Fuel pressure", "Fuel P", "kPa", 0.0, 765.0, 0, Category.FUEL, 0x0A) { A(it) * 3 })
        add(0x0B, "Intake manifold absolute pressure", 1, ch("map", "Intake manifold pressure (MAP)", "MAP", "kPa", 0.0, 255.0, 0, Category.AIR, 0x0B) { A(it) })
        add(0x0C, "Engine speed", 2, ch("rpm", "Engine speed", "RPM", "rpm", 0.0, 8000.0, 0, Category.ENGINE, 0x0C) { AB(it) / 4 })
        add(0x0D, "Vehicle speed", 1, ch("speed", "Vehicle speed", "Speed", "km/h", 0.0, 255.0, 0, Category.VEHICLE, 0x0D) { A(it) })
        add(0x0E, "Timing advance", 1, ch("timing", "Ignition timing advance (cyl 1)", "Timing", "°", -64.0, 63.5, 1, Category.ENGINE, 0x0E) { A(it) / 2 - 64 })
        add(0x0F, "Intake air temperature", 1, ch("iat", "Intake air temperature", "IAT", "°C", -40.0, 120.0, 0, Category.AIR, 0x0F) { A(it) - 40 })
        add(0x10, "Mass air flow rate", 2, ch("maf", "Mass air flow (MAF)", "MAF", "g/s", 0.0, 300.0, 2, Category.AIR, 0x10) { AB(it) / 100 })
        add(0x11, "Throttle position", 1, ch("tps", "Throttle position", "Throttle", "%", 0.0, 100.0, 1, Category.AIR, 0x11) { pct(A(it)) })
        add(0x12, "Commanded secondary air status", 1, text = {
            when (it[0]) { 1 -> "Upstream"; 2 -> "Downstream of catalyst"; 4 -> "From outside atmosphere or off"; 8 -> "Pump commanded on for diagnostics"; else -> "Code ${it[0]}" }
        })
        add(0x13, "Oxygen sensors present (2 banks)", 1, text = { o2Layout(it[0], false) })
        for (i in 0 until 8) {
            val bank = i / 4 + 1; val sensor = i % 4 + 1
            add(0x14 + i, "Oxygen sensor B${bank}S$sensor (narrowband)", 2,
                ch("o2_b${bank}s${sensor}_v", "O2 sensor B${bank}S$sensor voltage", "O2 B${bank}S$sensor", "V", 0.0, 1.275, 3, Category.OXYGEN, 0x14 + i) { A(it) / 200 },
                ch("o2_b${bank}s${sensor}_trim", "O2 B${bank}S$sensor short term trim", "O2 trim B${bank}S$sensor", "%", -100.0, 99.2, 1, Category.OXYGEN, 0x14 + i) { if (it[1] == 0xFF) null else trim(B(it)) })
        }
        add(0x1C, "OBD standards this vehicle conforms to", 1, text = { obdStandard(it[0]) })
        add(0x1D, "Oxygen sensors present (4 banks)", 1, text = { o2Layout(it[0], true) })
        add(0x1E, "Auxiliary input status", 1, text = { if (it[0] and 1 == 1) "Power take-off active" else "Power take-off inactive" })
        add(0x1F, "Run time since engine start", 2, ch("runtime", "Run time since engine start", "Run time", "s", 0.0, 65535.0, 0, Category.ENGINE, 0x1F) { AB(it) })
        add(0x21, "Distance traveled with MIL on", 2, ch("dist_mil", "Distance with MIL on", "MIL dist", "km", 0.0, 65535.0, 0, Category.STATUS, 0x21) { AB(it) })
        add(0x22, "Fuel rail pressure (relative to manifold vacuum)", 2, ch("frp_rel", "Fuel rail pressure (rel. vacuum)", "FRP rel", "kPa", 0.0, 5177.0, 1, Category.FUEL, 0x22) { AB(it) * 0.079 })
        add(0x23, "Fuel rail gauge pressure (diesel, or gasoline direct injection)", 2, ch("frp", "Fuel rail pressure", "Rail P", "kPa", 0.0, 655350.0, 0, Category.FUEL, 0x23) { AB(it) * 10 })
        for (i in 0 until 8) {
            val n = i + 1
            add(0x24 + i, "Oxygen sensor $n (wide range, voltage)", 4,
                ch("o2w${n}_lambda", "O2 sensor $n equivalence ratio (λ)", "λ S$n", "λ", 0.0, 2.0, 3, Category.OXYGEN, 0x24 + i) { AB(it) * 2 / 65536 },
                ch("o2w${n}_v", "O2 sensor $n voltage", "O2 S$n V", "V", 0.0, 8.0, 3, Category.OXYGEN, 0x24 + i) { CD(it) * 8 / 65536 })
        }
        add(0x2C, "Commanded EGR", 1, ch("egr_cmd", "Commanded EGR", "EGR cmd", "%", 0.0, 100.0, 1, Category.EMISSIONS, 0x2C) { pct(A(it)) })
        add(0x2D, "EGR error", 1, ch("egr_err", "EGR error", "EGR err", "%", -100.0, 99.2, 1, Category.EMISSIONS, 0x2D) { trim(A(it)) })
        add(0x2E, "Commanded evaporative purge", 1, ch("evap_purge", "Commanded EVAP purge", "Purge", "%", 0.0, 100.0, 1, Category.EMISSIONS, 0x2E) { pct(A(it)) })
        add(0x2F, "Fuel tank level input", 1, ch("fuel_level", "Fuel tank level", "Fuel", "%", 0.0, 100.0, 1, Category.FUEL, 0x2F) { pct(A(it)) })
        add(0x30, "Warm-ups since codes cleared", 1, ch("warmups", "Warm-ups since codes cleared", "Warm-ups", "", 0.0, 255.0, 0, Category.STATUS, 0x30) { A(it) })
        add(0x31, "Distance traveled since codes cleared", 2, ch("dist_clr", "Distance since codes cleared", "Dist clr", "km", 0.0, 65535.0, 0, Category.STATUS, 0x31) { AB(it) })
        add(0x32, "Evap system vapor pressure", 2, ch("evap_vp", "EVAP vapor pressure", "EVAP P", "Pa", -8192.0, 8191.75, 2, Category.EMISSIONS, 0x32) {
            val raw = it[0] * 256 + it[1]; (if (raw >= 32768) raw - 65536 else raw) / 4.0 })
        add(0x33, "Absolute barometric pressure", 1, ch("baro", "Barometric pressure", "Baro", "kPa", 0.0, 255.0, 0, Category.AIR, 0x33) { A(it) })
        for (i in 0 until 8) {
            val n = i + 1
            add(0x34 + i, "Oxygen sensor $n (wide range, current)", 4,
                ch("o2c${n}_lambda", "O2 sensor $n equivalence ratio (λ)", "λ S$n", "λ", 0.0, 2.0, 3, Category.OXYGEN, 0x34 + i) { AB(it) * 2 / 65536 },
                ch("o2c${n}_ma", "O2 sensor $n current", "O2 S$n mA", "mA", -128.0, 128.0, 2, Category.OXYGEN, 0x34 + i) { CD(it) / 256 - 128 })
        }
        add(0x3C, "Catalyst temperature B1S1", 2, ch("cat_b1s1", "Catalyst temperature B1S1", "Cat B1S1", "°C", -40.0, 1000.0, 0, Category.EMISSIONS, 0x3C) { AB(it) / 10 - 40 })
        add(0x3D, "Catalyst temperature B2S1", 2, ch("cat_b2s1", "Catalyst temperature B2S1", "Cat B2S1", "°C", -40.0, 1000.0, 0, Category.EMISSIONS, 0x3D) { AB(it) / 10 - 40 })
        add(0x3E, "Catalyst temperature B1S2", 2, ch("cat_b1s2", "Catalyst temperature B1S2", "Cat B1S2", "°C", -40.0, 1000.0, 0, Category.EMISSIONS, 0x3E) { AB(it) / 10 - 40 })
        add(0x3F, "Catalyst temperature B2S2", 2, ch("cat_b2s2", "Catalyst temperature B2S2", "Cat B2S2", "°C", -40.0, 1000.0, 0, Category.EMISSIONS, 0x3F) { AB(it) / 10 - 40 })
        add(0x41, "Monitor status this drive cycle", 4, text = { Readiness.describe(it) })
        add(0x42, "Control module voltage", 2, ch("ecu_voltage", "Control module voltage", "ECU V", "V", 0.0, 16.0, 2, Category.ELECTRICAL, 0x42) { AB(it) / 1000 })
        add(0x43, "Absolute load value", 2, ch("abs_load", "Absolute load", "Abs load", "%", 0.0, 400.0, 1, Category.ENGINE, 0x43) { AB(it) * 100 / 255 })
        add(0x44, "Commanded air-fuel equivalence ratio", 2, ch("lambda_cmd", "Commanded equivalence ratio (λ)", "λ cmd", "λ", 0.0, 2.0, 3, Category.FUEL, 0x44) { AB(it) * 2 / 65536 })
        add(0x45, "Relative throttle position", 1, ch("tps_rel", "Relative throttle position", "Rel TPS", "%", 0.0, 100.0, 1, Category.AIR, 0x45) { pct(A(it)) })
        add(0x46, "Ambient air temperature", 1, ch("ambient", "Ambient air temperature", "Ambient", "°C", -40.0, 60.0, 0, Category.VEHICLE, 0x46) { A(it) - 40 })
        add(0x47, "Absolute throttle position B", 1, ch("tps_b", "Absolute throttle position B", "TPS B", "%", 0.0, 100.0, 1, Category.AIR, 0x47) { pct(A(it)) })
        add(0x48, "Absolute throttle position C", 1, ch("tps_c", "Absolute throttle position C", "TPS C", "%", 0.0, 100.0, 1, Category.AIR, 0x48) { pct(A(it)) })
        add(0x49, "Accelerator pedal position D", 1, ch("app_d", "Accelerator pedal position D", "Pedal D", "%", 0.0, 100.0, 1, Category.AIR, 0x49) { pct(A(it)) })
        add(0x4A, "Accelerator pedal position E", 1, ch("app_e", "Accelerator pedal position E", "Pedal E", "%", 0.0, 100.0, 1, Category.AIR, 0x4A) { pct(A(it)) })
        add(0x4B, "Accelerator pedal position F", 1, ch("app_f", "Accelerator pedal position F", "Pedal F", "%", 0.0, 100.0, 1, Category.AIR, 0x4B) { pct(A(it)) })
        add(0x4C, "Commanded throttle actuator", 1, ch("tac_cmd", "Commanded throttle actuator", "TAC cmd", "%", 0.0, 100.0, 1, Category.AIR, 0x4C) { pct(A(it)) })
        add(0x4D, "Time run with MIL on", 2, ch("time_mil", "Time run with MIL on", "MIL time", "min", 0.0, 65535.0, 0, Category.STATUS, 0x4D) { AB(it) })
        add(0x4E, "Time since trouble codes cleared", 2, ch("time_clr", "Time since codes cleared", "Clr time", "min", 0.0, 65535.0, 0, Category.STATUS, 0x4E) { AB(it) })
        add(0x50, "Maximum value for air flow rate from MAF", 4, ch("maf_max", "MAF sensor maximum", "MAF max", "g/s", 0.0, 2550.0, 0, Category.AIR, 0x50) { A(it) * 10 })
        add(0x51, "Fuel type", 1, text = { fuelType(it[0]) })
        add(0x52, "Ethanol fuel %", 1, ch("ethanol", "Ethanol fuel %", "Ethanol", "%", 0.0, 100.0, 1, Category.FUEL, 0x52) { pct(A(it)) })
        add(0x53, "Absolute evap system vapor pressure", 2, ch("evap_abs", "EVAP absolute vapor pressure", "EVAP abs", "kPa", 0.0, 327.675, 3, Category.EMISSIONS, 0x53) { AB(it) / 200 })
        add(0x54, "Evap system vapor pressure (wide)", 2, ch("evap_vp2", "EVAP vapor pressure (wide)", "EVAP P2", "Pa", -32767.0, 32768.0, 0, Category.EMISSIONS, 0x54) {
            val raw = it[0] * 256 + it[1]; (if (raw >= 32768) raw - 65536 else raw).toDouble() })
        add(0x55, "Short term secondary O2 trim B1/B3", 2, ch("stso2_b1", "Short term secondary O2 trim B1", "S-O2 ST B1", "%", -100.0, 99.2, 1, Category.OXYGEN, 0x55) { trim(A(it)) })
        add(0x56, "Long term secondary O2 trim B1/B3", 2, ch("ltso2_b1", "Long term secondary O2 trim B1", "S-O2 LT B1", "%", -100.0, 99.2, 1, Category.OXYGEN, 0x56) { trim(A(it)) })
        add(0x57, "Short term secondary O2 trim B2/B4", 2, ch("stso2_b2", "Short term secondary O2 trim B2", "S-O2 ST B2", "%", -100.0, 99.2, 1, Category.OXYGEN, 0x57) { trim(A(it)) })
        add(0x58, "Long term secondary O2 trim B2/B4", 2, ch("ltso2_b2", "Long term secondary O2 trim B2", "S-O2 LT B2", "%", -100.0, 99.2, 1, Category.OXYGEN, 0x58) { trim(A(it)) })
        add(0x59, "Fuel rail absolute pressure", 2, ch("frp_abs", "Fuel rail absolute pressure", "Rail abs", "kPa", 0.0, 655350.0, 0, Category.FUEL, 0x59) { AB(it) * 10 })
        add(0x5A, "Relative accelerator pedal position", 1, ch("app_rel", "Relative accelerator pedal position", "Pedal rel", "%", 0.0, 100.0, 1, Category.AIR, 0x5A) { pct(A(it)) })
        add(0x5B, "Hybrid battery pack remaining life", 1, ch("hv_soc", "Hybrid battery remaining", "HV batt", "%", 0.0, 100.0, 1, Category.ELECTRICAL, 0x5B) { pct(A(it)) })
        add(0x5C, "Engine oil temperature", 1, ch("oil_temp", "Engine oil temperature", "Oil T", "°C", -40.0, 210.0, 0, Category.COOLING, 0x5C) { A(it) - 40 })
        add(0x5D, "Fuel injection timing", 2, ch("inj_timing", "Fuel injection timing", "Inj timing", "°", -210.0, 302.0, 2, Category.FUEL, 0x5D) { AB(it) / 128 - 210 })
        add(0x5E, "Engine fuel rate", 2, ch("fuel_rate", "Engine fuel rate (ECU)", "Fuel rate", "L/h", 0.0, 3276.75, 2, Category.FUEL, 0x5E) { AB(it) / 20 })
        add(0x5F, "Emission requirements", 1, text = { "Code 0x" + it[0].toString(16).uppercase() })
        add(0x61, "Driver's demand engine percent torque", 1, ch("torque_demand", "Driver demand torque", "Tq demand", "%", -125.0, 130.0, 0, Category.ENGINE, 0x61) { A(it) - 125 })
        add(0x62, "Actual engine percent torque", 1, ch("torque_actual", "Actual engine torque", "Tq actual", "%", -125.0, 130.0, 0, Category.ENGINE, 0x62) { A(it) - 125 })
        add(0x63, "Engine reference torque", 2, ch("torque_ref", "Engine reference torque", "Ref torque", "Nm", 0.0, 65535.0, 0, Category.ENGINE, 0x63) { AB(it) })
        add(0x66, "Mass air flow sensor (A/B)", 5,
            ch("maf_a", "MAF sensor A", "MAF A", "g/s", 0.0, 2048.0, 2, Category.AIR, 0x66) { if (it[0] and 1 == 0) null else (it[1] * 256 + it[2]) / 32.0 },
            ch("maf_b", "MAF sensor B", "MAF B", "g/s", 0.0, 2048.0, 2, Category.AIR, 0x66) { if (it[0] and 2 == 0) null else (it[3] * 256 + it[4]) / 32.0 })
        add(0x67, "Engine coolant temperature (sensors 1/2)", 3,
            ch("ect_1", "Coolant temperature sensor 1", "ECT 1", "°C", -40.0, 215.0, 0, Category.COOLING, 0x67) { if (it[0] and 1 == 0) null else it[1] - 40.0 },
            ch("ect_2", "Coolant temperature sensor 2", "ECT 2", "°C", -40.0, 215.0, 0, Category.COOLING, 0x67) { if (it[0] and 2 == 0) null else it[2] - 40.0 })
        add(0x68, "Intake air temperature (sensors 1/2)", 3,
            ch("iat_1", "Intake air temperature sensor 1", "IAT 1", "°C", -40.0, 215.0, 0, Category.AIR, 0x68) { if (it[0] and 1 == 0) null else it[1] - 40.0 },
            ch("iat_2", "Intake air temperature sensor 2", "IAT 2", "°C", -40.0, 215.0, 0, Category.AIR, 0x68) { if (it[0] and 2 == 0) null else it[2] - 40.0 })
        add(0x70, "Boost pressure control", 10,
            ch("boost_cmd_a", "Commanded boost pressure A (absolute)", "Boost cmd", "kPa", 0.0, 400.0, 1, Category.TURBO, 0x70) { if (it[0] and 1 == 0) null else (it[1] * 256 + it[2]) * 0.03125 },
            ch("boost_act_a", "Actual boost pressure A (absolute)", "Boost act", "kPa", 0.0, 400.0, 1, Category.TURBO, 0x70) { if (it[0] and 2 == 0) null else (it[3] * 256 + it[4]) * 0.03125 })
        add(0xA6, "Odometer", 4, ch("odometer", "Odometer", "Odo", "km", 0.0, 429496729.5, 1, Category.VEHICLE, 0xA6) {
            ((it[0].toLong() shl 24) or (it[1].toLong() shl 16) or (it[2].toLong() shl 8) or it[3].toLong()) / 10.0 })

        // ---- Values this app calculates from ECU data. Always labelled "calc" in the UI. ----
        addDerived(Channel("boost", "Boost (MAP − barometric)", "Boost", "kPa", -100.0, 250.0, 1, Category.TURBO, -1, Channel.Source.CALCULATED,
            "Calculated: intake manifold pressure minus barometric pressure. Negative = vacuum.") { null })
        addDerived(Channel("trim_total1", "Total fuel trim B1 (STFT+LTFT)", "Total trim B1", "%", -50.0, 50.0, 1, Category.CALCULATED, -1, Channel.Source.CALCULATED,
            "Calculated: short term + long term fuel trim, bank 1.") { null })
        addDerived(Channel("trim_total2", "Total fuel trim B2 (STFT+LTFT)", "Total trim B2", "%", -50.0, 50.0, 1, Category.CALCULATED, -1, Channel.Source.CALCULATED,
            "Calculated: short term + long term fuel trim, bank 2.") { null })
        addDerived(Channel("afr_cmd", "Commanded AFR (gasoline)", "AFR cmd", ":1", 5.0, 30.0, 2, Category.CALCULATED, -1, Channel.Source.CALCULATED,
            "Calculated: commanded λ × 14.7 (stoichiometric gasoline). Not a measured value.") { null })
        addDerived(Channel("fuel_rate_est", "Fuel rate (estimated from MAF)", "Fuel est", "L/h", 0.0, 100.0, 2, Category.CALCULATED, -1, Channel.Source.CALCULATED,
            "Estimated: MAF ÷ (AFR × fuel density). Assumes λ=1 (or commanded λ) and the configured fuel; not measured.") { null })
        addDerived(Channel("economy_l100", "Fuel economy (estimated)", "L/100km", "L/100km", 0.0, 50.0, 1, Category.CALCULATED, -1, Channel.Source.CALCULATED,
            "Estimated from fuel rate and vehicle speed.") { null })
        addDerived(Channel("economy_kml", "Fuel economy (estimated)", "km/L", "km/L", 0.0, 50.0, 1, Category.CALCULATED, -1, Channel.Source.CALCULATED,
            "Estimated from fuel rate and vehicle speed.") { null })
        addDerived(Channel("vol_eff", "Volumetric efficiency (estimated)", "VE", "%", 0.0, 150.0, 1, Category.CALCULATED, -1, Channel.Source.CALCULATED,
            "Estimated: measured MAF compared with the theoretical airflow for the engine displacement, MAP and IAT.") { null })
        for (c in 1..8) addDerived(Channel("misfire_c$c", "Misfire count cylinder $c (Mode 06)", "Misfire C$c", "count", 0.0, 1000.0, 0, Category.ENGINE, -1, Channel.Source.ECU,
            "Read from the ECU's Mode 06 misfire monitor (MID A${c + 1}); only on ECUs that report it.") { null })
        addDerived(Channel("misfire_total", "Misfire count all cylinders (Mode 06)", "Misfires", "count", 0.0, 1000.0, 0, Category.ENGINE, -1, Channel.Source.ECU,
            "Sum of the Mode 06 per-cylinder misfire counts.") { null })
        addDerived(Channel("rpm_per_kmh", "Engine RPM per km/h (overall ratio)", "RPM/kmh", "rpm/(km/h)", 0.0, 200.0, 1, Category.TRANSMISSION, -1, Channel.Source.CALCULATED,
            "Calculated: RPM ÷ vehicle speed. Tracks the overall gearing; on CVTs it shows the ratio changing.") { null })
        addDerived(Channel("gps_speed", "GPS speed", "GPS spd", "km/h", 0.0, 300.0, 0, Category.VEHICLE, -1, Channel.Source.GPS, "Phone GPS speed.") { null })
        addDerived(Channel("adapter_voltage", "Adapter voltage (OBD pin 16)", "Batt V", "V", 0.0, 16.0, 2, Category.ELECTRICAL, -1, Channel.Source.CALCULATED,
            "Read by the adapter (ATRV) at the OBD port, not by an ECU.") { null })
    }

    fun fuelStatus(v: Int): String = when (v) {
        0 -> "Not present"
        1 -> "Open loop (insufficient engine temperature)"
        2 -> "Closed loop (using O2 sensor feedback)"
        4 -> "Open loop (engine load or fuel cut)"
        8 -> "Open loop (system failure)"
        16 -> "Closed loop with a feedback fault"
        else -> "Unknown ($v)"
    }

    fun o2Layout(v: Int, fourBanks: Boolean): String {
        val names = if (fourBanks) listOf("B1S1", "B1S2", "B2S1", "B2S2", "B3S1", "B3S2", "B4S1", "B4S2")
        else listOf("B1S1", "B1S2", "B1S3", "B1S4", "B2S1", "B2S2", "B2S3", "B2S4")
        val present = names.filterIndexed { i, _ -> v and (1 shl i) != 0 }
        return if (present.isEmpty()) "None" else present.joinToString(", ")
    }

    fun obdStandard(v: Int): String = when (v) {
        1 -> "OBD-II (CARB)"; 2 -> "OBD (EPA)"; 3 -> "OBD and OBD-II"; 4 -> "OBD-I"; 5 -> "Not OBD compliant"
        6 -> "EOBD (Europe)"; 7 -> "EOBD and OBD-II"; 8 -> "EOBD and OBD"; 9 -> "EOBD, OBD and OBD-II"
        10 -> "JOBD (Japan)"; 11 -> "JOBD and OBD-II"; 12 -> "JOBD and EOBD"; 13 -> "JOBD, EOBD and OBD-II"
        17 -> "EMD"; 18 -> "EMD+"; 19 -> "HD OBD-C"; 20 -> "HD OBD"; 21 -> "WWH OBD"; 23 -> "HD EOBD-I"
        25 -> "HD EOBD-I N"; 26 -> "HD EOBD-II"; 28 -> "HD EOBD-II N"; 30 -> "OBDBr-1"; 31 -> "OBDBr-2"
        32 -> "KOBD (Korea)"; 33 -> "IOBD I (India)"; 34 -> "IOBD II (India)"; 35 -> "HD EOBD-IV"
        else -> "Code $v"
    }

    fun fuelType(v: Int): String = when (v) {
        0 -> "Not available"; 1 -> "Gasoline"; 2 -> "Methanol"; 3 -> "Ethanol"; 4 -> "Diesel"; 5 -> "LPG"; 6 -> "CNG"
        7 -> "Propane"; 8 -> "Electric"; 9 -> "Bifuel gasoline"; 10 -> "Bifuel methanol"; 11 -> "Bifuel ethanol"
        12 -> "Bifuel LPG"; 13 -> "Bifuel CNG"; 14 -> "Bifuel propane"; 15 -> "Bifuel electricity"
        16 -> "Bifuel electric/combustion"; 17 -> "Hybrid gasoline"; 18 -> "Hybrid ethanol"; 19 -> "Hybrid diesel"
        20 -> "Hybrid electric"; 21 -> "Hybrid electric/combustion"; 22 -> "Hybrid regenerative"; 23 -> "Bifuel diesel"
        else -> "Code $v"
    }

    /** Decodes a support bitmap response (4 bytes) for base PID [base] into the PIDs it marks. */
    fun decodeSupport(base: Int, data: IntArray): Set<Int> {
        val out = HashSet<Int>()
        for (byteIdx in 0 until minOf(4, data.size)) {
            for (bit in 0 until 8) {
                if (data[byteIdx] and (0x80 shr bit) != 0) out.add(base + byteIdx * 8 + bit + 1)
            }
        }
        return out
    }
}
