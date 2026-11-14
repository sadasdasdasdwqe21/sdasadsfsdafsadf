package app.obdforge.util

import app.obdforge.core.pid.Channel
import app.obdforge.data.UnitSystem
import java.util.Locale

/** Converts metric channel values for display. Storage and exports stay metric. */
object Units {
    data class Display(val value: Double, val unit: String, val decimals: Int)

    fun convert(ch: Channel?, v: Double, system: UnitSystem): Display {
        val unit = ch?.unit ?: ""
        val dec = ch?.decimals ?: 1
        if (system == UnitSystem.METRIC || ch == null) return Display(v, unit, dec)
        return when (unit) {
            "°C" -> Display(v * 9 / 5 + 32, "°F", dec)
            "km/h" -> Display(v * 0.621371, "mph", dec)
            "km" -> Display(v * 0.621371, "mi", dec)
            "kPa" -> if (ch.key == "boost" || ch.key == "map" || ch.key.startsWith("boost")) Display(v * 0.145038, "psi", 1) else Display(v * 0.145038, "psi", 1)
            "L/h" -> Display(v * 0.264172, "gal/h", 2)
            "L/100km" -> Display(if (v > 0.01) 235.215 / v else 0.0, "mpg", 1)
            "km/L" -> Display(v * 2.35215, "mpg", 1)
            "g/s" -> Display(v * 0.132277, "lb/min", 2)
            else -> Display(v, unit, dec)
        }
    }

    fun range(ch: Channel, system: UnitSystem): Pair<Double, Double> {
        val a = convert(ch, ch.min, system).value; val b = convert(ch, ch.max, system).value
        return if (ch.unit == "L/100km" && system == UnitSystem.IMPERIAL) 0.0 to 60.0 else minOf(a, b) to maxOf(a, b)
    }

    fun format(ch: Channel?, v: Double?, system: UnitSystem, withUnit: Boolean = true): String {
        if (v == null || v.isNaN()) return "--"
        val d = convert(ch, v, system)
        val s = String.format(Locale.getDefault(), "%.${d.decimals}f", d.value)
        return if (withUnit && d.unit.isNotEmpty()) "$s ${d.unit}" else s
    }

    fun unitLabel(ch: Channel?, system: UnitSystem): String = if (ch == null) "" else convert(ch, 0.0, system).unit

    fun speed(kmh: Double, system: UnitSystem): String = if (system == UnitSystem.METRIC) "${kmh.toInt()} km/h" else "${(kmh * 0.621371).toInt()} mph"
}
