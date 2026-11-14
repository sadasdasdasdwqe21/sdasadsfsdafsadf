package app.obdforge.data

import app.obdforge.core.pid.Pids
import app.obdforge.core.util.Json
import app.obdforge.core.util.asDouble
import app.obdforge.core.util.asList
import app.obdforge.core.util.asMap
import app.obdforge.core.util.asStr

enum class GaugeStyle(val label: String) { ANALOG("Analogue dial"), RING("Circular ring"), DIGITAL("Digital"), BAR("Bar"), GRAPH("Line graph") }

data class GaugeConfig(
    val channel: String,
    val style: GaugeStyle = GaugeStyle.RING,
    /** 1 = one grid cell wide, 2 = full row on phones */
    val span: Int = 1,
    /** 1 = normal height, 2 = tall */
    val height: Int = 1,
    val color: Long = 0xFF22D3EE,
    val min: Double? = null,
    val max: Double? = null,
    val warnHigh: Double? = null,
    val warnLow: Double? = null,
    val showStats: Boolean = true,
)

data class Dashboard(val id: Long, val name: String, val columns: Int, val gauges: List<GaugeConfig>, val preset: Boolean)

object DashboardCodec {
    fun encode(columns: Int, gauges: List<GaugeConfig>): String = Json.write(mapOf("columns" to columns, "gauges" to gauges.map {
        mapOf("channel" to it.channel, "style" to it.style.name, "span" to it.span, "height" to it.height, "color" to it.color,
            "min" to it.min, "max" to it.max, "warnHigh" to it.warnHigh, "warnLow" to it.warnLow, "showStats" to it.showStats)
    }))

    fun decode(e: DashboardEntity): Dashboard {
        val m = runCatching { Json.parse(e.json).asMap() }.getOrDefault(emptyMap())
        val gauges = m["gauges"].asList().mapNotNull { g ->
            val x = g.asMap()
            val ch = x["channel"].asStr() ?: return@mapNotNull null
            GaugeConfig(ch, runCatching { GaugeStyle.valueOf(x["style"].asStr()!!) }.getOrDefault(GaugeStyle.RING),
                x["span"].asDouble()?.toInt() ?: 1, x["height"].asDouble()?.toInt() ?: 1, (x["color"] as? Number)?.toLong() ?: 0xFF22D3EE,
                x["min"].asDouble(), x["max"].asDouble(), x["warnHigh"].asDouble(), x["warnLow"].asDouble(), x["showStats"] as? Boolean ?: true)
        }
        return Dashboard(e.id, e.name, m["columns"].asDouble()?.toInt() ?: 2, gauges, e.preset)
    }

    private val C1 = 0xFF22D3EE; private val C2 = 0xFFF97316; private val C3 = 0xFF4ADE80; private val C4 = 0xFFC084FC; private val C5 = 0xFFFACC15

    /** The nine preset dashboards. Gauges for PIDs the car does not support show "not supported". */
    fun presets(): List<Pair<String, List<GaugeConfig>>> = listOf(
        "Engine performance" to listOf(
            GaugeConfig("rpm", GaugeStyle.ANALOG, 2, 2, C1, warnHigh = 6200.0), GaugeConfig("speed", GaugeStyle.DIGITAL, 1, 1, C3),
            GaugeConfig("load", GaugeStyle.RING, 1, 1, C2), GaugeConfig("timing", GaugeStyle.BAR, 1, 1, C4), GaugeConfig("tps", GaugeStyle.BAR, 1, 1, C5),
            GaugeConfig("torque_actual", GaugeStyle.RING, 1, 1, C2), GaugeConfig("rpm", GaugeStyle.GRAPH, 2, 1, C1)),
        "Fuel system" to listOf(
            GaugeConfig("stft1", GaugeStyle.BAR, 1, 1, C1, -25.0, 25.0, 10.0, -10.0), GaugeConfig("ltft1", GaugeStyle.BAR, 1, 1, C2, -25.0, 25.0, 10.0, -10.0),
            GaugeConfig("stft2", GaugeStyle.BAR, 1, 1, C1, -25.0, 25.0, 10.0, -10.0), GaugeConfig("ltft2", GaugeStyle.BAR, 1, 1, C2, -25.0, 25.0, 10.0, -10.0),
            GaugeConfig("trim_total1", GaugeStyle.GRAPH, 2, 1, C5), GaugeConfig("frp", GaugeStyle.DIGITAL, 1, 1, C3), GaugeConfig("fuel_rate", GaugeStyle.DIGITAL, 1, 1, C4),
            GaugeConfig("fuel_rate_est", GaugeStyle.DIGITAL, 1, 1, C4), GaugeConfig("economy_l100", GaugeStyle.DIGITAL, 1, 1, C3), GaugeConfig("lambda_cmd", GaugeStyle.DIGITAL, 1, 1, C1),
            GaugeConfig("fuel_level", GaugeStyle.BAR, 1, 1, C3)),
        "Turbo & boost" to listOf(
            GaugeConfig("boost", GaugeStyle.ANALOG, 2, 2, C2, -100.0, 150.0, 120.0), GaugeConfig("map", GaugeStyle.DIGITAL, 1, 1, C1), GaugeConfig("baro", GaugeStyle.DIGITAL, 1, 1, C1),
            GaugeConfig("boost_cmd_a", GaugeStyle.DIGITAL, 1, 1, C4), GaugeConfig("boost_act_a", GaugeStyle.DIGITAL, 1, 1, C4), GaugeConfig("iat", GaugeStyle.RING, 1, 1, C5, warnHigh = 60.0),
            GaugeConfig("rpm", GaugeStyle.RING, 1, 1, C1), GaugeConfig("boost", GaugeStyle.GRAPH, 2, 1, C2)),
        "Cooling system" to listOf(
            GaugeConfig("ect", GaugeStyle.ANALOG, 2, 2, C2, 40.0, 130.0, 105.0), GaugeConfig("oil_temp", GaugeStyle.RING, 1, 1, C5, warnHigh = 130.0),
            GaugeConfig("iat", GaugeStyle.RING, 1, 1, C1), GaugeConfig("ambient", GaugeStyle.DIGITAL, 1, 1, C3), GaugeConfig("load", GaugeStyle.BAR, 1, 1, C4),
            GaugeConfig("ect", GaugeStyle.GRAPH, 2, 1, C2)),
        "Intake & airflow" to listOf(
            GaugeConfig("maf", GaugeStyle.RING, 1, 1, C1), GaugeConfig("map", GaugeStyle.RING, 1, 1, C2), GaugeConfig("iat", GaugeStyle.DIGITAL, 1, 1, C5),
            GaugeConfig("tps", GaugeStyle.BAR, 1, 1, C3), GaugeConfig("app_d", GaugeStyle.BAR, 1, 1, C3), GaugeConfig("vol_eff", GaugeStyle.DIGITAL, 1, 1, C4),
            GaugeConfig("maf", GaugeStyle.GRAPH, 2, 1, C1)),
        "Emissions" to listOf(
            GaugeConfig("o2_b1s1_v", GaugeStyle.GRAPH, 2, 1, C1, 0.0, 1.0), GaugeConfig("o2_b1s2_v", GaugeStyle.GRAPH, 2, 1, C2, 0.0, 1.0),
            GaugeConfig("o2w1_lambda", GaugeStyle.DIGITAL, 1, 1, C3), GaugeConfig("o2c1_lambda", GaugeStyle.DIGITAL, 1, 1, C3), GaugeConfig("cat_b1s1", GaugeStyle.RING, 1, 1, C2),
            GaugeConfig("evap_purge", GaugeStyle.BAR, 1, 1, C4), GaugeConfig("egr_cmd", GaugeStyle.BAR, 1, 1, C5)),
        "Transmission" to listOf(
            GaugeConfig("speed", GaugeStyle.ANALOG, 2, 2, C3), GaugeConfig("rpm", GaugeStyle.RING, 1, 1, C1), GaugeConfig("rpm_per_kmh", GaugeStyle.DIGITAL, 1, 1, C4),
            GaugeConfig("load", GaugeStyle.BAR, 1, 1, C2), GaugeConfig("gps_speed", GaugeStyle.DIGITAL, 1, 1, C5)),
        "Electrical" to listOf(
            GaugeConfig("ecu_voltage", GaugeStyle.ANALOG, 2, 2, C5, 10.0, 16.0, 14.9, 12.2), GaugeConfig("adapter_voltage", GaugeStyle.DIGITAL, 1, 1, C5),
            GaugeConfig("rpm", GaugeStyle.DIGITAL, 1, 1, C1), GaugeConfig("ecu_voltage", GaugeStyle.GRAPH, 2, 1, C5)),
        "General" to listOf(
            GaugeConfig("speed", GaugeStyle.DIGITAL, 1, 1, C3), GaugeConfig("rpm", GaugeStyle.RING, 1, 1, C1), GaugeConfig("ect", GaugeStyle.RING, 1, 1, C2, warnHigh = 105.0),
            GaugeConfig("fuel_level", GaugeStyle.BAR, 1, 1, C3), GaugeConfig("ecu_voltage", GaugeStyle.DIGITAL, 1, 1, C5), GaugeConfig("economy_l100", GaugeStyle.DIGITAL, 1, 1, C4),
            GaugeConfig("ambient", GaugeStyle.DIGITAL, 1, 1, C1), GaugeConfig("runtime", GaugeStyle.DIGITAL, 1, 1, C1)),
    )

    fun profilePreset(name: String, keys: List<String>): List<GaugeConfig> = keys.filter { Pids.channel(it) != null }.mapIndexed { i, k ->
        GaugeConfig(k, if (i == 0) GaugeStyle.ANALOG else if (i % 3 == 0) GaugeStyle.DIGITAL else GaugeStyle.RING, if (i == 0) 2 else 1, if (i == 0) 2 else 1,
            listOf(C1, C2, C3, C4, C5)[i % 5])
    }
}
