package app.obdforge.core.perf

import app.obdforge.core.live.VehicleSpec
import kotlin.math.PI
import kotlin.math.abs

enum class PerfTestType(val label: String, val fromKmh: Double, val toKmh: Double, val distanceM: Double? = null) {
    ZERO_60("0–60 km/h", 0.0, 60.0),
    ZERO_100("0–100 km/h", 0.0, 100.0),
    ZERO_160("0–160 km/h", 0.0, 160.0),
    ROLL_80_120("80–120 km/h", 80.0, 120.0),
    QUARTER_MILE("Quarter mile (402 m)", 0.0, 0.0, 402.336),
    EIGHTH_MILE("Eighth mile (201 m)", 0.0, 0.0, 201.168),
    ;
    val standingStart get() = fromKmh == 0.0
}

data class PerfPoint(val tMs: Long, val speedKmh: Double, val rpm: Double?, val distanceM: Double, val source: String)

data class PowerPoint(val rpm: Double?, val speedKmh: Double, val wheelKw: Double, val crankKw: Double, val crankNm: Double?)

data class PerfResult(
    val type: PerfTestType,
    val elapsedS: Double,
    val trapSpeedKmh: Double?,
    val distanceM: Double,
    val speedSource: String,
    val points: List<PerfPoint>,
    val power: List<PowerPoint>,
    val peakCrankKw: Double?,
    val peakCrankNm: Double?,
    val notes: List<String>,
)

/**
 * Timer state machine for acceleration tests. Feed it speed samples (GPS preferred: it updates
 * independently of the ECU and is not affected by tyre size; OBD speed is used otherwise). It
 * arms when stationary (or below the start speed for rolling tests), starts on the crossing, and
 * interpolates the exact crossing times between samples.
 */
class PerformanceTimer(val type: PerfTestType, private val spec: VehicleSpec) {
    enum class State { WAITING_TO_ARM, ARMED, RUNNING, DONE, ABORTED }

    var state = State.WAITING_TO_ARM; private set
    private val pts = ArrayList<PerfPoint>()
    private var startT = 0.0
    private var distance = 0.0
    private var last: PerfPoint? = null
    var result: PerfResult? = null; private set
    var abortReason: String? = null; private set

    fun feed(tMs: Long, speedKmh: Double, rpm: Double?, source: String) {
        val prev = last
        val p0 = PerfPoint(tMs, speedKmh, rpm, distance, source)
        when (state) {
            State.WAITING_TO_ARM -> {
                if (type.standingStart && speedKmh < 0.5) state = State.ARMED
                if (!type.standingStart && speedKmh < type.fromKmh - 5 && speedKmh > 10) state = State.ARMED
            }
            State.ARMED -> {
                val crossing = if (type.standingStart) speedKmh >= 0.8 else speedKmh >= type.fromKmh
                if (crossing && prev != null) {
                    val target = if (type.standingStart) 0.0 else type.fromKmh
                    startT = interp(prev, p0, target)
                    distance = 0.0
                    pts.clear()
                    pts.add(PerfPoint(startT.toLong(), target, prev.rpm, 0.0, source))
                    pts.add(p0.copy(distanceM = 0.0))
                    state = State.RUNNING
                } else if (!type.standingStart && speedKmh < 10) state = State.WAITING_TO_ARM
            }
            State.RUNNING -> {
                if (prev != null) {
                    val dt = (tMs - prev.tMs) / 1000.0
                    distance += (prev.speedKmh + speedKmh) / 2 / 3.6 * dt
                }
                val p = PerfPoint(tMs, speedKmh, rpm, distance, source)
                pts.add(p)
                if (prev != null && speedKmh < prev.speedKmh - 8) abort("Speed dropped (lift-off or braking)")
                else if ((tMs - startT) > 90_000) abort("Test took longer than 90 s")
                else if (type.distanceM != null && distance >= type.distanceM) {
                    // interpolate exact distance crossing
                    val prevD = pts[pts.size - 2].distanceM
                    val frac = if (distance - prevD > 0) (type.distanceM - prevD) / (distance - prevD) else 1.0
                    val tEnd = pts[pts.size - 2].tMs + frac * (tMs - pts[pts.size - 2].tMs)
                    val vEnd = pts[pts.size - 2].speedKmh + frac * (speedKmh - pts[pts.size - 2].speedKmh)
                    finish(tEnd, vEnd, type.distanceM)
                } else if (type.distanceM == null && speedKmh >= type.toKmh && prev != null) {
                    finish(interp(prev, p, type.toKmh), null, distance)
                }
            }
            else -> Unit
        }
        last = p0
    }

    private fun interp(a: PerfPoint, b: PerfPoint, v: Double): Double {
        if (b.speedKmh == a.speedKmh) return b.tMs.toDouble()
        val f = ((v - a.speedKmh) / (b.speedKmh - a.speedKmh)).coerceIn(0.0, 1.0)
        return a.tMs + f * (b.tMs - a.tMs)
    }

    private fun abort(reason: String) { state = State.ABORTED; abortReason = reason }

    private fun finish(tEnd: Double, trap: Double?, dist: Double) {
        state = State.DONE
        val elapsed = (tEnd - startT) / 1000.0
        val power = PowerEstimator(spec).estimate(pts)
        val notes = ArrayList<String>()
        val src = pts.lastOrNull()?.source ?: "OBD"
        if (src == "OBD") notes.add("Timed from OBD vehicle speed: updates only as fast as the adapter polls (often 5–10 Hz) and the speedometer signal can read a few percent high.")
        else notes.add("Timed from GPS speed.")
        notes.add("Power and torque are estimates from acceleration, the configured mass (${spec.massKg.toInt()} kg), drag and rolling resistance. Road slope, wind and wheelspin are not accounted for.")
        result = PerfResult(type, elapsed, trap, dist, src, pts.toList(), power, power.maxOfOrNull { it.crankKw }, power.mapNotNull { it.crankNm }.maxOrNull(), notes)
    }

    fun reset() { state = State.WAITING_TO_ARM; pts.clear(); last = null; result = null; abortReason = null; distance = 0.0 }
}

/** Road-load power estimate: P = m·a·v + ½·ρ·CdA·v³ + Crr·m·g·v. */
class PowerEstimator(private val spec: VehicleSpec) {
    fun estimate(points: List<PerfPoint>): List<PowerPoint> {
        if (points.size < 4) return emptyList()
        // Smooth speed with a centred moving average to tame quantised OBD speed (1 km/h steps).
        val v = points.map { it.speedKmh / 3.6 }
        val sm = v.indices.map { i -> val a = maxOf(0, i - 2); val b = minOf(v.size - 1, i + 2); v.subList(a, b + 1).average() }
        val out = ArrayList<PowerPoint>()
        for (i in 1 until points.size - 1) {
            val dt = (points[i + 1].tMs - points[i - 1].tMs) / 1000.0
            if (dt <= 0) continue
            val a = (sm[i + 1] - sm[i - 1]) / dt
            val speed = sm[i]
            if (speed < 2) continue
            val pW = spec.massKg * a * speed + 0.5 * 1.2 * spec.dragArea * speed * speed * speed + spec.rollingResistance * spec.massKg * 9.81 * speed
            if (pW <= 0) continue
            val wheelKw = pW / 1000
            val crankKw = wheelKw / (1 - spec.drivetrainLossPct / 100)
            val rpm = points[i].rpm
            val nm = if (rpm != null && rpm > 500) crankKw * 1000 / (2 * PI * rpm / 60) else null
            out.add(PowerPoint(rpm, points[i].speedKmh, wheelKw, crankKw, nm))
        }
        return out
    }
}

/**
 * Stationary free-rev test (vehicle parked, in neutral/park): measures how quickly RPM rises
 * and falls. Useful for comparing throttle response before/after repairs without driving.
 */
class FreeRevTest(private val fromRpm: Double = 1200.0, private val toRpm: Double = 3000.0) {
    var riseS: Double? = null; private set
    var fallS: Double? = null; private set
    private var riseStart: Long? = null
    private var fallStart: Long? = null
    private var lastRpm = 0.0
    val done get() = riseS != null && fallS != null

    fun feed(tMs: Long, rpm: Double, speedKmh: Double?): String? {
        if ((speedKmh ?: 0.0) > 2) return "Vehicle is moving: stationary test stopped."
        if (riseS == null) {
            if (lastRpm < fromRpm && rpm >= fromRpm) riseStart = tMs
            if (riseStart != null && rpm >= toRpm) riseS = (tMs - riseStart!!) / 1000.0
        } else if (fallS == null) {
            if (lastRpm >= toRpm && rpm < toRpm) fallStart = tMs
            if (fallStart != null && rpm <= fromRpm) fallS = (tMs - fallStart!!) / 1000.0
        }
        lastRpm = rpm
        return null
    }

    fun reset() { riseS = null; fallS = null; riseStart = null; fallStart = null; lastRpm = 0.0 }
}

fun speedFromRpm(rpm: Double, gearRatio: Double, finalDrive: Double, tyreDiameterM: Double): Double =
    if (gearRatio <= 0 || finalDrive <= 0) 0.0 else abs(rpm / gearRatio / finalDrive * PI * tyreDiameterM * 60 / 1000)
