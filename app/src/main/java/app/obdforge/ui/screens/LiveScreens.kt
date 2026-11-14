package app.obdforge.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import app.obdforge.core.analysis.Series
import app.obdforge.core.pid.Channel
import app.obdforge.core.pid.Pids
import app.obdforge.report.ChartImage
import app.obdforge.report.Share
import app.obdforge.ui.components.Chip
import app.obdforge.ui.components.EmptyState
import app.obdforge.ui.components.InfoNote
import app.obdforge.ui.components.RequireChannels
import app.obdforge.ui.components.SectionCard
import app.obdforge.ui.components.SimBadge
import app.obdforge.ui.components.TimeChart
import app.obdforge.ui.components.graph
import app.obdforge.ui.theme.Palette
import app.obdforge.util.Units
import kotlinx.coroutines.delay

/** Every channel the connected vehicle supports, plus calculated values. */
@Composable
fun LiveListScreen(nav: NavHostController) {
    val g = graph()
    val values by g.live.values.collectAsStateWithLifecycle()
    val unavailable by g.live.unavailable.collectAsStateWithLifecycle()
    val settings by g.settings.state.collectAsStateWithLifecycle()
    val rate by g.live.rateHz.collectAsStateWithLifecycle()
    val link by g.connection.state.collectAsStateWithLifecycle()
    val report = g.connection.report
    var pinned by remember { mutableStateOf(g.settings.pinned()) }
    var pinnedOnly by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<String>() }

    val channels = remember(report) {
        val sup = report?.supportedPids ?: emptySet()
        Pids.channels.filter { c ->
            when {
                c.source == Channel.Source.ECU && c.pid >= 0 -> c.pid in sup
                c.key.startsWith("misfire_") -> false
                c.key == "gps_speed" -> false
                else -> report != null
            }
        }
    }
    val shown = if (pinnedOnly) channels.filter { it.key in pinned } else channels
    RequireChannels("livelist", shown.map { it.key })

    ListScreen("Live data", nav, actions = {
        if (g.connection.isSimulated) SimBadge()
        if (selected.isNotEmpty()) TextButton(onClick = { nav.navigate("livegraph?keys=${selected.joinToString(",")}") }) { Text("Graph ${selected.size}") }
    }) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (report == null) "Not connected" else "${shown.size} channels · ${String.format("%.1f", rate)} cycles/s",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                FilterChip(pinnedOnly, { pinnedOnly = !pinnedOnly }, label = { Text("Pinned only") })
            }
            if (report != null && !pinnedOnly && shown.size > 15) InfoNote("Polling many channels lowers the refresh rate of each. Pin your favourites and use “Pinned only” for faster updates.")
        }
        if (report == null) item { EmptyState("Not connected", "Connect to an adapter or the simulator to see live data.") }
        val grouped = shown.groupBy { it.category }
        grouped.forEach { (cat, list) ->
            item(key = "h_${cat.name}") { Text(cat.label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp)) }
            items(list, key = { it.key }) { c ->
                val lv = values[c.key]
                val dead = c.key in unavailable
                SectionCard(modifier = Modifier.clickable { nav.navigate("livegraph?keys=${c.key}") }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(c.key in selected, { if (it) selected.add(c.key) else selected.remove(c.key) })
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(c.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f, fill = false))
                                if (c.source != Channel.Source.ECU) { Spacer(Modifier.width(6.dp)); Chip(c.source.badge, Palette.Purple) }
                            }
                            if (lv != null) Text("min ${Units.format(c, lv.min, settings.units, false)} · avg ${Units.format(c, lv.avg, settings.units, false)} · max ${Units.format(c, lv.max, settings.units, false)}",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(if (dead) "no answer" else Units.format(c, lv?.value, settings.units), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                            color = if (dead) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                        IconButton(onClick = { pinned = if (c.key in pinned) pinned - c.key else pinned + c.key; g.settings.setPinned(pinned) }) {
                            Icon(if (c.key in pinned) Icons.Filled.Star else Icons.Filled.StarBorder, "Pin", tint = if (c.key in pinned) Palette.Amber else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        if (report != null) item {
            SectionCard("Status PIDs") {
                val texts = report.supportedPids.sorted().mapNotNull { p -> Pids[p]?.takeIf { it.text != null && it.channels.isEmpty() } }
                if (texts.isEmpty()) Text("None", color = MaterialTheme.colorScheme.onSurfaceVariant)
                texts.forEach { Text("${it.name} (PID ${it.pid.toString(16).uppercase()})", style = MaterialTheme.typography.bodySmall) }
                InfoNote("Status values such as fuel system status and readiness are shown on the Diagnose screen.")
            }
        }
        if (link !is app.obdforge.connection.LinkState.Connected && values.isNotEmpty()) item { Text("Values are from before the connection stopped.", color = Palette.Amber) }
    }
}

@Composable
fun LiveGraphScreen(nav: NavHostController, initialKeys: List<String>) {
    val g = graph()
    val ctx = LocalContext.current
    val settings by g.settings.state.collectAsStateWithLifecycle()
    val keys = remember { mutableStateListOf<String>().apply { addAll(initialKeys) } }
    var window by remember { mutableLongStateOf(60_000L) }
    var paused by remember { mutableStateOf(false) }
    var shared by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var tick by remember { mutableLongStateOf(0L) }
    var frozen by remember { mutableStateOf<List<Series>>(emptyList()) }
    RequireChannels("livegraph", keys.toList())
    LaunchedEffect(paused) { while (!paused) { delay(250); tick++ } }
    val series = if (paused) frozen else keys.map { g.live.history(it, System.currentTimeMillis() - 10 * 60_000) }.also { tick.let { } }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar("Live graph", nav) {
            IconButton(onClick = { if (!paused) frozen = keys.map { g.live.history(it) }; paused = !paused }) { Icon(if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, "Pause") }
            IconButton(onClick = { picking = true }) { Icon(Icons.Filled.Add, "Add channel") }
            IconButton(onClick = {
                val f = ChartImage.render(ctx, series, "Live " + keys.joinToString(", ") { Pids.channel(it)?.shortName ?: it })
                Share.share(ctx, f, "image/png", "Share graph")
            }) { Icon(Icons.Filled.Share, "Export image") }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(30_000L to "30 s", 60_000L to "1 min", 300_000L to "5 min").forEach { (w, l) -> FilterChip(window == w, { window = w }, label = { Text(l) }) }
                FilterChip(shared, { shared = !shared }, label = { Text("Shared axis") })
            }
            SectionCard {
                TimeChart(series, height = 320.dp, units = settings.units, sharedAxis = shared, followLatest = !paused, windowMs = window)
            }
            if (paused) InfoNote("Paused: pinch to zoom, drag to scroll, tap to read values at a moment.")
            SectionCard("Channels") {
                keys.toList().forEach { k ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(Pids.channel(k)?.name ?: k, modifier = Modifier.weight(1f))
                        TextButton(onClick = { keys.remove(k) }) { Text("Remove") }
                    }
                }
            }
        }
    }
    if (picking) ChannelPicker(onPick = { if (it !in keys) keys.add(it); picking = false }, onDismiss = { picking = false })
}
