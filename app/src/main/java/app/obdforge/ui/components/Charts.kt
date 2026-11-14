package app.obdforge.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.obdforge.core.analysis.Series
import app.obdforge.core.pid.Pids
import app.obdforge.data.UnitSystem
import app.obdforge.ui.theme.Palette
import app.obdforge.util.Units
import java.util.Locale

/** Converts a raw (metric) series into display units. */
fun Series.display(units: UnitSystem): Series {
    if (units == UnitSystem.METRIC) return this
    val ch = Pids.channel(key.substringBefore('#'))
    return Series(key, times, DoubleArray(size) { Units.convert(ch, values[it], units).value })
}

/**
 * Multi-channel time chart. Each channel is scaled to its own range (shown in the legend) so
 * RPM and fuel trims can share one plot; or [sharedAxis] puts them on one Y scale.
 * Pinch to zoom, drag to scroll, tap to place a cursor that reads every channel at that time.
 */
@Composable
fun TimeChart(
    series: List<Series>,
    modifier: Modifier = Modifier,
    height: Dp = 260.dp,
    units: UnitSystem = UnitSystem.METRIC,
    sharedAxis: Boolean = false,
    cursorTime: Long? = null,
    onCursor: ((Long?) -> Unit)? = null,
    followLatest: Boolean = false,
    windowMs: Long? = null,
    labels: List<String>? = null,
) {
    val data = series.filter { it.size > 0 }.map { it.display(units) }
    val tm = rememberTextMeasurer()
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableFloatStateOf(0f) } // fraction of total span from the right edge
    var localCursor by remember { mutableStateOf<Long?>(null) }
    val cursor = cursorTime ?: localCursor
    val grid = MaterialTheme.colorScheme.outlineVariant
    val label = MaterialTheme.colorScheme.onSurfaceVariant
    val onSurface = MaterialTheme.colorScheme.onSurface

    if (data.isEmpty()) {
        EmptyState("No data", "Nothing recorded for the selected channels yet.")
        return
    }
    val tMinAll = data.minOf { it.times.first() }
    val tMaxAll = data.maxOf { it.times.last() }
    val total = (tMaxAll - tMinAll).coerceAtLeast(1)
    val visibleSpan = if (followLatest && windowMs != null) minOf(windowMs, total) else (total / zoom).toLong().coerceAtLeast(500)
    val tEnd = if (followLatest) tMaxAll else (tMaxAll - (pan * total).toLong()).coerceIn(tMinAll + visibleSpan, tMaxAll)
    val tStart = tEnd - visibleSpan

    val ranges = data.map { s ->
        val vis = (0 until s.size).filter { s.times[it] in tStart..tEnd }.map { s.values[it] }
        val src = if (vis.isEmpty()) s.values.toList() else vis
        var lo = src.min(); var hi = src.max()
        if (hi - lo < 1e-6) { lo -= 1; hi += 1 }
        val pad = (hi - lo) * 0.06
        (lo - pad) to (hi + pad)
    }
    val shared = if (sharedAxis) ranges.minOf { it.first } to ranges.maxOf { it.second } else null

    Column(modifier) {
        Canvas(
            Modifier.fillMaxWidth().height(height)
                .pointerInput(followLatest) {
                    if (!followLatest) detectTransformGestures { _, panChange, zoomChange, _ ->
                        zoom = (zoom * zoomChange).coerceIn(1f, 200f)
                        pan = (pan + panChange.x / size.width / zoom).coerceIn(0f, 1f - 1f / zoom)
                    }
                }
                .pointerInput(tStart, tEnd) {
                    detectTapGestures { off ->
                        val t = tStart + ((off.x / size.width) * (tEnd - tStart)).toLong()
                        localCursor = if (localCursor != null && cursorTime == null) null else t
                        onCursor?.invoke(t)
                    }
                }
        ) {
            val w = size.width; val h = size.height
            for (i in 0..4) { val y = h * i / 4; drawLine(grid, Offset(0f, y), Offset(w, y), 1f) }
            for (i in 0..5) { val x = w * i / 5; drawLine(grid, Offset(x, 0f), Offset(x, h), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))) }
            data.forEachIndexed { idx, s ->
                val (lo, hi) = shared ?: ranges[idx]
                val color = Palette.series[idx % Palette.series.size]
                val path = Path(); var started = false
                // Down-sample to at most ~2 points per pixel for speed.
                val step = maxOf(1, (0 until s.size).count { s.times[it] in tStart..tEnd } / (w.toInt() * 2).coerceAtLeast(1))
                var i = 0
                while (i < s.size) {
                    val t = s.times[i]
                    if (t in tStart..tEnd) {
                        val x = ((t - tStart).toFloat() / (tEnd - tStart).coerceAtLeast(1)) * w
                        val y = h - ((s.values[i] - lo) / (hi - lo)).toFloat() * h
                        if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
                    }
                    i += step
                }
                drawPath(path, color, style = Stroke(2.5f))
            }
            if (shared != null) {
                for (i in 0..4) {
                    val v = shared.second - (shared.second - shared.first) * i / 4
                    val lay = tm.measure(String.format(Locale.US, "%.1f", v), TextStyle(fontSize = 10.sp, color = label))
                    drawText(lay, topLeft = Offset(4f, (h * i / 4).coerceAtMost(h - lay.size.height)))
                }
            }
            val secs = (tEnd - tStart) / 1000.0
            val tl = tm.measure(String.format(Locale.US, "%.1f s window · %.1f s", secs, (tEnd - tMinAll) / 1000.0), TextStyle(fontSize = 10.sp, color = label))
            drawText(tl, topLeft = Offset(w - tl.size.width - 4, h - tl.size.height - 2))
            cursor?.let { ct ->
                if (ct in tStart..tEnd) {
                    val x = ((ct - tStart).toFloat() / (tEnd - tStart)) * w
                    drawLine(onSurface, Offset(x, 0f), Offset(x, h), 2f)
                }
            }
        }
        FlowRow(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            data.forEachIndexed { idx, s ->
                val ch = Pids.channel(s.key.substringBefore('#'))
                val color = Palette.series[idx % Palette.series.size]
                val v = cursor?.let { s.at(it) } ?: s.values.lastOrNull()
                val (lo, hi) = ranges[idx]
                val name = labels?.getOrNull(series.indexOfFirst { it.key == s.key }) ?: ch?.shortName ?: s.key
                Text(
                    "● $name: ${v?.let { String.format(Locale.US, "%.${ch?.decimals ?: 1}f", it) } ?: "--"} ${Units.unitLabel(ch, units)}" +
                        if (!sharedAxis) "  [${String.format(Locale.US, "%.0f…%.0f", lo, hi)}]" else "",
                    color = color, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(end = 10.dp, bottom = 2.dp),
                )
            }
        }
        if (!followLatest && zoom > 1.01f) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Zoom ${String.format(Locale.US, "%.1f", zoom)}×", style = MaterialTheme.typography.labelSmall, color = label)
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { zoom = 1f; pan = 0f }) { Text("Reset zoom") }
            }
        }
    }
}

/** X-Y scatter (e.g. boost vs RPM) built by pairing samples by time. */
@Composable
fun XyChart(x: Series, y: Series, modifier: Modifier = Modifier, height: Dp = 260.dp, units: UnitSystem = UnitSystem.METRIC, color: Color = Palette.Orange) {
    val xs = x.display(units); val ys = y.display(units)
    val pts = (0 until ys.size).mapNotNull { i -> xs.at(ys.times[i])?.let { it to ys.values[i] } }
    val tm = rememberTextMeasurer()
    val grid = MaterialTheme.colorScheme.outlineVariant
    val label = MaterialTheme.colorScheme.onSurfaceVariant
    if (pts.size < 3) { EmptyState("Not enough data", "Both channels need overlapping samples."); return }
    val xlo = pts.minOf { it.first }; val xhi = pts.maxOf { it.first }.let { if (it - xlo < 1e-6) xlo + 1 else it }
    val ylo = pts.minOf { it.second }; val yhi = pts.maxOf { it.second }.let { if (it - ylo < 1e-6) ylo + 1 else it }
    val xc = Pids.channel(x.key); val yc = Pids.channel(y.key)
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(height).padding(start = 36.dp, bottom = 18.dp)) {
            val w = size.width; val h = size.height
            for (i in 0..4) {
                val yy = h * i / 4; drawLine(grid, Offset(0f, yy), Offset(w, yy), 1f)
                val xx = w * i / 4; drawLine(grid, Offset(xx, 0f), Offset(xx, h), 1f)
                val yl = tm.measure(String.format(Locale.US, "%.0f", yhi - (yhi - ylo) * i / 4), TextStyle(fontSize = 10.sp, color = label))
                drawText(yl, topLeft = Offset(-yl.size.width - 6f, (yy - yl.size.height / 2).coerceIn(0f, h - yl.size.height)))
                val xl = tm.measure(String.format(Locale.US, "%.0f", xlo + (xhi - xlo) * i / 4), TextStyle(fontSize = 10.sp, color = label))
                drawText(xl, topLeft = Offset((xx - xl.size.width / 2).coerceIn(0f, w - xl.size.width), h + 2f))
            }
            for ((px, py) in pts) {
                drawCircle(color.copy(alpha = 0.55f), 3.5f, Offset(((px - xlo) / (xhi - xlo)).toFloat() * w, h - ((py - ylo) / (yhi - ylo)).toFloat() * h))
            }
        }
        Text("X: ${xc?.name ?: x.key} (${Units.unitLabel(xc, units)})   Y: ${yc?.name ?: y.key} (${Units.unitLabel(yc, units)}) · ${pts.size} points",
            style = MaterialTheme.typography.labelSmall, color = label)
    }
}
