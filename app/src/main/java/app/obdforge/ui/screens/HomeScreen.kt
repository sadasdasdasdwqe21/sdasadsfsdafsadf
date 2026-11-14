package app.obdforge.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import app.obdforge.connection.AdapterTarget
import app.obdforge.connection.LinkState
import app.obdforge.core.pid.Pids
import app.obdforge.ui.components.Busy
import app.obdforge.ui.components.Chip
import app.obdforge.ui.components.KeyValue
import app.obdforge.ui.components.RequireChannels
import app.obdforge.ui.components.SectionCard
import app.obdforge.ui.components.SimBadge
import app.obdforge.ui.components.StatusDot
import app.obdforge.ui.components.graph
import app.obdforge.ui.theme.Palette
import app.obdforge.util.Units
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(nav: NavHostController) {
    val g = graph()
    val link by g.connection.state.collectAsStateWithLifecycle()
    val vehicle by g.activeVehicle.collectAsStateWithLifecycle()
    val values by g.live.values.collectAsStateWithLifecycle()
    val diag by g.diagnostics.state.collectAsStateWithLifecycle()
    val rec by g.recorder.state.collectAsStateWithLifecycle()
    val settings by g.settings.state.collectAsStateWithLifecycle()
    val rate by g.live.rateHz.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    RequireChannels("home", listOf("rpm", "speed", "ect", "ecu_voltage", "adapter_voltage", "load"))

    ListScreen("OBD Forge", null) {
        item {
            SectionCard("Connection") {
                val (dot, label) = when (val s = link) {
                    LinkState.Idle -> Color.Gray to "Not connected"
                    is LinkState.Connecting -> Palette.Amber to s.step
                    is LinkState.Connected -> Palette.Green to "Connected · ${s.target.label}"
                    is LinkState.Reconnecting -> Palette.Amber to "Reconnecting (attempt ${s.attempt})"
                    is LinkState.Failed -> Palette.Red to s.message
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(dot); Spacer(Modifier.width(8.dp))
                    Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    if ((link as? LinkState.Connected)?.target is AdapterTarget.Simulator) SimBadge()
                }
                (link as? LinkState.Connected)?.let { c ->
                    Spacer(Modifier.height(6.dp))
                    KeyValue("Protocol", c.report.protocol.label)
                    KeyValue("Adapter", c.report.adapter.chip + if (c.report.adapter.likelyClone) " (possible clone)" else "")
                    KeyValue("ECUs", c.report.ecus.joinToString { it.name })
                    KeyValue("Supported PIDs", c.report.supportedPids.size.toString())
                    KeyValue("Link quality", g.connection.linkQualityText())
                    KeyValue("Refresh", if (rate > 0) String.format("%.1f cycles/s", rate) else "--")
                    c.report.warnings.forEach { Text(it, color = Palette.Amber, style = MaterialTheme.typography.bodySmall) }
                }
                if (link is LinkState.Connecting) Busy("Working…")
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (link is LinkState.Connected || link is LinkState.Reconnecting || link is LinkState.Connecting) {
                        OutlinedButton(onClick = { g.connection.disconnect() }) { Text("Disconnect") }
                    } else {
                        Button(onClick = { nav.navigate("connect") }) { Icon(Icons.Filled.Bluetooth, null); Spacer(Modifier.width(6.dp)); Text("Connect") }
                        settings.lastAdapter?.let { last -> OutlinedButton(onClick = { g.connection.reconnectLast() }) { Text("Reconnect ${last.name}") } }
                    }
                }
            }
        }
        item {
            SectionCard("Vehicle", action = { OutlinedButton(onClick = { nav.navigate("vehicles") }) { Text("Vehicles") } }) {
                if (vehicle == null) Text("No vehicle profile yet. One is created automatically from the VIN when you connect, or add it yourself.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                vehicle?.let { v ->
                    Text(listOf(v.year?.toString() ?: "", v.make, v.model).filter { it.isNotBlank() }.joinToString(" ").ifBlank { v.name }, style = MaterialTheme.typography.titleMedium)
                    if (v.engine.isNotBlank() || v.engineCode.isNotBlank()) KeyValue("Engine", listOf(v.engine, v.engineCode).filter { it.isNotBlank() }.joinToString(" · "))
                    if (v.vin.isNotBlank()) KeyValue("VIN", v.vin)
                }
            }
        }
        item {
            SectionCard("Engine status") {
                if (values.isEmpty()) Text(if (link is LinkState.Connected) "Waiting for data…" else "Connect to see live engine data.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    for (k in listOf("rpm", "speed", "ect", "ecu_voltage")) {
                        val ch = Pids.channel(k)
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(Units.format(ch, values[k]?.value ?: (if (k == "ecu_voltage") values["adapter_voltage"]?.value else null), settings.units, withUnit = false),
                                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 24.sp,
                                color = if (k == "ect" && (values[k]?.value ?: 0.0) > 105) Palette.Red else MaterialTheme.colorScheme.onSurface)
                            Text("${ch?.shortName} ${Units.unitLabel(ch, settings.units)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                val running = (values["rpm"]?.value ?: 0.0) > 300
                if (values.isNotEmpty()) Text(if (running) "Engine running" else "Engine off (ignition on)", style = MaterialTheme.typography.bodySmall,
                    color = if (running) Palette.Green else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
        }
        item {
            SectionCard("Trouble codes", action = {
                if (link is LinkState.Connected) OutlinedButton(onClick = { g.diagnostics.readAll() }, enabled = diag.busy == null) { Text("Read") }
            }) {
                if (diag.busy != null) Busy(diag.busy!!)
                when {
                    diag.readAt == null -> Text("Not read yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    diag.dtcCount == 0 && diag.permanent.isEmpty() -> Text("No trouble codes", color = Palette.Green, style = MaterialTheme.typography.titleMedium)
                    else -> {
                        Text("${diag.stored.size} stored · ${diag.pending.size} pending · ${diag.permanent.size} permanent",
                            style = MaterialTheme.typography.titleMedium, color = if (diag.stored.isNotEmpty()) Palette.Red else Palette.Amber)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                            diag.allCodes.map { it.code }.distinct().take(6).forEach { Chip(it, Palette.Orange) }
                        }
                    }
                }
                diag.readiness?.let { r -> Text("MIL ${if (r.milOn) "ON" else "off"} · ${r.supported.size - r.incomplete.size}/${r.supported.size} monitors ready",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)) }
            }
        }
        item {
            SectionCard("Recording") {
                val r = rec
                if (r == null) {
                    Text("Not recording.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    Button(enabled = link is LinkState.Connected, onClick = { scope.launch { g.recorder.start(null, null, g.recorder.defaultChannels()) } },
                        colors = ButtonDefaults.buttonColors(containerColor = Palette.Red)) {
                        Icon(Icons.Filled.FiberManualRecord, null); Spacer(Modifier.width(6.dp)); Text("Start recording")
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(Palette.Red); Spacer(Modifier.width(8.dp))
                        Text("${r.title} · ${r.samples} samples · ${(System.currentTimeMillis() - r.startedAt) / 1000}s", modifier = Modifier.weight(1f))
                        if (r.simulated) SimBadge()
                    }
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(onClick = { scope.launch { g.recorder.stop() } }) { Icon(Icons.Filled.Stop, null); Spacer(Modifier.width(6.dp)); Text("Stop") }
                }
            }
        }
        item {
            val shortcuts = listOf(
                Triple("Live data", Icons.Filled.ShowChart, "live"), Triple("Dashboards", Icons.Filled.Speed, "dashboards"),
                Triple("Diagnose", Icons.Filled.Build, "diag"), Triple("Performance", Icons.Filled.Timer, "perf"),
                Triple("Analysis", Icons.Filled.Analytics, "analysis"), Triple("Assistant", Icons.Filled.Psychology, "assistant"),
                Triple("Recordings", Icons.Filled.FiberManualRecord, "sessions"), Triple("Vehicles", Icons.Filled.DirectionsCar, "vehicles"),
            )
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                shortcuts.chunked(4).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { (label, icon, route) -> Shortcut(label, icon, Modifier.weight(1f)) { nav.navigate(route) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun Shortcut(label: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Card(modifier.clickable(onClick = onClick), shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
            Spacer(Modifier.height(4.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}
