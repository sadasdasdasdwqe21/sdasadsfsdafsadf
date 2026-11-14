package app.obdforge.core.analysis

import app.obdforge.core.live.FuelKind
import app.obdforge.core.live.VehicleSpec
import app.obdforge.core.pid.Mode06
import kotlin.math.abs

enum class Level(val label: String) { OK("Normal"), INFO("Info"), WARN("Check"), ALERT("Problem") }

data class Finding(
    val section: String,
    val title: String,
    val detail: String,
    val level: Level,
    /** Numbers behind the finding, e.g. "Idle LTFT B1" -> -24.6 */
    val evidence: Map<String, Double> = emptyMap(),
    /** True when the finding rests on an estimate rather than a measured ECU value. */
    val estimated: Boolean = false,
)

/** Fuel trim averages for one operating condition. */
data class TrimBin(val condition: String, val samples: Int, val stft: Double, val ltft: Double) {
    val total get() = stft + ltft
}

data class TrimAnalysis(val bank1: List<TrimBin>, val bank2: List<TrimBin>, val findings: List<Finding>)

/**
 * Engine analysis from logged series. Every conclusion is phrased as what the data shows and
 * what it may point to; nothing here declares a mechanical fault as confirmed.
 */
class EngineAnalysis(private val data: Map<String, Series>, private val spec: VehicleSpec = VehicleSpec()) {

    private fun s(k: String) = data[k]?.takeIf { !it.isEmpty() }

    /** Operating condition at time t from rpm/speed/throttle/load. */
    private fun condition(t: Long): String? {
        val rpm = s("rpm")?.at(t) ?: return null
        val speed = s("speed")?.at(t) ?: 0.0
        val tps = s("tps")?.at(t) ?: s("tps_rel")?.at(t)
        val load = s("load")?.at(t)
        return when {
            rpm < 300 -> null
            speed < 3 && rpm < 1100 -> "Idle"
            speed < 3 && rpm in 2200.0..3200.0 -> "2500 rpm no load"
            load != null && load > 70 -> "High load"
            speed >= 30 && (tps == null || tps < 40) && (load == null || load < 65) -> "Cruise"
            else -> null
        }
    }

    fun fuelTrims(): TrimAnalysis {
        val findings = ArrayList<Finding>()
        val b1 = bins(s("stft1"), s("ltft1"))
        val b2 = bins(s("stft2"), s("ltft2"))
        val sec = "Fuel trims"
        if (b1.isEmpty()) {
            findings.add(Finding(sec, "No fuel trim data", "Log STFT and LTFT (bank 1) with RPM and speed to analyse trims.", Level.INFO))
            return TrimAnalysis(b1, b2, findings)
        }
        for ((bank, bins) in listOf(1 to b1, 2 to b2)) {
            if (bins.isEmpty()) continue
            for (b in bins) {
                val lvl = when { abs(b.total) >= 20 -> Level.ALERT; abs(b.total) >= 10 -> Level.WARN; else -> Level.OK }
                val dir = if (b.total > 0) "adding fuel (the engine would run lean without correction)" else "removing fuel (the engine would run rich without correction)"
                findings.add(Finding(sec, "Bank $bank ${b.condition}: total trim ${fmt(b.total)} %",
                    if (lvl == Level.OK) "Within the usual ±10 % band." else "The ECU is $dir. STFT ${fmt(b.stft)} %, LTFT ${fmt(b.ltft)} % over ${b.samples} samples.",
                    lvl, mapOf("STFT" to b.stft, "LTFT" to b.ltft, "Total" to b.total)))
            }
            val idle = bins.firstOrNull { it.condition == "Idle" }
            val cruise = bins.firstOrNull { it.condition == "Cruise" } ?: bins.firstOrNull { it.condition == "2500 rpm no load" }
            val high = bins.firstOrNull { it.condition == "High load" }
            if (idle != null && cruise != null) {
                val diff = idle.total - cruise.total
                if (idle.total > 10 && diff > 8) findings.add(Finding(sec, "Bank $bank lean at idle, better at higher airflow",
                    "Total trim ${fmt(idle.total)} % at idle vs ${fmt(cruise.total)} % at cruise. A fixed amount of unmetered air matters most when airflow is low, so this pattern fits a vacuum/intake leak after the MAF. Confirm with a smoke test before replacing anything.",
                    Level.WARN, mapOf("Idle total" to idle.total, "Cruise total" to cruise.total)))
                if (cruise.total > 10 && cruise.total - idle.total > 5) findings.add(Finding(sec, "Bank $bank leaner as load increases",
                    "Trims rise from ${fmt(idle.total)} % (idle) to ${fmt(cruise.total)} % (cruise). This pattern fits fuel delivery falling behind (pressure/pump/filter) or a MAF under-reading at higher flow. Check fuel pressure under load.",
                    Level.WARN, mapOf("Idle total" to idle.total, "Cruise total" to cruise.total)))
                if (idle.total < -10 && cruise.total < -10) findings.add(Finding(sec, "Bank $bank rich across conditions",
                    "Negative trims at idle (${fmt(idle.total)} %) and cruise (${fmt(cruise.total)} %). Consistent rich correction fits extra fuel entering (leaking injector, high fuel pressure, purge valve passing vapour, fuel-diluted oil) or a MAF over-reading.",
                    Level.WARN, mapOf("Idle total" to idle.total, "Cruise total" to cruise.total)))
                if (idle.total < -10 && cruise.total > -6) findings.add(Finding(sec, "Bank $bank rich mainly at idle",
                    "Rich correction concentrated at idle (${fmt(idle.total)} %) fits a small constant fuel source such as a dripping injector or purge vapour, which matters most at low airflow.",
                    Level.WARN, mapOf("Idle total" to idle.total, "Cruise total" to cruise.total)))
            }
            if (high != null && high.samples > 3 && abs(high.stft) < 0.5 && abs(high.ltft) > 0)
                findings.add(Finding(sec, "Bank $bank in open loop at high load", "STFT stays near 0 at high load: the ECU is likely in open loop (enrichment), which is normal.", Level.INFO))
        }
        if (b2.isNotEmpty()) {
            for (c in b1.map { it.condition }) {
                val x = b1.first { it.condition == c }; val y = b2.firstOrNull { it.condition == c } ?: continue
                if (abs(x.total - y.total) > 10) findings.add(Finding(sec, "Banks differ at $c",
                    "Bank 1 ${fmt(x.total)} % vs bank 2 ${fmt(y.total)} %. A difference between banks points to something affecting one bank only (leak on one side, injector, exhaust leak before that bank's sensor) rather than a shared cause like the MAF or fuel pressure.",
                    Level.WARN, mapOf("B1" to x.total, "B2" to y.total)))
            }
        }
        // STFT activity: a healthy closed loop keeps STFT moving.
        s("stft1")?.let { st ->
            if (st.size > 30) {
                val sd = Stats.stdDev(st.values)
                if (sd < 0.3 && s("rpm") != null) findings.add(Finding(sec, "Short term trim not moving",
                    "STFT stayed almost constant (sd ${fmt(sd)} %). Either the engine was in open loop the whole time or the O2 feedback is not switching. Check the upstream O2 sensor graph and fuel system status.",
                    Level.INFO))
            }
        }
        return TrimAnalysis(b1, b2, findings)
    }

    private fun bins(st: Series?, lt: Series?): List<TrimBin> {
        if (st == null || lt == null) return emptyList()
        val acc = LinkedHashMap<String, MutableList<Pair<Double, Double>>>()
        for (i in 0 until st.size) {
            val t = st.times[i]
            val c = condition(t) ?: continue
            val l = lt.at(t) ?: continue
            acc.getOrPut(c) { ArrayList() }.add(st.values[i] to l)
        }
        val order = listOf("Idle", "2500 rpm no load", "Cruise", "High load")
        return order.mapNotNull { c ->
            val list = acc[c] ?: return@mapNotNull null
            if (list.size < 3) null else TrimBin(c, list.size, Stats.mean(list.map { it.first }), Stats.mean(list.map { it.second }))
        }
    }

    fun airflow(): List<Finding> {
        val sec = "Airflow"
        val out = ArrayList<Finding>()
        val maf = s("maf"); val rpm = s("rpm"); val map = s("map")
        if (maf == null && map == null) return listOf(Finding(sec, "No airflow data", "Log MAF and/or MAP with RPM.", Level.INFO))
        if (maf != null && rpm != null) {
            val idle = (0 until maf.size).filter { condition(maf.times[it]) == "Idle" }.map { maf.values[it] }
            if (idle.size >= 5) {
                val m = Stats.mean(idle); val perL = m / spec.displacementL
                out.add(Finding(sec, "Warm idle airflow ${fmt(m)} g/s (${fmt(perL)} g/s per litre)",
                    "A common rule of thumb for a warm petrol engine at idle is roughly 1–4 g/s per litre of displacement; check the service data for your engine. Much lower can mean a MAF under-reading or air entering after the MAF; much higher, a MAF over-reading or an extra idle load.",
                    if (perL < 0.8 || perL > 6.0) Level.WARN else Level.OK, mapOf("Idle MAF g/s" to m, "g/s per L" to perL)))
            }
            val ve = s("vol_eff")
            if (ve != null) {
                val wot = (0 until ve.size).filter { (s("tps")?.at(ve.times[it]) ?: 0.0) > 80 }.map { ve.values[it] }
                if (wot.size >= 3) out.add(Finding(sec, "Estimated volumetric efficiency at full throttle ${fmt(Stats.mean(wot))} %",
                    "Calculated from MAF, MAP, IAT, RPM and the configured ${spec.displacementL} L displacement. Naturally aspirated engines are typically 75–95 %. A value far below others in your logs suggests airflow not reaching the MAF reading (leak after the MAF) or a MAF error.",
                    Level.INFO, mapOf("VE %" to Stats.mean(wot)), estimated = true))
            }
        }
        if (map != null && rpm != null) {
            val idle = (0 until map.size).filter { condition(map.times[it]) == "Idle" }.map { map.values[it] }
            val baro = s("baro")?.let { Stats.mean(it.values) }
            if (idle.size >= 5 && baro != null) {
                val vac = baro - Stats.mean(idle)
                out.add(Finding(sec, "Idle manifold vacuum ${fmt(vac)} kPa (MAP ${fmt(Stats.mean(idle))} kPa)",
                    "A healthy warm engine usually pulls steady, strong vacuum at idle (often 55–75 kPa below barometric at sea level; less at altitude). Low or fluctuating vacuum can come from leaks, valve problems or retarded timing.",
                    if (vac < 40) Level.WARN else Level.OK, mapOf("Vacuum kPa" to vac)))
            }
        }
        s("iat")?.let { iat ->
            val amb = s("ambient")?.let { Stats.mean(it.values) }
            val st = Stats.describe(iat)
            if (amb != null && st.max - amb > 35) out.add(Finding(sec, "Intake air ${fmt(st.max - amb)} °C above ambient at peak",
                "Large intake temperature rise (heat soak, intercooler effectiveness) reduces power and can trigger timing retard.", Level.WARN,
                mapOf("Max IAT" to st.max, "Ambient" to amb)))
            else out.add(Finding(sec, "Intake air temperature ${fmt(st.min)}–${fmt(st.max)} °C", "", Level.OK))
        }
        throttleResponse()?.let { out.add(it) }
        if (maf != null && map != null) {
            Stats.correlation(maf, map)?.let { r ->
                out.add(Finding(sec, "MAF vs MAP correlation r = ${String.format("%.2f", r)}",
                    "Airflow and manifold pressure normally rise together (r close to 1 over a varied drive). Poor correlation can mean a sensor error or a leak.",
                    if (r < 0.6) Level.WARN else Level.OK))
            }
        }
        return out
    }

    /** Time from a throttle step (>40 %/s rise) to RPM or MAP responding. */
    private fun throttleResponse(): Finding? {
        val tps = s("tps") ?: return null
        val rpm = s("rpm") ?: return null
        val delays = ArrayList<Double>()
        for (i in 1 until tps.size) {
            val dt = (tps.times[i] - tps.times[i - 1]) / 1000.0
            if (dt <= 0 || dt > 1.0) continue
            if ((tps.values[i] - tps.values[i - 1]) / dt > 40 && tps.values[i] > 30) {
                val t0 = tps.times[i - 1]
                val r0 = rpm.at(t0) ?: continue
                val hit = (0 until rpm.size).firstOrNull { rpm.times[it] > t0 && rpm.values[it] > r0 + 150 && rpm.times[it] - t0 < 3000 }
                if (hit != null) delays.add((rpm.times[hit] - t0) / 1000.0)
            }
        }
        if (delays.isEmpty()) return null
        val m = Stats.mean(delays)
        return Finding("Airflow", "Throttle response ${String.format("%.2f", m)} s (${delays.size} events)",
            "Time from a quick throttle opening to RPM rising 150 rpm. Limited by the logging rate (roughly the time between samples).",
            if (m > 1.0) Level.WARN else Level.OK, mapOf("Response s" to m))
    }

    fun boost(): List<Finding> {
        val sec = "Boost"
        val boost = s("boost") ?: run {
            val map = s("map"); val baro = s("baro")
            if (map != null && baro != null) Series("boost", map.times, DoubleArray(map.size) { map.values[it] - (baro.at(map.times[it]) ?: baro.values[0]) }) else null
        } ?: return listOf(Finding(sec, "No boost data", "Log MAP and barometric pressure to analyse boost.", Level.INFO))
        val out = ArrayList<Finding>()
        val st = Stats.describe(boost)
        out.add(Finding(sec, "Peak boost ${fmt(st.max)} kPa (${fmt(st.max * 0.145038)} psi)",
            "Calculated as MAP minus barometric pressure.", Level.INFO, mapOf("Peak kPa" to st.max), estimated = true))
        val tps = s("tps") ?: s("tps_rel")
        val rpm = s("rpm")
        if (tps != null && rpm != null) {
            val wot = (0 until boost.size).filter { (tps.at(boost.times[it]) ?: 0.0) > 75 && (rpm.at(boost.times[it]) ?: 0.0) > 3000 }.map { boost.values[it] }
            if (wot.size >= 3) {
                val m = Stats.mean(wot)
                out.add(Finding(sec, "Average boost at full throttle above 3000 rpm: ${fmt(m)} kPa",
                    if (spec.turbocharged && m < 20) "Low for a turbocharged engine. Fits a boost leak, wastegate stuck open or control fault; compare with the specification for your engine." else "",
                    if (spec.turbocharged && m < 20) Level.WARN else Level.OK, mapOf("WOT boost" to m), estimated = true))
            }
            response(boost, tps)?.let { out.add(it) }
        }
        val over = Stats.durationBeyond(boost, spec.boostLimitKpa, true)
        if (over > 0) out.add(Finding(sec, "Boost above your ${fmt(spec.boostLimitKpa)} kPa limit for ${over / 1000.0} s",
            "Possible overboost. Check the wastegate and its control.", Level.ALERT, mapOf("Seconds over" to over / 1000.0)))
        val cmd = s("boost_cmd_a"); val act = s("boost_act_a")
        if (cmd != null && act != null) {
            val errs = (0 until act.size).mapNotNull { i -> cmd.at(act.times[i])?.let { act.values[i] - it } }
            if (errs.isNotEmpty()) {
                val worst = errs.minOrNull()!!
                out.add(Finding(sec, "Actual vs commanded boost: worst shortfall ${fmt(worst)} kPa",
                    "From ECU PID 70 (commanded and actual boost).", if (worst < -20) Level.WARN else Level.OK, mapOf("Worst shortfall" to worst)))
            }
        }
        return out
    }

    /** Time from throttle >75 % to reaching 90 % of the peak boost of that pull. */
    private fun response(boost: Series, tps: Series): Finding? {
        val times = ArrayList<Double>()
        var i = 1
        while (i < tps.size) {
            if (tps.values[i - 1] < 60 && tps.values[i] >= 75) {
                val t0 = tps.times[i]
                val window = boost.slice(t0, t0 + 8000)
                if (window.size > 3) {
                    val peak = window.values.max()
                    if (peak > 10) {
                        val hit = (0 until window.size).first { window.values[it] >= peak * 0.9 }
                        times.add((window.times[hit] - t0) / 1000.0)
                    }
                }
            }
            i++
        }
        if (times.isEmpty()) return null
        return Finding("Boost", "Boost response ${String.format("%.1f", Stats.mean(times))} s to 90 % of peak (${times.size} pulls)",
            "Depends on gear and starting RPM; compare pulls done the same way.", Level.INFO, mapOf("Response s" to Stats.mean(times)))
    }

    fun cooling(): List<Finding> {
        val sec = "Cooling"
        val ect = s("ect") ?: return listOf(Finding(sec, "No coolant data", "Log coolant temperature.", Level.INFO))
        val out = ArrayList<Finding>()
        val st = Stats.describe(ect)
        val startT = ect.values.first()
        if (startT < 40) {
            val hit = (0 until ect.size).firstOrNull { ect.values[it] >= 80 }
            if (hit != null) {
                val sec2 = (ect.times[hit] - ect.times[0]) / 1000.0
                out.add(Finding(sec, "Warm-up to 80 °C took ${fmt(sec2 / 60)} min (from ${fmt(startT)} °C)",
                    "Depends on ambient temperature and driving. Much longer than usual for your car suggests a thermostat opening early.",
                    if (sec2 > 20 * 60) Level.WARN else Level.OK, mapOf("Warm-up s" to sec2)))
            } else if ((ect.times.last() - ect.times.first()) > 15 * 60_000) {
                out.add(Finding(sec, "Coolant never reached 80 °C in ${fmt((ect.times.last() - ect.times.first()) / 60000.0)} min",
                    "Fits a thermostat stuck open (or a coolant sensor reading low). Compare with an IR thermometer reading.", Level.WARN))
            }
        }
        out.add(Finding(sec, "Coolant ${fmt(st.min)}–${fmt(st.max)} °C", "", if (st.max >= 110) Level.ALERT else if (st.max >= 104) Level.WARN else Level.OK,
            mapOf("Max °C" to st.max)))
        val hot = Stats.durationBeyond(ect, 105.0, true)
        if (hot > 0) out.add(Finding(sec, "Above 105 °C for ${hot / 1000} s", "Check coolant level, fan operation and thermostat.", Level.ALERT))
        val warm = (0 until ect.size).filter { ect.values[it] > 80 }.map { ect.values[it] }
        if (warm.size > 30) {
            val sd = Stats.stdDev(warm.toDoubleArray())
            if (sd > 4) out.add(Finding(sec, "Coolant temperature swings (sd ${fmt(sd)} °C once warm)",
                "Large swings after warm-up can come from a sticking thermostat, air in the system or fan cycling.", Level.WARN))
        }
        return out
    }

    fun fuelSystem(): List<Finding> {
        val sec = "Fuel system"
        val out = ArrayList<Finding>()
        for (k in listOf("frp", "frp_abs", "fuel_pressure", "frp_rel")) {
            val sr = s(k) ?: continue
            val st = Stats.describe(sr)
            out.add(Finding(sec, "${app.obdforge.core.pid.Pids.channel(k)?.name}: ${fmt(st.min)}–${fmt(st.max)} kPa (mean ${fmt(st.mean)})",
                "Compare against the specification for your engine at idle and under load.", Level.INFO, mapOf("Min" to st.min, "Max" to st.max)))
        }
        val speed = s("speed")
        val rate = s("fuel_rate") ?: s("fuel_rate_est")
        if (speed != null && rate != null && speed.size > 10) {
            var km = 0.0; var litres = 0.0
            for (i in 1 until speed.size) {
                val dt = (speed.times[i] - speed.times[i - 1]) / 3_600_000.0
                if (dt <= 0 || dt > 0.01) continue
                km += speed.values[i - 1] * dt
                litres += (rate.at(speed.times[i - 1]) ?: 0.0) * dt
            }
            if (km > 0.5) {
                val estimated = s("fuel_rate") == null
                out.add(Finding(sec, "Fuel used ${String.format("%.2f", litres)} L over ${String.format("%.1f", km)} km: ${fmt(litres / km * 100)} L/100 km (${fmt(km / litres)} km/L)",
                    if (estimated) "Estimated from MAF (${spec.fuel.label}, AFR ${spec.fuel.stoichAfr}). Accuracy depends on MAF accuracy and is typically within ±10–15 %." else "From the ECU fuel rate PID.",
                    Level.INFO, mapOf("L/100km" to litres / km * 100), estimated = estimated))
            }
        }
        s("lambda_cmd")?.let { l ->
            val rich = Stats.durationBeyond(l, 0.95, false)
            out.add(Finding(sec, "Commanded λ ${fmt(Stats.describe(l).min)}–${fmt(Stats.describe(l).max)}",
                "Commanded enrichment (λ < 1) for ${rich / 1000} s, usually at high load for component protection.", Level.INFO))
        }
        if (spec.fuel == FuelKind.DIESEL) out.add(Finding(sec, "Diesel", "MAF-based consumption estimate is disabled for diesel (no fixed AFR).", Level.INFO))
        if (out.isEmpty()) out.add(Finding(sec, "No fuel system data", "Log fuel rail pressure, fuel rate or MAF with speed.", Level.INFO))
        return out
    }

    fun misfires(mode06: List<Mode06.TestResult>, misfireSeries: Map<Int, Series> = emptyMap()): List<Finding> {
        val sec = "Misfire"
        val out = ArrayList<Finding>()
        val cyl = mode06.filter { Mode06.isMisfireCylinder(it.mid) }
        if (cyl.isNotEmpty()) {
            val byCyl = cyl.groupBy { it.mid - 0xA1 }.mapValues { e -> e.value.maxOf { it.value } }
            val total = byCyl.values.sum()
            for ((c, v) in byCyl.toSortedMap()) {
                val share = if (total > 0) v / total * 100 else 0.0
                out.add(Finding(sec, "Cylinder $c: ${fmt(v)} misfire counts (Mode 06)", if (share > 50 && v > 10) "This cylinder accounts for ${fmt(share)} % of misfires: look at its coil, plug and injector first." else "",
                    if (share > 50 && v > 10) Level.WARN else Level.OK, mapOf("Counts" to v)))
            }
        } else out.add(Finding(sec, "No per-cylinder misfire data", "This ECU did not report Mode 06 misfire monitors (MIDs A2–AD). Many vehicles do not.", Level.INFO))
        // Correlate counter increments with RPM/load when counters were logged over time.
        val rpm = s("rpm"); val load = s("load")
        for ((c, series) in misfireSeries) {
            if (rpm == null || series.size < 3) continue
            val events = (1 until series.size).filter { series.values[it] > series.values[it - 1] }
            if (events.isEmpty()) continue
            val rpms = events.mapNotNull { rpm.at(series.times[it]) }
            val loads = events.mapNotNull { load?.at(series.times[it]) }
            out.add(Finding(sec, "Cylinder $c misfires happened around ${fmt(Stats.mean(rpms))} rpm" + if (loads.isNotEmpty()) ", ${fmt(Stats.mean(loads))} % load" else "",
                "Misfires mainly at low RPM/idle often relate to vacuum leaks or injector balance; under high load, to ignition (coil/plug) breaking down.",
                Level.INFO))
        }
        s("rpm")?.let { r ->
            val idle = (0 until r.size).filter { condition(r.times[it]) == "Idle" }.map { r.values[it] }
            if (idle.size > 20) {
                val sd = Stats.stdDev(idle.toDoubleArray())
                out.add(Finding(sec, "Idle RPM stability: sd ${fmt(sd)} rpm", "Unsteady idle (sd above ~40 rpm) can come from misfire, vacuum leaks or idle control problems. Limited by the logging rate.",
                    if (sd > 40) Level.WARN else Level.OK, mapOf("Idle RPM sd" to sd)))
            }
        }
        return out
    }

    fun electrical(): List<Finding> {
        val sec = "Electrical"
        val v = s("ecu_voltage") ?: s("adapter_voltage") ?: return emptyList()
        val rpm = s("rpm")
        val running = (0 until v.size).filter { (rpm?.at(v.times[it]) ?: 1000.0) > 500 }.map { v.values[it] }
        if (running.isEmpty()) return emptyList()
        val m = Stats.mean(running); val mn = running.min()
        val lvl = when { m < 13.2 -> Level.WARN; m > 14.9 -> Level.WARN; mn < 12.0 -> Level.WARN; else -> Level.OK }
        return listOf(Finding(sec, "Running voltage ${String.format("%.2f", m)} V (min ${String.format("%.2f", mn)} V)",
            "With the engine running a healthy charging system usually holds about 13.5–14.7 V (some smart-charging cars vary more).", lvl, mapOf("Mean V" to m, "Min V" to mn)))
    }

    fun anomalies(): List<Finding> {
        val out = ArrayList<Finding>()
        val limits = mapOf("rpm" to 400.0, "speed" to 15.0, "ect" to 5.0, "map" to 40.0, "maf" to 40.0, "o2_b1s1_v" to 1.0)
        for ((k, sr) in data) {
            if (sr.size < 20) continue
            val jumps = Stats.suddenChanges(sr, 8.0, limits[k] ?: 0.0)
            if (k in limits && jumps.isNotEmpty()) {
                val ch = app.obdforge.core.pid.Pids.channel(k)
                out.add(Finding("Anomalies", "${ch?.name ?: k}: ${jumps.size} sudden change(s)",
                    "Largest step ${fmt(jumps.maxOf { abs(it.second) })} ${ch?.unit ?: ""}. Sudden steps in a slow signal can mean a wiring/connector dropout or a real event; check the graph at those times.",
                    Level.WARN))
            }
            val gaps = Stats.gaps(sr)
            if (gaps.isNotEmpty() && k == "rpm") out.add(Finding("Anomalies", "${gaps.size} logging gap(s)",
                "Gaps in the data (adapter or Bluetooth interruptions). Values across gaps are not interpolated.", Level.INFO))
        }
        return out
    }

    fun all(mode06: List<Mode06.TestResult> = emptyList()): List<Finding> =
        fuelTrims().findings + airflow() + boost() + misfires(mode06) + cooling() + fuelSystem() + electrical() + anomalies()

    companion object {
        fun fmt(v: Double): String = if (v.isNaN()) "--" else if (abs(v) >= 100) String.format("%.0f", v) else String.format("%.1f", v)
    }
}
