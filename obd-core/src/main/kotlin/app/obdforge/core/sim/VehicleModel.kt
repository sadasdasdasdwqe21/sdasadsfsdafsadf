package app.obdforge.core.sim

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

enum class SimScenario(val label: String, val description: String) {
    NORMAL("Normal operation", "Healthy 2.0 L turbo petrol engine on a repeating drive cycle."),
    RICH("Rich running", "Leaking injector / purge: LTFT around -25 %, STFT around -5 %, P0172 sets after a while."),
    LEAN("Lean (vacuum leak)", "Unmetered air: strongly positive trims at idle that improve with RPM, P0171."),
    OVERHEAT("Overheating", "Cooling fault: coolant keeps climbing past 110 °C, P0217."),
    MISFIRE("Misfire cylinder 2", "Weak coil on cylinder 2: RPM jitter, Mode 06 counts, P0302 pending then stored."),
    SENSOR_FAULT("O2 sensor fault", "Upstream O2 sensor stuck at 0.45 V: no switching, P0134."),
    UNDERBOOST("Boost leak", "Turbo cannot reach target boost: low MAP under load, P0299."),
    COMM_FAILURE("Communication problems", "Random NO DATA, corrupted bytes and periodic link drops to exercise recovery."),
    ENGINE_OFF("Ignition on, engine off", "Key on engine off: RPM 0, the vehicle still answers OBD requests."),
}

/**
 * A small physical model of a 2.0 L turbocharged direct-injection petrol engine driven around
 * a repeating cycle. Values are self-consistent (MAF follows RPM and MAP, trims follow the fault
 * scenario, coolant warms up) so that the analysis and assistant code see realistic patterns.
 */
class VehicleModel(var scenario: SimScenario = SimScenario.NORMAL, seed: Long = 42) {
    private val rnd = java.util.Random(seed)
    var t = 0.0; private set

    // Outputs
    var rpm = 0.0; var speed = 0.0; var throttle = 0.0; var load = 0.0; var map = 30.0
    val baro = 98.0
    var maf = 0.0; var ect = 25.0; var iat = 30.0; var ambient = 25.0; var oilTemp = 25.0
    var stft1 = 0.0; var ltft1 = 0.0; var timing = 10.0
    var o2s1 = 0.45; var o2s2 = 0.6; var lambdaCmd = 1.0; var lambdaWide = 1.0
    var catTemp = 25.0; var voltage = 12.4; var fuelLevel = 63.0; var runtime = 0.0
    var railPressureKpa = 0.0; var fuelRateLh = 0.0; var odometerKm = 84213.4
    var distanceSinceClear = 1520.0; var warmups = 37; var timeSinceClearMin = 3110.0
    var misfireCounts = IntArray(4)
    var engineRunning = true
    var closedLoop = false
    val displacementL = 2.0
    private var cycleSeconds = 0.0
    private var lambdaPhase = 0.0
    private var spool = 0.0

    fun step(dt: Double) {
        if (dt <= 0) return
        t += dt
        engineRunning = scenario != SimScenario.ENGINE_OFF
        if (!engineRunning) {
            rpm = 0.0; speed = 0.0; throttle = 0.0; load = 0.0; map = baro; maf = 0.0
            voltage = 12.3; stft1 = 0.0; closedLoop = false; o2s1 = 0.0; o2s2 = 0.0; fuelRateLh = 0.0
            ect += (ambient - ect) * dt / 600; iat = ambient + 3; railPressureKpa = 450.0
            return
        }
        runtime += dt
        cycleSeconds = (cycleSeconds + dt) % 120.0
        // Drive cycle: idle 0-20 s, accelerate 20-32 s, cruise 32-70, hard pull 70-80, coast 80-100, idle.
        val c = cycleSeconds
        val targetThrottle: Double
        val targetSpeed: Double
        when {
            c < 20 -> { targetThrottle = 0.0; targetSpeed = 0.0 }
            c < 32 -> { targetThrottle = 35.0; targetSpeed = (c - 20) / 12 * 70 }
            c < 70 -> { targetThrottle = 14.0 + 2 * sin(c / 4); targetSpeed = 70.0 + 3 * sin(c / 6) }
            c < 80 -> { targetThrottle = 95.0; targetSpeed = 70 + (c - 70) * 5.0 }
            c < 100 -> { targetThrottle = 0.0; targetSpeed = max(0.0, 120 - (c - 80) * 6.0) }
            else -> { targetThrottle = 0.0; targetSpeed = 0.0 }
        }
        throttle += (targetThrottle - throttle) * min(1.0, dt * 4)
        speed += (targetSpeed - speed) * min(1.0, dt * 2)
        if (speed < 0.5) speed = 0.0
        // Gear model for a CVT-like ratio: rpm follows speed with a floor at idle.
        val idle = 750.0 + if (ect < 50) 250.0 else 0.0
        val targetRpm = if (speed < 1) idle else max(idle, 1250 + speed * 18 + throttle * 22)
        rpm += (targetRpm - rpm) * min(1.0, dt * 3)
        if (scenario == SimScenario.MISFIRE) rpm += (rnd.nextGaussian() * 35)
        rpm = rpm.coerceIn(0.0, 6500.0)

        // Turbo spools above ~2000 rpm with throttle; boost leak limits it.
        val spoolTarget = if (throttle > 50 && rpm > 2000) min(1.0, (rpm - 2000) / 1500) else 0.0
        spool += (spoolTarget - spool) * min(1.0, dt * 1.5)
        val maxBoostKpa = if (scenario == SimScenario.UNDERBOOST) 35.0 else 105.0
        val vacuumMap = 28 + throttle * 0.75
        map = if (spool > 0.02) baro + spool * maxBoostKpa * (throttle / 100) else min(baro - 2, vacuumMap)
        map += rnd.nextGaussian() * 0.4
        load = ((map / baro) * 100 * 0.85).coerceIn(15.0, 100.0)
        if (throttle < 1 && speed > 5) load = 0.0 // overrun fuel cut

        // Speed-density airflow (g/s). VE ~85 %.
        val rho = map * 1000 / (287.05 * (iat + 273.15))
        val trueAir = rho * (displacementL / 1000) * (rpm / 120) * 0.85 * 1000
        val leakAir = if (scenario == SimScenario.LEAN) 0.9 else 0.0 // unmetered g/s
        maf = max(0.0, trueAir + rnd.nextGaussian() * 0.05)
        if (scenario == SimScenario.UNDERBOOST && spool > 0.1) maf *= 1.08 // air escapes after the MAF

        // Temperatures
        val thermostat = 90.0
        val heatIn = (rpm / 6000) * 0.9 + load / 300
        ect += when (scenario) {
            SimScenario.OVERHEAT -> (if (ect < 125) 0.06 + heatIn * 0.15 else 0.0) * dt * 10
            else -> if (ect < thermostat) (0.25 + heatIn) * dt else (thermostat - ect) * 0.2 * dt
        }
        ect += rnd.nextGaussian() * 0.02
        oilTemp += ((ect - 5) - oilTemp) * dt / 120
        iat += ((ambient + 8 + (if (spool > 0.2) 15 * spool else 0.0) + (if (speed < 5) 10.0 else 0.0)) - iat) * dt / 20
        catTemp += ((if (throttle < 1 && speed > 5) 380.0 else 450 + load * 3.5) - catTemp) * dt / 30

        // Closed loop after warm-up and not at full load / overrun.
        closedLoop = ect > 40 && throttle < 80 && !(throttle < 1 && speed > 5)
        lambdaCmd = if (throttle >= 80) 0.82 else 1.0

        // Fuel trims: the controller corrects a mixture error. Positive = adding fuel.
        val mixtureError = when (scenario) {
            SimScenario.RICH -> -28.0 // excess fuel: controller removes fuel
            SimScenario.LEAN -> if (maf > 0.1) 100 * leakAir / (maf + leakAir) * 1.7 else 0.0
            SimScenario.UNDERBOOST -> -4.0
            else -> 1.5
        }
        if (closedLoop) {
            // LTFT learns slowly towards the needed correction; STFT handles the rest.
            ltft1 += ((mixtureError * 0.85).coerceIn(-30.0, 30.0) - ltft1) * min(1.0, dt / 20)
            lambdaPhase += dt * 2 * PI * 1.1
            stft1 = (mixtureError - ltft1) + 3.0 * sin(lambdaPhase) + rnd.nextGaussian() * 0.6
            if (scenario == SimScenario.RICH) stft1 = -5.0 + 2.5 * sin(lambdaPhase) + rnd.nextGaussian() * 0.5
        } else {
            stft1 = 0.0
        }
        stft1 = stft1.coerceIn(-25.0, 25.0)

        // Oxygen sensors
        val residual = if (closedLoop) 0.0 else if (lambdaCmd < 1) -0.15 else 0.0
        lambdaWide = (lambdaCmd + residual + 0.02 * sin(lambdaPhase) + (if (scenario == SimScenario.RICH && !closedLoop) -0.12 else 0.0)).coerceIn(0.6, 1.6)
        o2s1 = when {
            scenario == SimScenario.SENSOR_FAULT -> 0.45 + rnd.nextGaussian() * 0.003
            !closedLoop && throttle < 1 && speed > 5 -> 0.05
            !closedLoop && lambdaCmd < 1 -> 0.88
            !closedLoop -> 0.45
            else -> (0.45 + 0.4 * sin(lambdaPhase) + (if (scenario == SimScenario.RICH) 0.08 else 0.0)).coerceIn(0.05, 0.92)
        }
        o2s2 = (0.65 + 0.02 * sin(t / 7) + (if (scenario == SimScenario.RICH) 0.12 else 0.0)).coerceIn(0.0, 1.0)

        // Ignition timing: more advance at light load, retarded under boost.
        timing = (if (speed < 1 && throttle < 1) 12.0 else 34 - load * 0.28) + rnd.nextGaussian() * 0.6
        if (scenario == SimScenario.MISFIRE) timing -= 2

        voltage = 14.2 + rnd.nextGaussian() * 0.05
        railPressureKpa = (if (throttle > 60) 11000.0 else 4000.0 + load * 40) + rnd.nextGaussian() * 50

        // Fuel used (ECU-style): true air / (AFR * density)
        val afr = 14.7 * lambdaCmd
        val fuelGs = if (load == 0.0) 0.0 else (trueAir + leakAir) / afr * (1 + (if (scenario == SimScenario.RICH) 0.25 else 0.0))
        fuelRateLh = fuelGs * 3600 / 745.0
        fuelLevel = max(5.0, fuelLevel - fuelRateLh * dt / 3600 / 60 * 100)
        odometerKm += speed * dt / 3600
        distanceSinceClear += speed * dt / 3600

        // Misfire counters (Mode 06 style, per cylinder, rolling).
        if (scenario == SimScenario.MISFIRE && rpm > 500) {
            if (rnd.nextDouble() < 0.6 * dt * 10) misfireCounts[1]++
        }
        if (rnd.nextDouble() < 0.002) misfireCounts[rnd.nextInt(4)]++
    }

    fun misfireRate(cyl: Int): Int = misfireCounts.getOrElse(cyl) { 0 }

    fun clearAdaptives() {
        ltft1 = 0.0
        misfireCounts = IntArray(4)
        distanceSinceClear = 0.0; warmups = 0; timeSinceClearMin = 0.0
    }

    fun gaussianNoise(scale: Double) = rnd.nextGaussian() * scale
    fun chance(p: Double) = rnd.nextDouble() < p
    fun randomInt(n: Int) = rnd.nextInt(n)

    companion object {
        fun approach(cur: Double, target: Double, rate: Double, dt: Double) = cur + (target - cur) * (1 - exp(-rate * dt))
        fun clamp(v: Double, lo: Double, hi: Double) = max(lo, min(hi, v))
        fun absDiff(a: Double, b: Double) = abs(a - b)
    }
}
