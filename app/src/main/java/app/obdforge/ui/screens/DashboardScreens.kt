package app.obdforge.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import app.obdforge.core.mfr.Profiles
import app.obdforge.core.pid.Channel
import app.obdforge.core.pid.Pids
import app.obdforge.data.Dashboard
import app.obdforge.data.DashboardCodec
import app.obdforge.data.DashboardEntity
import app.obdforge.data.GaugeConfig
import app.obdforge.data.GaugeStyle
import app.obdforge.ui.components.Chip
import app.obdforge.ui.components.EmptyState
import app.obdforge.ui.components.GaugeView
import app.obdforge.ui.components.InfoNote
import app.obdforge.ui.components.RequireChannels
import app.obdforge.ui.components.SectionCard
import app.obdforge.ui.components.SimBadge
import app.obdforge.ui.components.display
import app.obdforge.ui.components.gaugeData
import app.obdforge.ui.components.graph
import app.obdforge.ui.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun DashboardsScreen(nav: NavHostController) {
    val g = graph()
    val list by g.db.misc().dashboards().collectAsStateWithLifecycle(initialValue = emptyList())
    val vehicle by g.activeVehicle.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf<DashboardEntity?>(null) }
    ListScreen("Dashboards", nav, actions = {
        TextButton(onClick = { nav.navigate("live") }) { Text("All PIDs") }
        IconButton(onClick = {
            scope.launch(Dispatchers.IO) {
                val id = g.db.misc().insertDashboard(DashboardEntity(name = "My dashboard ${list.size + 1}", position = list.size, json = DashboardCodec.encode(2, listOf(GaugeConfig("rpm"), GaugeConfig("speed")))))
                withContext(Dispatchers.Main) { nav.navigate("dashboard_edit/$id") }
            }
        }) { Icon(Icons.Filled.Add, "New dashboard") }
    }) {
        item {
            val profile = Profiles.byId(vehicle?.profileId)
            if (profile != null && profile.id != "generic") SectionCard {
                Text("Preset for ${profile.title}", style = MaterialTheme.typography.titleSmall)
                OutlinedButton(onClick = {
                    scope.launch(Dispatchers.IO) {
                        val id = g.db.misc().insertDashboard(DashboardEntity(name = profile.title, position = list.size,
                            json = DashboardCodec.encode(2, DashboardCodec.profilePreset(profile.title, profile.recommendedChannels))))
                        withContext(Dispatchers.Main) { nav.navigate("dashboard/$id") }
                    }
                }) { Text("Create dashboard") }
            }
        }
        items(list, key = { it.id }) { e ->
            val d = DashboardCodec.decode(e)
            SectionCard(modifier = Modifier.clickable { nav.navigate("dashboard/${e.id}") }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(d.name, style = MaterialTheme.typography.titleMedium)
                        Text(d.gauges.joinToString(" · ") { Pids.channel(it.channel)?.shortName ?: it.channel }, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                    }
                    IconButton(onClick = { nav.navigate("dashboard_edit/${e.id}") }) { Icon(Icons.Filled.Edit, "Edit") }
                    IconButton(onClick = { confirmDelete = e }) { Icon(Icons.Filled.Delete, "Delete") }
                }
            }
        }
    }
    confirmDelete?.let { e ->
        AlertDialog(onDismissRequest = { confirmDelete = null }, title = { Text("Delete \"${e.name}\"?") },
            confirmButton = { TextButton(onClick = { scope.launch(Dispatchers.IO) { g.db.misc().deleteDashboard(e.id) }; confirmDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } })
    }
}

@Composable
fun DashboardScreen(nav: NavHostController, id: Long) {
    val g = graph()
    val all by g.db.misc().dashboards().collectAsStateWithLifecycle(initialValue = emptyList())
    val entity = all.firstOrNull { it.id == id }
    val d = entity?.let { DashboardCodec.decode(it) }
    val values by g.live.values.collectAsStateWithLifecycle()
    val unavailable by g.live.unavailable.collectAsStateWithLifecycle()
    val settings by g.settings.state.collectAsStateWithLifecycle()
    val rec by g.recorder.state.collectAsStateWithLifecycle()
    val rate by g.live.rateHz.collectAsStateWithLifecycle()
    val err by g.live.lastError.collectAsStateWithLifecycle()
    val report = g.connection.report
    val scope = rememberCoroutineScope()
    val keys = d?.gauges?.map { it.channel }?.toSet() ?: emptySet()
    RequireChannels("dash:$id", keys)
    val cols = if (LocalConfiguration.current.screenWidthDp >= 600) 4 else 2

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(d?.name ?: "Dashboard", nav) {
            if (g.connection.isSimulated) SimBadge()
            IconButton(onClick = { g.live.resetStats() }) { Icon(Icons.Filled.RestartAlt, "Reset min/max") }
            IconButton(onClick = {
                scope.launch { if (rec == null) g.recorder.start(null, null, keys + g.recorder.defaultChannels()) else g.recorder.stop() }
            }) { Icon(if (rec == null) Icons.Filled.FiberManualRecord else Icons.Filled.Stop, "Record", tint = Palette.Red) }
            IconButton(onClick = { nav.navigate("dashboard_edit/$id") }) { Icon(Icons.Filled.Edit, "Edit") }
        }
        Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (report == null) "Not connected" else String.format("%.1f updates/s", rate), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            err?.let { Text(it, color = Palette.Amber, style = MaterialTheme.typography.labelSmall) }
        }
        if (d == null) { EmptyState("Dashboard not found", ""); return@Column }
        if (d.gauges.isEmpty()) { EmptyState("No gauges", "Tap edit to add gauges.") ; return@Column }
        LazyVerticalGrid(GridCells.Fixed(cols), contentPadding = PaddingValues(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {
            itemsIndexed(d.gauges, span = { _, cfg -> GridItemSpan(if (cfg.span >= 2) minOf(cols, if (cols == 4) 2 else 2) else 1) }) { _, cfg ->
                val ch = Pids.channel(cfg.channel)
                val unsupported = report != null && (cfg.channel in unavailable || (ch != null && ch.pid >= 0 && ch.source == Channel.Source.ECU && ch.pid !in report.supportedPids))
                val data = gaugeData(cfg, ch, values[cfg.channel], settings.units, unsupported)
                val hist = if (cfg.style == GaugeStyle.GRAPH) g.live.history(cfg.channel, System.currentTimeMillis() - 60_000).display(settings.units) else null
                GaugeView(cfg.style, data, hist, Modifier.fillMaxWidth().height((150 * cfg.height + if (cfg.height > 1) 40 else 0).dp)
                    .clickable { nav.navigate("livegraph?keys=${cfg.channel}") }, cfg.showStats)
            }
        }
    }
}

@Composable
fun DashboardEditScreen(nav: NavHostController, id: Long) {
    val g = graph()
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    val gauges = remember { mutableStateListOf<GaugeConfig>() }
    var loaded by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var entity by remember { mutableStateOf<DashboardEntity?>(null) }
    LaunchedEffect(id) {
        val e = withContext(Dispatchers.IO) { g.db.misc().dashboardList().firstOrNull { it.id == id } }
        if (e != null) { entity = e; val d = DashboardCodec.decode(e); name = d.name; gauges.clear(); gauges.addAll(d.gauges) }
        loaded = true
    }
    fun save() {
        val e = entity ?: return
        scope.launch(Dispatchers.IO) { g.db.misc().updateDashboard(e.copy(name = name, json = DashboardCodec.encode(2, gauges.toList()))) }
        nav.popBackStack()
    }
    Column(Modifier.fillMaxSize()) {
        ScreenTopBar("Edit dashboard", nav) { TextButton(onClick = { save() }) { Text("Save") } }
        if (!loaded) return@Column
        LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
            itemsIndexed(gauges) { i, cfg ->
                GaugeEditor(cfg, onChange = { gauges[i] = it },
                    onUp = { if (i > 0) { val t = gauges[i - 1]; gauges[i - 1] = gauges[i]; gauges[i] = t } },
                    onDown = { if (i < gauges.size - 1) { val t = gauges[i + 1]; gauges[i + 1] = gauges[i]; gauges[i] = t } },
                    onDelete = { gauges.removeAt(i) })
            }
            item { Button(onClick = { picking = true }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Filled.Add, null); Text("Add gauge") } }
            item { InfoNote("Gauges for values your car does not report show “Not supported” instead of a made-up number.") }
        }
    }
    if (picking) ChannelPicker(onPick = { k -> gauges.add(GaugeConfig(k, color = colorLong(Palette.series[gauges.size % Palette.series.size]))); picking = false },
        onDismiss = { picking = false })
}

private fun colorLong(c: Color): Long {
    val a = (c.alpha * 255).toLong(); val r = (c.red * 255).toLong(); val gg = (c.green * 255).toLong(); val b = (c.blue * 255).toLong()
    return (a shl 24) or (r shl 16) or (gg shl 8) or b
}

@Composable
private fun GaugeEditor(cfg: GaugeConfig, onChange: (GaugeConfig) -> Unit, onUp: () -> Unit, onDown: () -> Unit, onDelete: () -> Unit) {
    val ch = Pids.channel(cfg.channel)
    var styleOpen by remember { mutableStateOf(false) }
    var warnHigh by remember(cfg.channel) { mutableStateOf(cfg.warnHigh?.toString() ?: "") }
    var warnLow by remember(cfg.channel) { mutableStateOf(cfg.warnLow?.toString() ?: "") }
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(14.dp).clip(CircleShape).background(Color(cfg.color)))
            Spacer(Modifier.width(8.dp))
            Text(ch?.name ?: cfg.channel, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            IconButton(onClick = onUp) { Icon(Icons.Filled.ArrowUpward, "Up") }
            IconButton(onClick = onDown) { Icon(Icons.Filled.ArrowDownward, "Down") }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "Remove") }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box {
                OutlinedButton(onClick = { styleOpen = true }) { Text(cfg.style.label) }
                DropdownMenu(styleOpen, { styleOpen = false }) {
                    GaugeStyle.entries.forEach { s -> DropdownMenuItem(text = { Text(s.label) }, onClick = { onChange(cfg.copy(style = s)); styleOpen = false }) }
                }
            }
            FilterChip(cfg.span == 2, { onChange(cfg.copy(span = if (cfg.span == 2) 1 else 2)) }, label = { Text("Wide") })
            FilterChip(cfg.height == 2, { onChange(cfg.copy(height = if (cfg.height == 2) 1 else 2)) }, label = { Text("Tall") })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 4.dp)) {
            Palette.series.forEach { c ->
                Box(Modifier.size(26.dp).clip(CircleShape).background(c).clickable { onChange(cfg.copy(color = colorLong(c))) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(warnLow, { warnLow = it; onChange(cfg.copy(warnLow = it.toDoubleOrNull())) }, label = { Text("Warn below (${ch?.unit ?: ""})") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(warnHigh, { warnHigh = it; onChange(cfg.copy(warnHigh = it.toDoubleOrNull())) }, label = { Text("Warn above (${ch?.unit ?: ""})") }, singleLine = true, modifier = Modifier.weight(1f))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Show min / avg / max", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            Switch(cfg.showStats, { onChange(cfg.copy(showStats = it)) })
        }
    }
}

/** Searchable channel list; channels the connected car supports are listed first. */
@Composable
fun ChannelPicker(onPick: (String) -> Unit, onDismiss: () -> Unit, multi: Boolean = false, initial: Set<String> = emptySet(), onPickMany: ((Set<String>) -> Unit)? = null) {
    val g = graph()
    var q by remember { mutableStateOf("") }
    val selected = remember { mutableStateListOf<String>().apply { addAll(initial) } }
    val supported = g.connection.report?.supportedPids
    val enh = g.live.enhanced
    val channels = Pids.channels.filter { q.isBlank() || it.name.contains(q, true) || it.key.contains(q, true) || it.shortName.contains(q, true) }
        .sortedWith(compareByDescending<Channel> { supported == null || it.pid < 0 || it.pid in supported }.thenBy { it.category.ordinal })
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (multi) "Choose channels" else "Add gauge") },
        text = {
            Column {
                OutlinedTextField(q, { q = it }, label = { Text("Search") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(enh) { e ->
                        Row(Modifier.fillMaxWidth().clickable { onPick(e.key) }.padding(vertical = 8.dp)) {
                            Text(e.name, modifier = Modifier.weight(1f)); Chip("enh", Palette.Purple)
                        }
                    }
                    items(channels, key = { it.key }) { c ->
                        val ok = supported == null || c.pid < 0 || c.pid in supported
                        Row(Modifier.fillMaxWidth().clickable {
                            if (multi) { if (c.key in selected) selected.remove(c.key) else selected.add(c.key) } else onPick(c.key)
                        }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(c.name, color = if (ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("${c.category.label} · ${c.unit}" + if (!ok) " · not supported by this car" else "", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (c.source != Channel.Source.ECU) Chip(c.source.badge, Palette.Purple)
                            if (multi && c.key in selected) Chip("✓", Palette.Green)
                        }
                    }
                }
            }
        },
        confirmButton = { if (multi) TextButton(onClick = { onPickMany?.invoke(selected.toSet()) }) { Text("Done") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
