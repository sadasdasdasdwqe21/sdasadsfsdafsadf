package app.obdforge.core.live

import app.obdforge.core.obd.ObdClient
import app.obdforge.core.pid.Channel
import app.obdforge.core.pid.Pids

data class Sample(val key: String, val value: Double, val timeMs: Long)

/** Fuel used for estimates. AFR and density are textbook values for each fuel. */
enum class FuelKind(val label: String, val stoichAfr: Double, val densityGPerL: Double) {
    GASOLINE("Petrol / gasoline", 14.7, 745.0),
    DIESEL("Diesel", 14.5, 832.0),
    E85("E85", 9.8, 785.0),
    LPG("LPG", 15.5, 540.0),
}

/** Vehicle parameters used by calculated channels and performance estimates. */
data class VehicleSpec(
    val displacementL: Double = 2.0,
    val fuel: FuelKind = FuelKind.GASOLINE,
    val massKg: Double = 1600.0,
    val dragArea: Double = 0.75,          // Cd x frontal area, m²
    val rollingResistance: Double = 0.012,
    val drivetrainLossPct: Double = 15.0,
    val turbocharged: Boolean = true,
    val boostLimitKpa: Double = 120.0,     // gauge boost above which overboost is flagged
    val tyreDiameterM: Double = 0.68,
    val cylinders: Int = 4,
)

/**
 * Polls the selected channels as fast as the adapter and ECU allow. Each PID has its own
 * interval, so slow-changing values (coolant, fuel level) do not steal bandwidth from RPM.
 * Only PIDs that the ECU reported as supported are ever requested; a PID that keeps returning
 * nothing is dropped and reported in [unavailable] instead of being shown with fake data.
 */
class LivePoller(private val client: ObdClient, private val supported: Set<Int>) {
    private val intervals = HashMap<Int, Long>()      // pid -> interval ms
    private val lastPoll = HashMap<Int, Long>()
    private val misses = HashMap<Int, Int>()
    private var multiOk: Boolean = client.protocol.isCan
    val unavailable = HashSet<Int>()
    var requestCount = 0L; private set
    var responseCount = 0L; private set
    private val cycleTimes = ArrayDeque<Long>()

    @Synchronized
    fun select(channels: Map<String, Long>) {
        intervals.clear()
        for ((key, interval) in channels) {
            val ch = Pids.channel(key) ?: continue
            for (pid in requiredPids(ch)) {
                if (pid !in supported || pid in unavailable) continue
                intervals[pid] = minOf(intervals[pid] ?: Long.MAX_VALUE, interval)
            }
        }
    }

    /** PIDs needed to produce a channel (calculated channels need their inputs). */
    fun requiredPids(ch: Channel): List<Int> = when (ch.key) {
        "boost" -> listOf(0x0B, 0x33)
        "trim_total1" -> listOf(0x06, 0x07)
        "trim_total2" -> listOf(0x08, 0x09)
        "afr_cmd" -> listOf(0x44)
        "fuel_rate_est" -> listOf(0x10, 0x44)
        "economy_l100", "economy_kml" -> listOf(0x10, 0x0D, 0x5E, 0x44)
        "vol_eff" -> listOf(0x10, 0x0B, 0x0F, 0x0C)
        "rpm_per_kmh" -> listOf(0x0C, 0x0D)
        else -> if (ch.pid >= 0) listOf(ch.pid) else emptyList()
    }

    /** Achieved full-cycle rate in Hz over the last few seconds. */
    val cycleHz: Double
        @Synchronized get() {
            if (cycleTimes.size < 2) return 0.0
            val span = cycleTimes.last() - cycleTimes.first()
            return if (span <= 0) 0.0 else (cycleTimes.size - 1) * 1000.0 / span
        }

    /** One polling pass. Returns decoded samples for every channel of every PID that answered. */
    fun cycle(now: Long = System.currentTimeMillis()): List<Sample> {
        val due: List<Int>
        synchronized(this) {
            due = intervals.filter { (pid, iv) -> now - (lastPoll[pid] ?: 0L) >= iv }.keys.sorted()
            due.forEach { lastPoll[it] = now }
        }
        if (due.isEmpty()) return emptyList()
        requestCount += if (multiOk) ((due.size + 5) / 6).toLong() else due.size.toLong()
        val data = HashMap<Int, IntArray>(client.readPids(due, multiOk))
        val missing = due.filter { it !in data }
        if (multiOk && missing.isNotEmpty() && due.size > 1) {
            // Some ECUs answer only the first PID of a multi-PID request. Re-ask singly; if that works, stop combining.
            val single = client.readPids(missing, multi = false)
            if (single.isNotEmpty()) { multiOk = false; data.putAll(single) }
        }
        val out = ArrayList<Sample>()
        val t = System.currentTimeMillis()
        for (pid in due) {
            val d = data[pid]
            if (d == null) {
                val n = (misses[pid] ?: 0) + 1
                misses[pid] = n
                if (n >= 5) synchronized(this) { unavailable.add(pid); intervals.remove(pid) }
                continue
            }
            misses[pid] = 0
            responseCount++
            val spec = Pids[pid] ?: continue
            for (c in spec.channels) c.decode(d)?.let { out.add(Sample(c.key, it, t)) }
        }
        synchronized(this) {
            cycleTimes.addLast(t)
            while (cycleTimes.size > 20) cycleTimes.removeFirst()
        }
        return out
    }
}

/**
 * Produces calculated channels from the latest ECU values. Every value produced here is a
 * calculation, labelled "calc" in the UI and in exports.
 */
class DerivedCalculator(var spec: VehicleSpec = VehicleSpec()) {
    fun compute(latest: Map<String, Double>, t: Long): List<Sample> {
        val out = ArrayList<Sample>()
        fun v(k: String) = latest[k]
        val map = v("map"); val baro = v("baro")
        if (map != null && baro != null) out.add(Sample("boost", map - baro, t))
        val s1 = v("stft1"); val l1 = v("ltft1")
        if (s1 != null && l1 != null) out.add(Sample("trim_total1", s1 + l1, t))
        val s2 = v("stft2"); val l2 = v("ltft2")
        if (s2 != null && l2 != null) out.add(Sample("trim_total2", s2 + l2, t))
        val lam = v("lambda_cmd")
        if (lam != null) out.add(Sample("afr_cmd", lam * spec.fuel.stoichAfr, t))
        val maf = v("maf")
        var fuelLh: Double? = v("fuel_rate")
        if (maf != null && spec.fuel != FuelKind.DIESEL) {
            val afr = spec.fuel.stoichAfr * (lam ?: 1.0)
            val est = maf / afr * 3600.0 / spec.fuel.densityGPerL
            out.add(Sample("fuel_rate_est", est, t))
            if (fuelLh == null) fuelLh = est
        }
        val speed = v("speed")
        if (fuelLh != null && speed != null) {
            if (speed > 3) {
                out.add(Sample("economy_l100", fuelLh / speed * 100.0, t))
                if (fuelLh > 0.05) out.add(Sample("economy_kml", speed / fuelLh, t))
            }
        }
        val rpm = v("rpm"); val iat = v("iat")
        if (rpm != null && speed != null && speed > 5) out.add(Sample("rpm_per_kmh", rpm / speed, t))
        if (maf != null && map != null && rpm != null && iat != null && rpm > 300) {
            val rho = map * 1000.0 / (287.05 * (iat + 273.15)) // kg/m³
            val theoretical = rho * (spec.displacementL / 1000.0) * (rpm / 120.0) * 1000.0 // g/s
            if (theoretical > 0) out.add(Sample("vol_eff", maf / theoretical * 100.0, t))
        }
        return out
    }
}
