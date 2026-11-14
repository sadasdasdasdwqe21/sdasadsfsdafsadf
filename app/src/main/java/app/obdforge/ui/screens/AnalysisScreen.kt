package app.obdforge.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import app.obdforge.AppGraph
import app.obdforge.core.analysis.EngineAnalysis
import app.obdforge.core.analysis.Finding
import app.obdforge.core.analysis.Series
import app.obdforge.core.analysis.TrimAnalysis
import app.obdforge.core.live.VehicleSpec
import app.obdforge.ui.components.Busy
import app.obdforge.ui.components.Chip
import app.obdforge.ui.components.InfoNote
import app.obdforge.ui.components.RequireChannels
import app.obdforge.ui.components.SectionCard
import app.obdforge.ui.components.graph
import app.obdforge.ui.components.levelColor
import app.obdforge.ui.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private val analysisChannels = listOf("rpm", "speed", "load", "tps", "stft1", "ltft1", "stft2", "ltft2", "maf", "map", "baro", "iat", "ambient", "ect",
    "boost", "boost_cmd_a", "boost_act_a", "frp", "fuel_rate", "fuel_rate_est", "lambda_cmd", "ecu_voltage", "vol_eff", "trim_total1", "o2_b1s1_v")

private data class AnalysisResult(val trims: TrimAnalysis, val sections: Map<String, List<Finding>>, val samples: Int, val spanS: Double)

@Composable
fun AnalysisScreen(nav: NavHostController, sessionId: Long?) {
    val g = graph()
    val sessions by g.db.sessions().all().collectAsStateWithLifecycle(initialValue = emptyList())
    val vehicle by g.activeVehicle.collectAsStateWithLifecycle()
    val diag by g.diagnostics.state.collectAsStateWithLifecycle()
    var source by remember { mutableStateOf(sessionId) } // null = live buffer
    var result by remember { mutableStateOf<AnalysisResult?>(null) }
    var busy by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    if (source == null) RequireChannels("analysis", analysisChannels)

    fun run() {
        busy = true
        scope.launch {
            result = withContext(Dispatchers.Default) {
                val data: Map<String, Series> = if (source != null) g.recorder.load(source!!)
                else analysisChannels.associateWith { g.live.history(it, System.currentTimeMillis() - 15 * 60_000) }.filterValues { it.size > 0 }
                val spec = vehicle?.let { AppGraph.specOf(it) } ?: VehicleSpec()
                val ea = EngineAnalysis(data, spec)
                val trims = ea.fuelTrims()
                AnalysisResult(trims, linkedMapOf(
                    "Fuel trims" to trims.findings, "Airflow" to ea.airflow(), "Boost" to ea.boost(), "Misfire" to ea.misfires(diag.mode06),
                    "Cooling" to ea.cooling(), "Fuel system" to ea.fuelSystem(), "Electrical" to ea.electrical(), "Anomalies" to ea.anomalies(),
                ), data.values.sumOf { it.size }, (data.values.maxOfOrNull { it.times.lastOrNull() ?: 0 } ?: 0).minus(data.values.minOfOrNull { it.times.firstOrNull() ?: 0 } ?: 0) / 1000.0)
            }
            busy = false
        }
    }
    LaunchedEffect(source) { if (source != null) run() }

    ListScreen("Engine analysis", nav) {
        item {
            SectionCard("Data source") {
                Box {
                    OutlinedButton(onClick = { open = true }) { Text(source?.let { id -> sessions.firstOrNull { it.id == id }?.title ?: "Recording $id" } ?: "Live data (last 15 min)") }
                    DropdownMenu(open, { open = false }) {
                        DropdownMenuItem(text = { Text("Live data (last 15 min)") }, onClick = { source = null; open = false; result = null })
                        sessions.take(30).forEach { s -> DropdownMenuItem(text = { Text(s.title + if (s.simulated) " (sim)" else "") }, onClick = { source = s.id; open = false }) }
                    }
                }
                Button(onClick = { run() }, enabled = !busy) { Text("Analyse") }
                if (busy) Busy("Analysing…")
                InfoNote("For the best fuel trim analysis, include warm idle and steady cruising in the same data. The analysis describes what the data shows and what it may point to; it does not confirm faults.")
                result?.let { Text("${it.samples} samples over ${String.format(Locale.US, "%.0f", it.spanS)} s", style = MaterialTheme.typography.labelSmall) }
            }
        }
        result?.let { r ->
            if (r.trims.bank1.isNotEmpty()) item {
                SectionCard("Fuel trim by condition") {
                    Row { listOf("Condition", "Bank", "STFT", "LTFT", "Total", "n").forEachIndexed { i, h -> Text(h, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(if (i == 0) 2f else 1f)) } }
                    (r.trims.bank1.map { 1 to it } + r.trims.bank2.map { 2 to it }).forEach { (b, t) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(t.condition, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(2f))
                            Text("B$b", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            listOf(t.stft, t.ltft, t.total).forEach { v -> Text(String.format(Locale.US, "%+.1f", v), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                                color = if (kotlin.math.abs(v) > 10) Palette.Amber else MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f)) }
                            Text(t.samples.toString(), style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
            r.sections.forEach { (title, findings) ->
                if (findings.isNotEmpty()) item(key = title) {
                    SectionCard(title) {
                        findings.forEach { f ->
                            Column(Modifier.padding(vertical = 4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Chip(f.level.label, levelColor(f.level)); if (f.estimated) { Text(" ", modifier = Modifier.width(4.dp)); Chip("estimate", Palette.Purple) }
                                }
                                Text(f.title, style = MaterialTheme.typography.titleSmall)
                                if (f.detail.isNotBlank()) Text(f.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            item { OutlinedButton(onClick = { nav.navigate("assistant") }) { Text("Open the diagnostic assistant") } }
        }
    }
}

