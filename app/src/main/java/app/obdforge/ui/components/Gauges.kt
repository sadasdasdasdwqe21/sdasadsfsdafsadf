package app.obdforge.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.obdforge.core.analysis.Series
import app.obdforge.core.pid.Channel
import app.obdforge.data.GaugeConfig
import app.obdforge.data.GaugeStyle
import app.obdforge.data.UnitSystem
import app.obdforge.live.LiveValue
import app.obdforge.ui.theme.Palette
import app.obdforge.util.Units
import kotlin.math.cos
import kotlin.math.sin

/** Everything a gauge needs, already converted to display units. */
class GaugeData(
    val title: String,
    val unit: String,
    val value: Double?,
    val min: Double,
    val max: Double,
    val decimals: Int,
    val warnHigh: Double?,
    val warnLow: Double?,
    val stats: Triple<Double, Double, Double>?, // min, max, avg
    val color: Color,
    val sourceBadge: String?,
    val unsupported: Boolean,
) {
    val alarm: Boolean get() = value != null && ((warnHigh != null && value > warnHigh) || (warnLow != null && value < warnLow))
    fun fmt(v: Double?): String = if (v == null || v.isNaN()) "--" else String.format("%.${decimals}f", v)
    val fraction: Float get() = if (value == null || max <= min) 0f else ((value - min) / (max - min)).toFloat().coerceIn(0f, 1f)
}

fun gaugeData(cfg: GaugeConfig, ch: Channel?, lv: LiveValue?, units: UnitSystem, unsupported: Boolean): GaugeData {
    fun c(v: Double?) = if (v == null) null else Units.convert(ch, v, units).value
    val (rmin, rmax) = if (ch != null) Units.range(ch, units) else 0.0 to 100.0
    return GaugeData(
        title = ch?.shortName ?: cfg.channel,
        unit = Units.unitLabel(ch, units),
        value = c(lv?.value),
        min = c(cfg.min) ?: rmin, max = c(cfg.max) ?: rmax,
        decimals = ch?.decimals ?: 1,
        warnHigh = c(cfg.warnHigh), warnLow = c(cfg.warnLow),
        stats = lv?.let { Triple(c(it.min)!!, c(it.max)!!, c(it.avg)!!) },
        color = Color(cfg.color),
        sourceBadge = ch?.source?.takeIf { it != Channel.Source.ECU }?.badge,
        unsupported = unsupported,
    )
}

@Composable
fun GaugeView(style: GaugeStyle, d: GaugeData, history: Series?, modifier: Modifier = Modifier, showStats: Boolean = true) {
    val bg = MaterialTheme.colorScheme.surfaceContainer
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(bg).padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(d.title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            d.sourceBadge?.let { Chip(it, Palette.Purple) }
        }
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            if (d.unsupported) {
                Text("Not supported by this vehicle", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            } else when (style) {
                GaugeStyle.ANALOG -> AnalogGauge(d)
                GaugeStyle.RING -> RingGauge(d)
                GaugeStyle.DIGITAL -> DigitalGauge(d)
                GaugeStyle.BAR -> BarGauge(d)
                GaugeStyle.GRAPH -> GraphGauge(d, history)
            }
        }
        if (showStats && d.stats != null && !d.unsupported) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                StatText("min", d.fmt(d.stats.first)); StatText("avg", d.fmt(d.stats.third)); StatText("max", d.fmt(d.stats.second))
            }
        }
    }
}

@Composable
private fun StatText(label: String, v: String) {
    Text("$label $v", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace)
}

@Composable
private fun valueColor(d: GaugeData) = if (d.alarm) Palette.Red else MaterialTheme.colorScheme.onSurface

@Composable
fun AnalogGauge(d: GaugeData) {
    val anim by animateFloatAsState(d.fraction, tween(180), label = "needle")
    val tm = rememberTextMeasurer()
    val track = MaterialTheme.colorScheme.surfaceVariant
    val tickColor = MaterialTheme.colorScheme.onSurfaceVariant
    val vc = valueColor(d)
    val onSurface = MaterialTheme.colorScheme.onSurface
    Canvas(Modifier.fillMaxSize().aspectRatio(1.15f)) {
        val sweep = 240f; val start = 150f
        val r = minOf(size.width, size.height * 1.15f) / 2 * 0.86f
        val c = Offset(size.width / 2, size.height * 0.55f)
        val tl = Offset(c.x - r, c.y - r)
        drawArc(track, start, sweep, false, tl, Size(r * 2, r * 2), style = Stroke(r * 0.09f, cap = StrokeCap.Round))
        if (d.warnHigh != null && d.max > d.min) {
            val f = ((d.warnHigh - d.min) / (d.max - d.min)).toFloat().coerceIn(0f, 1f)
            drawArc(Palette.Red.copy(alpha = 0.7f), start + sweep * f, sweep * (1 - f), false, tl, Size(r * 2, r * 2), style = Stroke(r * 0.09f))
        }
        if (d.warnLow != null && d.max > d.min) {
            val f = ((d.warnLow - d.min) / (d.max - d.min)).toFloat().coerceIn(0f, 1f)
            drawArc(Palette.Amber.copy(alpha = 0.7f), start, sweep * f, false, tl, Size(r * 2, r * 2), style = Stroke(r * 0.09f))
        }
        drawArc(Brush.sweepGradient(listOf(d.color.copy(alpha = 0.3f), d.color), c), start, sweep * anim, false, tl, Size(r * 2, r * 2),
            style = Stroke(r * 0.09f, cap = StrokeCap.Round))
        for (i in 0..10) {
            val a = Math.toRadians((start + sweep * i / 10).toDouble())
            val inner = if (i % 5 == 0) r * 0.74f else r * 0.8f
            drawLine(tickColor, Offset(c.x + inner * cos(a).toFloat(), c.y + inner * sin(a).toFloat()),
                Offset(c.x + r * 0.86f * cos(a).toFloat(), c.y + r * 0.86f * sin(a).toFloat()), strokeWidth = if (i % 5 == 0) 4f else 2f)
            if (i % 5 == 0) {
                val v = d.min + (d.max - d.min) * i / 10
                val lbl = if (kotlin.math.abs(v) >= 1000) "${(v / 1000).toInt()}k" else String.format("%.0f", v)
                val lay = tm.measure(lbl, TextStyle(fontSize = (r / 11).coerceIn(9f, 16f).sp, color = tickColor))
                val lr = r * 0.6f
                drawText(lay, topLeft = Offset(c.x + lr * cos(a).toFloat() - lay.size.width / 2, c.y + lr * sin(a).toFloat() - lay.size.height / 2))
            }
        }
        rotate(start + sweep * anim, c) {
            val p = Path().apply {
                moveTo(c.x - r * 0.08f, c.y - r * 0.025f); lineTo(c.x + r * 0.84f, c.y); lineTo(c.x - r * 0.08f, c.y + r * 0.025f); close()
            }
            drawPath(p, d.color)
        }
        drawCircle(onSurface, r * 0.06f, c)
        val txt = tm.measure(d.fmt(d.value), TextStyle(fontSize = (r / 4.2f).coerceIn(16f, 46f).sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = vc))
        drawText(txt, topLeft = Offset(c.x - txt.size.width / 2, c.y + r * 0.22f))
        val u = tm.measure(d.unit, TextStyle(fontSize = (r / 10).coerceIn(10f, 16f).sp, color = tickColor))
        drawText(u, topLeft = Offset(c.x - u.size.width / 2, c.y + r * 0.22f + txt.size.height))
    }
}

@Composable
fun RingGauge(d: GaugeData) {
    val anim by animateFloatAsState(d.fraction, tween(180), label = "ring")
    val track = MaterialTheme.colorScheme.surfaceVariant
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val fontSize = (minOf(maxWidth.value, maxHeight.value) / 5.2f).coerceIn(14f, 40f)
        Canvas(Modifier.fillMaxSize().padding(4.dp)) {
            val s = minOf(size.width, size.height)
            val stroke = s * 0.09f
            val tl = Offset((size.width - s) / 2 + stroke / 2, (size.height - s) / 2 + stroke / 2)
            val sz = Size(s - stroke, s - stroke)
            drawArc(track, 135f, 270f, false, tl, sz, style = Stroke(stroke, cap = StrokeCap.Round))
            drawArc(if (d.alarm) Palette.Red else d.color, 135f, 270f * anim, false, tl, sz, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(d.fmt(d.value), fontSize = fontSize.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = valueColor(d))
            Text(d.unit, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun DigitalGauge(d: GaugeData) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val fontSize = (minOf(maxWidth.value / 4.2f, maxHeight.value / 2f)).coerceIn(18f, 72f)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(d.fmt(d.value), fontSize = fontSize.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = if (d.alarm) Palette.Red else d.color, maxLines = 1)
            Text(d.unit, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun BarGauge(d: GaugeData) {
    val anim by animateFloatAsState(d.fraction, tween(150), label = "bar")
    val track = MaterialTheme.colorScheme.surfaceVariant
    val zeroCentered = d.min < 0 && d.max > 0
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(d.fmt(d.value) + " " + d.unit, fontSize = 22.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = valueColor(d))
        Spacer(Modifier.height(8.dp))
        Canvas(Modifier.fillMaxWidth().height(18.dp)) {
            drawRoundRect(track, cornerRadius = CornerRadius(9f, 9f))
            val color = if (d.alarm) Palette.Red else d.color
            if (zeroCentered) {
                val z = ((0 - d.min) / (d.max - d.min)).toFloat() * size.width
                val x = anim * size.width
                drawRoundRect(color, topLeft = Offset(minOf(z, x), 0f), size = Size(kotlin.math.abs(x - z), size.height), cornerRadius = CornerRadius(9f, 9f))
                drawLine(Color.White.copy(alpha = 0.6f), Offset(z, 0f), Offset(z, size.height), 3f)
            } else drawRoundRect(color, size = Size(size.width * anim, size.height), cornerRadius = CornerRadius(9f, 9f))
            listOfNotNull(d.warnHigh, d.warnLow).forEach { w ->
                val x = ((w - d.min) / (d.max - d.min)).toFloat().coerceIn(0f, 1f) * size.width
                drawLine(Palette.Red, Offset(x, -4f), Offset(x, size.height + 4f), 3f)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(d.fmt(d.min), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(d.fmt(d.max), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun GraphGauge(d: GaugeData, history: Series?) {
    val grid = MaterialTheme.colorScheme.outlineVariant
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(d.fmt(d.value), fontSize = 22.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = valueColor(d))
            Text(" " + d.unit, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Canvas(Modifier.fillMaxWidth().weight(1f)) {
            for (i in 0..3) { val y = size.height * i / 3; drawLine(grid, Offset(0f, y), Offset(size.width, y), 1f) }
            val h = history ?: return@Canvas
            if (h.size < 2) return@Canvas
            val t1 = h.times.last(); val t0 = t1 - 60_000
            val vals = h.values
            val lo = minOf(d.min, vals.min()); val hi = maxOf(d.max, vals.max())
            val span = if (hi - lo < 1e-9) 1.0 else hi - lo
            val p = Path(); var started = false
            for (i in 0 until h.size) {
                if (h.times[i] < t0) continue
                val x = ((h.times[i] - t0).toFloat() / 60_000f) * size.width
                val y = size.height - ((vals[i] - lo) / span).toFloat() * size.height
                if (!started) { p.moveTo(x, y); started = true } else p.lineTo(x, y)
            }
            drawPath(p, d.color, style = Stroke(3f))
        }
    }
}
