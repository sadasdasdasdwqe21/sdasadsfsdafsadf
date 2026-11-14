package app.obdforge.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import app.obdforge.AppGraph
import app.obdforge.core.analysis.EngineAnalysis
import app.obdforge.core.analysis.Series
import app.obdforge.core.analysis.Stats
import app.obdforge.core.export.Exporters
import app.obdforge.core.export.SessionExport
import app.obdforge.core.pid.Pids
import app.obdforge.data.SessionEntity
import app.obdforge.report.ChartImage
import app.obdforge.report.PdfReport
import app.obdforge.report.Share
import app.obdforge.ui.components.Busy
import app.obdforge.ui.components.Chip
import app.obdforge.ui.components.EmptyState
import app.obdforge.ui.components.InfoNote
import app.obdforge.ui.components.SectionCard
import app.obdforge.ui.components.SimBadge
import app.obdforge.ui.components.TimeChart
import app.obdforge.ui.components.XyChart
import app.obdforge.ui.components.graph
import app.obdforge.ui.theme.Palette
import app.obdforge.util.Units
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun dur(ms: Long): String { val s = ms / 1000; return if (s >= 3600) "${s / 3600}h ${s % 3600 / 60}m" else "${s / 60}m ${s % 60}s" }

@Composable
fun SessionsScreen(nav: NavHostController) {
    val g = graph()
    val sessions by g.db.sessions().all().collectAsStateWithLifecycle(initialValue = emptyList())
    val vehicles by g.vehicles.collectAsStateWithLifecycle()
    val rec by g.recorder.state.collectAsStateWithLifecycle()
    var vehicleFilter by remember { mutableStateOf<Long?>(null) }
    val selected = remember { mutableStateListOf<Long>() }
    val scope = rememberCoroutineScope()
    val fmt = remember { SimpleDateFormat("EEE d MMM yyyy", Locale.getDefault()) }
    val tfmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val shown = sessions.filter { vehicleFilter == null || it.vehicleId == vehicleFilter }
    ListScreen("Recordings", nav, actions = {
        if (selected.size >= 2) TextButton(onClick = { nav.navigate("compare/${selected.joinToString(",")}") }) { Text("Compare ${selected.size}") }
    }) {
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(vehicleFilter == null, { vehicleFilter = null }, label = { Text("All vehicles") })
                vehicles.forEach { v -> FilterChip(vehicleFilter == v.id, { vehicleFilter = v.id }, label = { Text(v.name) }) }
            }
            rec?.let { r -> SectionCard { Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Recording now: ${r.title} (${r.samples} samples)", modifier = Modifier.weight(1f), color = Palette.Red)
                OutlinedButton(onClick = { scope.launch { g.recorder.stop() } }) { Text("Stop") }
            } } }
            InfoNote("Select two or more recordings to compare them. All recordings stay available without a connection.")
        }
        if (shown.isEmpty()) item { EmptyState("No recordings", "Start a recording from Home or a dashboard while connected.") }
        val byDay = shown.groupBy { fmt.format(Date(it.startedAt)) }
        byDay.forEach { (day, list) ->
            item(key = "d_$day") { Text(day, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
            items(list, key = { it.id }) { s ->
                SectionCard(modifier = Modifier.clickable { nav.navigate("session/${s.id}") }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(s.id in selected, { if (it) selected.add(s.id) else selected.remove(s.id) })
                        Column(Modifier.weight(1f)) {
                            Text(s.title, style = MaterialTheme.typography.titleSmall)
                            Text("${tfmt.format(Date(s.startedAt))} · ${dur((s.endedAt ?: System.currentTimeMillis()) - s.startedAt)} · ${s.sampleCount} samples · " +
                                (vehicles.firstOrNull { it.id == s.vehicleId }?.name ?: "no vehicle"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (s.description.isNotBlank()) Text(s.description, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                        }
                        if (s.simulated) SimBadge()
                        if (s.status == "RECOVERED") Chip("recovered", Palette.Amber)
                        if (s.status == "RECORDING") Chip("live", Palette.Red)
                    }
                }
            }
        }
    }
}

private data class Loaded(val session: SessionEntity, val data: Map<String, Series>)

@Composable
fun SessionDetailScreen(nav: NavHostController, id: Long) {
    val g = graph()
    val ctx = LocalContext.current
    val settings by g.settings.state.collectAsStateWithLifecycle()
    val vehicles by g.vehicles.collectAsStateWithLifecycle()
    var loaded by remember { mutableStateOf<Loaded?>(null) }
    var title by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    val chosen = remember { mutableStateListOf<String>() }
    var cursor by remember { mutableStateOf<Long?>(null) }
    var playing by remember { mutableStateOf(false) }
    var speed by remember { mutableFloatStateOf(4f) }
    var xKey by remember { mutableStateOf("rpm") }
    var yKey by remember { mutableStateOf("boost") }
    var busy by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var shared by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(id) {
        val s = withContext(Dispatchers.IO) { g.db.sessions().get(id) } ?: return@LaunchedEffect
        val data = withContext(Dispatchers.IO) { g.recorder.load(id) }
        loaded = Loaded(s, data); title = s.title; desc = s.description
        chosen.clear(); chosen.addAll(listOf("rpm", "speed", "boost", "ltft1").filter { it in data }.ifEmpty { data.keys.take(3) })
        if (xKey !in data) xKey = data.keys.firstOrNull() ?: "rpm"
        if (yKey !in data) yKey = data.keys.drop(1).firstOrNull() ?: xKey
    }
    val l = loaded
    val start = l?.data?.values?.minOfOrNull { it.times.firstOrNull() ?: 0 } ?: 0L
    val end = l?.data?.values?.maxOfOrNull { it.times.lastOrNull() ?: 0 } ?: 0L
    LaunchedEffect(playing, speed) {
        while (playing) {
            delay(100)
            val next = (cursor ?: start) + (100 * speed).toLong()
            if (next >= end) { cursor = end; playing = false } else cursor = next
        }
    }

    fun export(kind: String) {
        val s = l ?: return
        busy = "Exporting $kind…"
        scope.launch {
            val vname = vehicles.firstOrNull { it.id == s.session.vehicleId }?.name ?: "Vehicle"
            val e = SessionExport(title, vname, s.session.startedAt, desc, s.session.simulated, s.data)
            val f = withContext(Dispatchers.IO) {
                val base = File(Share.exportDir(ctx), "OBDForge_${Share.safeName(title)}")
                when (kind) {
                    "CSV" -> File(base.path + ".csv").also { f -> f.outputStream().use { Exporters.csv(e, it) } }
                    "JSON" -> File(base.path + ".json").also { f -> f.writeText(Exporters.json(e)) }
                    "Excel" -> File(base.path + ".xlsx").also { f -> f.outputStream().use { Exporters.xlsx(e, it) } }
                    "PDF" -> PdfReport.session(ctx, title, vname, s.session.startedAt, desc, s.session.simulated, s.data,
                        EngineAnalysis(s.data, vehicles.firstOrNull { it.id == s.session.vehicleId }?.let { AppGraph.specOf(it) } ?: app.obdforge.core.live.VehicleSpec()).all())
                    else -> ChartImage.render(ctx, chosen.mapNotNull { s.data[it] }, title)
                }
            }
            busy = null
            val mime = when (kind) { "CSV" -> "text/csv"; "JSON" -> "application/json"; "Excel" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"; "PDF" -> "application/pdf"; else -> "image/png" }
            Share.share(ctx, f, mime, "Export recording")
        }
    }

    ListScreen(title.ifBlank { "Recording" }, nav, actions = {
        if (l?.session?.simulated == true) SimBadge()
        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Delete") }
    }) {
        if (l == null) { item { Busy("Loading…") }; return@ListScreen }
        item {
            SectionCard("Details") {
                OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(desc, { desc = it }, label = { Text("Description / notes") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${SimpleDateFormat("d MMM yyyy HH:mm", Locale.getDefault()).format(Date(l.session.startedAt))} · ${dur(end - start)} · ${l.data.values.sumOf { it.size }} samples",
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { scope.launch(Dispatchers.IO) { g.db.sessions().update(l.session.copy(title = title, description = desc)) } }) { Text("Save") }
                }
                if (l.session.status == "RECOVERED") InfoNote("The app was closed while this was recording; everything written before that is kept.", Palette.Amber)
            }
        }
        item {
            SectionCard("Channels") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    l.data.keys.sortedBy { Pids.channel(it)?.category?.ordinal ?: 99 }.forEach { k ->
                        FilterChip(k in chosen, { if (k in chosen) chosen.remove(k) else chosen.add(k) }, label = { Text(Pids.channel(k)?.shortName ?: k) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(shared, { shared = !shared }, label = { Text("Shared axis") })
                }
                TimeChart(chosen.mapNotNull { l.data[it] }, units = settings.units, sharedAxis = shared, cursorTime = cursor, onCursor = { cursor = it; playing = false })
            }
        }
        item {
            SectionCard("Replay") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { if (cursor == null || cursor!! >= end) cursor = start; playing = !playing }) { Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Play") }
                    listOf(1f, 4f, 16f).forEach { s -> FilterChip(speed == s, { speed = s }, label = { Text("${s.toInt()}×") }, modifier = Modifier.padding(end = 4.dp)) }
                }
                Slider(value = (((cursor ?: start) - start).toFloat() / (end - start).coerceAtLeast(1)).coerceIn(0f, 1f),
                    onValueChange = { cursor = start + (it * (end - start)).toLong(); playing = false })
                val t = cursor ?: start
                Text("t = ${String.format(Locale.US, "%.1f", (t - start) / 1000.0)} s", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    chosen.forEach { k ->
                        val c = Pids.channel(k)
                        Column {
                            Text(Units.format(c, l.data[k]?.at(t), settings.units, false), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                            Text("${c?.shortName ?: k} ${Units.unitLabel(c, settings.units)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        item {
            SectionCard("X–Y plot") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KeyDropdown("X", xKey, l.data.keys.toList()) { xKey = it }
                    KeyDropdown("Y", yKey, l.data.keys.toList()) { yKey = it }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("RPM vs boost" to ("rpm" to "boost"), "Throttle vs load" to ("tps" to "load"), "STFT vs LTFT" to ("stft1" to "ltft1"),
                        "Coolant vs load" to ("load" to "ect"), "RPM vs MAF" to ("rpm" to "maf")).forEach { (lbl, p) ->
                        if (p.first in l.data && p.second in l.data) OutlinedButton(onClick = { xKey = p.first; yKey = p.second }) { Text(lbl) }
                    }
                }
                val xs = l.data[xKey]; val ys = l.data[yKey]
                if (xs != null && ys != null) XyChart(xs, ys, units = settings.units)
            }
        }
        item {
            SectionCard("Statistics") {
                Row { listOf("Channel" to 2f, "Min" to 1f, "Max" to 1f, "Mean" to 1f, "Median" to 1f, "SD" to 1f).forEach { (h, w) -> Text(h, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(w)) } }
                l.data.forEach { (k, s) ->
                    val st = Stats.describe(s); val c = Pids.channel(k)
                    Row {
                        Text(c?.shortName ?: k, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(2f))
                        listOf(st.min, st.max, st.mean, st.median, st.stdDev).forEach { v -> Text(Units.format(c, v, settings.units, false), fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.weight(1f)) }
                    }
                }
                chosen.firstOrNull()?.let { k ->
                    val s = l.data[k]!!; val st = Stats.describe(s); val c = Pids.channel(k)
                    Spacer(Modifier.width(4.dp))
                    Text("${c?.name ?: k}: rate of change avg ${String.format(Locale.US, "%.2f", st.meanRateOfChange)}/s, max ${String.format(Locale.US, "%.2f", st.maxRateOfChange)}/s; peaks at " +
                        st.peaks.take(3).joinToString { "${String.format(Locale.US, "%.1f", (it.first - start) / 1000.0)} s" }, style = MaterialTheme.typography.bodySmall)
                    c?.let { ch ->
                        val thr = (ch.min + ch.max) / 2
                        Text("Time above ${String.format(Locale.US, "%.0f", thr)} ${ch.unit}: ${Stats.durationBeyond(s, thr, true) / 1000} s", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        item {
            SectionCard("Export & analyse") {
                busy?.let { Busy(it) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("CSV", "JSON", "Excel", "PDF", "Image").forEach { k -> OutlinedButton(onClick = { export(k) }, enabled = busy == null) { Text(k) } }
                }
                OutlinedButton(onClick = { nav.navigate("analysis?session=$id") }) { Text("Engine analysis of this recording") }
            }
        }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Delete this recording?") },
        text = { Text("The recording and all its samples are removed from this phone.") },
        confirmButton = { TextButton(onClick = {
            scope.launch { withContext(Dispatchers.IO) { g.db.sessions().deleteSamples(id); g.db.sessions().deleteSession(id) }; nav.popBackStack() }
            confirmDelete = false
        }) { Text("Delete", color = Palette.Red) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } })
}

@Composable
fun KeyDropdown(label: String, key: String, keys: List<String>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) { Text("$label: ${Pids.channel(key)?.shortName ?: key}") }
        DropdownMenu(open, { open = false }) {
            keys.forEach { k -> DropdownMenuItem(text = { Text(Pids.channel(k)?.name ?: k) }, onClick = { onPick(k); open = false }) }
        }
    }
}

@Composable
fun CompareScreen(nav: NavHostController, ids: List<Long>) {
    val g = graph()
    val settings by g.settings.state.collectAsStateWithLifecycle()
    var sessions by remember { mutableStateOf<List<Loaded>>(emptyList()) }
    var key by remember { mutableStateOf("rpm") }
    LaunchedEffect(ids) {
        sessions = withContext(Dispatchers.IO) { ids.mapNotNull { i -> g.db.sessions().get(i)?.let { Loaded(it, g.recorder.load(i)) } } }
        val common = sessions.map { it.data.keys }.reduceOrNull { a, b -> a intersect b } ?: emptySet()
        if (key !in common) key = common.firstOrNull() ?: key
    }
    val keys = sessions.flatMap { it.data.keys }.distinct()
    ListScreen("Compare recordings", nav) {
        item {
            SectionCard {
                KeyDropdown("Channel", key, keys) { key = it }
                InfoNote("Each recording is aligned to its own start (t = 0).")
            }
        }
        item {
            SectionCard {
                val series = sessions.mapIndexedNotNull { i, s ->
                    s.data[key]?.let { sr -> val t0 = sr.times.firstOrNull() ?: 0; Series("$key#$i", LongArray(sr.size) { sr.times[it] - t0 }, sr.values) }
                }
                TimeChart(series, units = settings.units, sharedAxis = true, labels = sessions.filter { it.data.containsKey(key) }.map { it.session.title })
            }
        }
        item {
            SectionCard("Statistics") {
                sessions.forEachIndexed { i, s ->
                    val sr = s.data[key] ?: return@forEachIndexed
                    val st = Stats.describe(sr); val c = Pids.channel(key)
                    Text(s.session.title + if (s.session.simulated) " (simulated)" else "", color = Palette.series[i % Palette.series.size], style = MaterialTheme.typography.titleSmall)
                    Text("min ${Units.format(c, st.min, settings.units)} · max ${Units.format(c, st.max, settings.units)} · mean ${Units.format(c, st.mean, settings.units)} · sd ${String.format(Locale.US, "%.2f", st.stdDev)}",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
