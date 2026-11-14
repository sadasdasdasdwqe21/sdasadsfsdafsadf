package app.obdforge.core.alert

import app.obdforge.core.pid.Pids

enum class AlertCondition(val label: String) { ABOVE("above"), BELOW("below"), OUTSIDE("outside"), ABS_ABOVE("magnitude above") }

data class AlertRule(
    val id: String,
    val title: String,
    val channelKey: String,
    val condition: AlertCondition,
    val threshold: Double,
    val threshold2: Double = Double.NaN,
    /** The condition must hold this long before alerting (filters spikes). */
    val sustainMs: Long = 2_000,
    val cooldownMs: Long = 60_000,
    val enabled: Boolean = true,
    val critical: Boolean = false,
    /** Only evaluate while RPM is above this (e.g. charging voltage only when running). */
    val minRpm: Double? = null,
) {
    fun matches(v: Double): Boolean = when (condition) {
        AlertCondition.ABOVE -> v > threshold
        AlertCondition.BELOW -> v < threshold
        AlertCondition.OUTSIDE -> v < threshold || v > threshold2
        AlertCondition.ABS_ABOVE -> kotlin.math.abs(v) > threshold
    }

    fun describe(): String {
        val ch = Pids.channel(channelKey)
        val unit = ch?.unit ?: ""
        return when (condition) {
            AlertCondition.OUTSIDE -> "${ch?.shortName ?: channelKey} outside $threshold–$threshold2 $unit"
            else -> "${ch?.shortName ?: channelKey} ${condition.label} $threshold $unit"
        }
    }
}

data class AlertEvent(val ruleId: String, val title: String, val message: String, val value: Double, val timeMs: Long, val critical: Boolean)

/** Evaluates rules against incoming samples. Not thread-safe; feed from one thread. */
class AlertEngine(rules: List<AlertRule> = defaults()) {
    var rules: List<AlertRule> = rules
        set(v) { field = v; since.clear() }
    private val since = HashMap<String, Long>()
    private val lastFired = HashMap<String, Long>()
    private val knownDtcs = HashSet<String>()

    fun evaluate(latest: Map<String, Double>, now: Long): List<AlertEvent> {
        val out = ArrayList<AlertEvent>()
        for (r in rules) {
            if (!r.enabled) continue
            val v = latest[r.channelKey] ?: continue
            if (r.minRpm != null && (latest["rpm"] ?: 0.0) < r.minRpm) { since.remove(r.id); continue }
            if (r.matches(v)) {
                val start = since.getOrPut(r.id) { now }
                val coolingDown = lastFired[r.id]?.let { now - it < r.cooldownMs } ?: false
                if (now - start >= r.sustainMs && !coolingDown) {
                    lastFired[r.id] = now
                    val ch = Pids.channel(r.channelKey)
                    out.add(AlertEvent(r.id, r.title, "${r.describe()} — now ${ch?.format(v) ?: v} ${ch?.unit ?: ""}", v, now, r.critical))
                }
            } else since.remove(r.id)
        }
        return out
    }

    /** Alerts for codes not seen before in this connection. */
    fun checkDtcs(codes: Collection<String>, now: Long): List<AlertEvent> {
        val fresh = codes.filter { knownDtcs.add(it) }
        return fresh.map { AlertEvent("new_dtc", "New trouble code", "$it: ${app.obdforge.core.dtc.DtcDatabase.describe(it.take(5)) ?: "manufacturer-specific"}", 0.0, now, true) }
    }

    fun primeDtcs(codes: Collection<String>) { knownDtcs.addAll(codes) }

    companion object {
        fun defaults(): List<AlertRule> = listOf(
            AlertRule("ect_high", "High coolant temperature", "ect", AlertCondition.ABOVE, 105.0, sustainMs = 5_000, critical = true),
            AlertRule("volt_low", "Low system voltage", "ecu_voltage", AlertCondition.BELOW, 12.8, sustainMs = 10_000, minRpm = 600.0),
            AlertRule("volt_low_adapter", "Low battery voltage (adapter)", "adapter_voltage", AlertCondition.BELOW, 11.8, sustainMs = 10_000),
            AlertRule("boost_high", "Excessive boost", "boost", AlertCondition.ABOVE, 120.0, sustainMs = 500, critical = true),
            AlertRule("trim_b1", "Abnormal fuel trim bank 1", "trim_total1", AlertCondition.ABS_ABOVE, 20.0, sustainMs = 20_000),
            AlertRule("trim_b2", "Abnormal fuel trim bank 2", "trim_total2", AlertCondition.ABS_ABOVE, 20.0, sustainMs = 20_000),
            AlertRule("iat_high", "High intake air temperature", "iat", AlertCondition.ABOVE, 65.0, sustainMs = 10_000),
            AlertRule("oil_high", "High oil temperature", "oil_temp", AlertCondition.ABOVE, 130.0, sustainMs = 10_000, critical = true),
            AlertRule("frp_low", "Low fuel rail pressure", "frp", AlertCondition.BELOW, 2000.0, sustainMs = 3_000, enabled = false, minRpm = 600.0),
            AlertRule("misfire", "Engine misfire counts rising", "misfire_total", AlertCondition.ABOVE, 20.0, sustainMs = 0, enabled = true),
        )
    }
}
