package app.obdforge.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import app.obdforge.connection.LinkState
import app.obdforge.core.analysis.EngineAnalysis
import app.obdforge.core.assistant.AssistantInput
import app.obdforge.core.assistant.DiagnosticAssistant
import app.obdforge.core.dtc.Dtc
import app.obdforge.core.dtc.DtcDatabase
import app.obdforge.core.dtc.DtcKind
import app.obdforge.core.dtc.DtcKnowledge
import app.obdforge.core.pid.Mode06
import app.obdforge.core.pid.Pids
import app.obdforge.core.pid.Readiness
import app.obdforge.live.DiagState
import app.obdforge.report.PdfReport
import app.obdforge.report.Share
import app.obdforge.ui.components.Busy
import app.obdforge.ui.components.Chip
import app.obdforge.ui.components.ConfirmDestructive
import app.obdforge.ui.components.EmptyState
import app.obdforge.ui.components.InfoNote
import app.obdforge.ui.components.KeyValue
import app.obdforge.ui.components.RequireChannels
import app.obdforge.ui.components.SectionCard
import app.obdforge.ui.components.SimBadge
import app.obdforge.ui.components.graph
import app.obdforge.ui.components.severityColor
import app.obdforge.ui.theme.Palette
import app.obdforge.util.Units
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DiagnosticsScreen(nav: NavHostController) {
    val g = graph()
    val st by g.diagnostics.state.collectAsStateWithLifecycle()
    val link by g.connection.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val connected = link is LinkState.Connected
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val vehicle by g.activeVehicle.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        ScreenTopBar("Diagnostics", nav) {
            if (g.connection.isSimulated) SimBadge()
            TextButton(onClick = { nav.navigate("dtclib") }) { Text("Code library") }
            IconButton(onClick = {
                scope.launch {
                    val f = withContext(Dispatchers.IO) {
                        val live = g.live.latestMap()
                        val input = AssistantInput(dtcs = st.stored + st.pending, freezeFrames = st.freezeFrames, live = live, mode06 = st.mode06,
                            spec = vehicle?.let { app.obdforge.AppGraph.specOf(it) } ?: app.obdforge.core.live.VehicleSpec())
                        PdfReport.diagnostic(ctx, vehicle, st, g.connection.report?.protocol?.label, g.connection.report?.adapter?.chip, g.connection.isSimulated,
                            emptyList(), DiagnosticAssistant().analyse(input), live)
                    }
                    Share.share(ctx, f, "application/pdf", "Share report")
                }
            }) { Icon(Icons.Filled.PictureAsPdf, "PDF report") }
        }
        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp, containerColor = MaterialTheme.colorScheme.background) {
            listOf("Codes", "Readiness", "Freeze frame", "Monitor tests", "Vehicle info", "ECU scan", "Tests").forEachIndexed { i, t ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
            }
        }
        st.busy?.let { Box(Modifier.padding(horizontal = 16.dp)) { Busy(it) } }
        st.error?.let { Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(it, color = Palette.Red, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            TextButton(onClick = { g.diagnostics.clearError() }) { Text("OK") }
        } }
        if (!connected) InfoNote("Not connected. Showing the last results read.", Palette.Amber)
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (tab) {
                0 -> codesTab(st, connected, nav)
                1 -> readinessTab(st, connected)
                2 -> freezeTab(st)
                3 -> mode06Tab(st, connected)
                4 -> infoTab(st, connected)
                5 -> ecuTab(st, connected, nav)
                6 -> testsTab(st, connected)
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

private fun LazyListScope.codesTab(st: DiagState, connected: Boolean, nav: NavHostController) {
    item {
        val g = graph()
        var confirm by remember { mutableStateOf(false) }
        SectionCard {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { g.diagnostics.readAll() }, enabled = connected && st.busy == null) { Text("Read codes") }
                OutlinedButton(onClick = { confirm = true }, enabled = connected && st.busy == null) { Text("Clear codes", color = Palette.Red) }
            }
            st.readAt?.let { Text("Read ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(it))}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            st.lastClear?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.Amber) }
        }
        if (confirm) ConfirmDestructive(
            title = "Clear trouble codes (Mode 04)?",
            consequences = listOf(
                "Stored and pending codes and the freeze frame are erased.",
                "All readiness monitors reset to \"not ready\". The car may fail an emissions inspection until they complete over several drive cycles.",
                "Learned fuel trims and other adaptive values may reset on some vehicles.",
                "Permanent codes are NOT erased by this command; they clear themselves only after the ECU re-runs the monitor and it passes.",
                "Clearing does not fix anything: if the fault is still there the code will return.",
                "Best done with ignition on and engine off.",
            ),
            acknowledge = "I understand and want to clear codes",
            confirmLabel = "Clear codes",
            onConfirm = { g.diagnostics.clearCodes() },
            onDismiss = { confirm = false },
        )
    }
    if (st.readAt != null && st.allCodes.isEmpty()) item { SectionCard { Text("No trouble codes stored, pending or permanent.", color = Palette.Green) } }
    if (st.readAt == null) item { EmptyState("Codes not read", "Connect and tap Read codes.") }
    for (kind in listOf(DtcKind.STORED, DtcKind.PENDING, DtcKind.PERMANENT)) {
        val list = when (kind) { DtcKind.STORED -> st.stored; DtcKind.PENDING -> st.pending; else -> st.permanent }
        if (list.isEmpty()) continue
        item(key = "k_$kind") {
            Text("${kind.label} (${list.size})", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            when (kind) {
                DtcKind.PENDING -> InfoNote("Pending: the fault was seen once; it becomes stored (MIL on) if it repeats on the next drive cycle.")
                DtcKind.PERMANENT -> InfoNote("Permanent codes cannot be cleared with a scan tool. The ECU removes them after the monitor runs and passes (often after a few drive cycles with the fault fixed).")
                else -> Unit
            }
        }
        val bySystem = list.groupBy { it.system.label }
        bySystem.forEach { (sys, codes) ->
            items(codes, key = { "${kind}_${it.ecu}_${it.code}" }) { d -> DtcRow(d, st, nav, sys) }
        }
    }
}

@Composable
private fun DtcRow(d: Dtc, st: DiagState, nav: NavHostController, system: String) {
    val g = graph()
    val vehicle by g.activeVehicle.collectAsStateWithLifecycle()
    val info = DtcKnowledge.info(d.code.take(5), vehicle?.make)
    SectionCard(modifier = Modifier.clickable { nav.navigate("dtc/${d.code.take(5)}") }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(d.code, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, color = severityColor(info.severity))
            Spacer(Modifier.width(8.dp))
            Chip(info.severity.label, severityColor(info.severity))
            Spacer(Modifier.width(6.dp))
            Chip(if (d.isGeneric) "generic" else "manufacturer", if (d.isGeneric) Palette.Cyan else Palette.Purple)
        }
        Text(info.description, style = MaterialTheme.typography.bodyMedium)
        Text("$system · ${info.system} · ${g.connection.session?.client?.ecuName(d.ecu) ?: "ECU ${d.ecu.toString(16)}"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val inStored = st.stored.any { it.code == d.code }; val inPending = st.pending.any { it.code == d.code }
        if (d.kind == DtcKind.PENDING) Text(
            (if (inStored) "Also stored: the fault is confirmed and recurring." else "Not stored yet.") + (st.pendingHistory[d.code]?.takeIf { it > 1 }?.let { " Seen pending in $it reads this session." } ?: ""),
            style = MaterialTheme.typography.labelSmall, color = Palette.Amber)
        if (d.kind == DtcKind.STORED && !inPending) Text("Not pending now: the fault was not detected again in the current drive cycle.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        st.freezeFrames.firstOrNull { it.dtc == d.code }?.let { Text("Freeze frame available", style = MaterialTheme.typography.labelSmall, color = Palette.Cyan) }
        d.udsStatus?.let { Text("Status: " + Dtc.udsStatusText(it).joinToString(", "), style = MaterialTheme.typography.labelSmall) }
    }
}

private fun LazyListScope.readinessTab(st: DiagState, connected: Boolean) {
    item {
        val g = graph()
        SectionCard {
            Button(onClick = { g.diagnostics.readAll() }, enabled = connected && st.busy == null) { Text("Refresh") }
            InfoNote("Readiness monitors are the self-tests the ECU runs while driving. After codes are cleared or the battery is disconnected they show \"Not ready\" until they complete. Continuous monitors (misfire, fuel, components) run all the time.")
        }
    }
    val sets = listOfNotNull(st.readiness?.let { "Since codes cleared" to it }, st.readinessThisCycle?.let { "This drive cycle" to it })
    if (sets.isEmpty()) item { EmptyState("No readiness data", "Read codes to load monitor status.") }
    sets.forEach { (title, r) ->
        item(key = title) {
            SectionCard(title) {
                if (title.startsWith("Since")) {
                    KeyValue("Check engine light (MIL)", if (r.milOn) "ON" else "Off", valueColor = if (r.milOn) Palette.Red else Palette.Green)
                    KeyValue("Codes reported", r.dtcCount.toString())
                    KeyValue("Ignition type", if (r.compressionIgnition) "Compression (diesel)" else "Spark (petrol)")
                }
                r.monitors.forEach { m ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Text(m.name + if (m.continuous) " (continuous)" else "", modifier = Modifier.weight(1f))
                        Text(m.state.label, color = when (m.state) { Readiness.State.COMPLETE -> Palette.Green; Readiness.State.INCOMPLETE -> Palette.Amber; else -> MaterialTheme.colorScheme.onSurfaceVariant })
                    }
                }
            }
        }
    }
}

private fun LazyListScope.freezeTab(st: DiagState) {
    if (st.freezeFrames.isEmpty()) { item { EmptyState("No freeze frame", "A freeze frame is stored when a code sets. Read codes to load it.") }; return }
    st.freezeFrames.forEach { ff ->
        item(key = "ff_${ff.ecu}_${ff.frame}") {
            val g = graph()
            val values by g.live.values.collectAsStateWithLifecycle()
            val settings by g.settings.state.collectAsStateWithLifecycle()
            RequireChannels("freeze", ff.values.keys)
            SectionCard("Freeze frame · ${ff.dtc ?: "no code"}") {
                InfoNote("Conditions captured when the fault was detected, next to the current live values.")
                Row(Modifier.fillMaxWidth()) {
                    Text("Parameter", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                    Text("At fault", modifier = Modifier.width(90.dp), style = MaterialTheme.typography.labelMedium)
                    Text("Now", modifier = Modifier.width(90.dp), style = MaterialTheme.typography.labelMedium)
                }
                ff.texts.forEach { (pid, t) -> KeyValue(Pids[pid]?.name ?: "PID $pid", t) }
                ff.values.forEach { (k, v) ->
                    val c = Pids.channel(k)
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Text(c?.name ?: k, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        Text(Units.format(c, v, settings.units), modifier = Modifier.width(90.dp), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        Text(Units.format(c, values[k]?.value, settings.units), modifier = Modifier.width(90.dp), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

private fun LazyListScope.mode06Tab(st: DiagState, connected: Boolean) {
    item {
        val g = graph()
        SectionCard {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { g.diagnostics.readMode06() }, enabled = connected && st.busy == null) { Text("Read Mode 06") }
                OutlinedButton(onClick = { g.diagnostics.readMode05() }, enabled = connected && st.busy == null) { Text("O2 tests (Mode 05)") }
            }
            InfoNote("Mode 06 shows the result of each on-board test against the ECU's own limits. A result near a limit (marginal) can predict a future code. CAN vehicles report oxygen-sensor tests here; older vehicles use Mode 05.")
        }
    }
    if (st.mode06Read && st.mode06.isEmpty()) item { EmptyState("No Mode 06 data", "This vehicle did not report on-board test results (common on non-CAN or some manufacturer ECUs).") }
    val groups = st.mode06.groupBy { it.mid }
    groups.forEach { (mid, results) ->
        item(key = "m06_$mid") {
            SectionCard(Mode06.midName(mid)) {
                results.forEach { r -> Mode06Row(r) }
            }
        }
    }
    if (st.mode05.isNotEmpty()) item {
        SectionCard("Oxygen sensor tests (Mode 05)") {
            st.mode05.forEach { KeyValue("${it.sensor} ${it.name}", String.format(Locale.US, "%.3f %s", it.value, it.unit)) }
        }
    }
}

@Composable
private fun Mode06Row(r: Mode06.TestResult) {
    val color = when (r.status) { "FAIL" -> Palette.Red; "MARGINAL" -> Palette.Amber; else -> Palette.Green }
    val track = MaterialTheme.colorScheme.surfaceVariant
    Column(Modifier.padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Test 0x${r.tid.toString(16).uppercase()}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            Text(String.format(Locale.US, "%.3f %s", r.value, r.unit), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.width(8.dp))
            Chip(r.status, color)
        }
        Canvas(Modifier.fillMaxWidth().height(10.dp).padding(vertical = 2.dp)) {
            val lo = minOf(r.min, r.value); val hi = maxOf(r.max, r.value)
            val span = if (hi - lo < 1e-9) 1.0 else hi - lo
            fun x(v: Double) = (((v - lo) / span) * size.width).toFloat()
            drawRoundRect(track, cornerRadius = CornerRadius(4f, 4f))
            drawRoundRect(color.copy(alpha = 0.35f), topLeft = Offset(x(r.min), 0f), size = Size((x(r.max) - x(r.min)).coerceAtLeast(2f), size.height), cornerRadius = CornerRadius(4f, 4f))
            drawCircle(color, size.height * 0.7f, Offset(x(r.value), size.height / 2))
        }
        Text("limits ${String.format(Locale.US, "%.3f", r.min)} – ${String.format(Locale.US, "%.3f", r.max)}" + if (!r.scaled) " (unscaled: unit id not in table)" else "",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun LazyListScope.infoTab(st: DiagState, connected: Boolean) {
    item {
        val g = graph()
        val report = g.connection.report
        SectionCard {
            Button(onClick = { g.diagnostics.readVehicleInfo() }, enabled = connected && st.busy == null) { Text("Read vehicle information (Mode 09)") }
            report?.let { r ->
                KeyValue("Protocol", r.protocol.label)
                KeyValue("OBD standard", r.obdStandard ?: "-")
                KeyValue("Adapter", r.adapter.idString)
                r.adapter.description?.let { KeyValue("Adapter description", it) }
                r.adapter.stnDevice?.let { KeyValue("STN device", it) }
                r.adapter.voltage?.let { KeyValue("Battery at OBD port", String.format(Locale.US, "%.1f V", it)) }
                KeyValue("Connect time", "${r.connectMs} ms")
            }
        }
    }
    report(st)
}

private fun LazyListScope.report(st: DiagState) {
    if (st.vehicleInfo.isNotEmpty()) item {
        val g = graph()
        SectionCard("Mode 09") {
            st.vehicleInfo.forEach { KeyValue("${it.name} · ${g.connection.session?.client?.ecuName(it.ecu) ?: it.ecu.toString(16)}", it.value) }
        }
    }
    item {
        val g = graph()
        val r = g.connection.report
        if (r != null) SectionCard("Adapter capabilities") {
            r.adapter.supported.forEach { (k, v) -> KeyValue(k, if (v) "yes" else "no", valueColor = if (v) Palette.Green else MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

private fun LazyListScope.ecuTab(st: DiagState, connected: Boolean, nav: NavHostController) {
    item {
        val g = graph()
        var extra by remember { mutableStateOf("") }
        SectionCard("Enhanced ECU scan (read-only)") {
            InfoNote("Reads identification (ISO 14229 standard identifiers) and manufacturer trouble codes (UDS service 19) from the engine and transmission addresses and any extra 11-bit request ids you add. This reaches modules on the OBD CAN bus only; body, ABS and airbag modules behind a gateway or on other buses may not answer through an ELM327.")
            OutlinedTextField(extra, { extra = it.uppercase() }, label = { Text("Extra request ids, e.g. 7B0, 760") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onClick = { g.diagnostics.scanEcus(extra.split(',', ' ').mapNotNull { it.trim().toIntOrNull(16) }) }, enabled = connected && st.busy == null) { Text("Scan ECUs") }
        }
    }
    items(st.ecuScan, key = { "ecu_${it.requestId}" }) { e ->
        SectionCard("${e.name} · ${e.requestId.toString(16).uppercase()}") {
            if (!e.responded) Text("No response${e.error?.let { " ($it)" } ?: ""}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            e.identification.forEach { (k, v) -> KeyValue(k, v) }
            if (e.dtcs.isNotEmpty()) {
                Text("Trouble codes (UDS)", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
                e.dtcs.forEach { d ->
                    Row(Modifier.fillMaxWidth().clickable { nav.navigate("dtc/${d.code.take(5)}") }.padding(vertical = 4.dp)) {
                        Text(d.code, fontFamily = FontFamily.Monospace, modifier = Modifier.width(110.dp))
                        Column(Modifier.weight(1f)) {
                            Text(DtcDatabase.describe(d.code.take(5)) ?: "Manufacturer-specific (not in database)", style = MaterialTheme.typography.bodySmall)
                            d.udsStatus?.let { Text(Dtc.udsStatusText(it).joinToString(", "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
            } else if (e.responded) Text("No trouble codes reported", color = Palette.Green, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun LazyListScope.testsTab(st: DiagState, connected: Boolean) {
    item {
        val g = graph()
        var confirm by remember { mutableStateOf(false) }
        SectionCard("EVAP system leak test (Mode 08)") {
            InfoNote("Asks the ECU to seal the evaporative emission system so a leak test can run. Most vehicles do not support Mode 08, and those that do usually require specific conditions (engine off, fuel level within a range, cold soak). The app only sends the request; the ECU decides whether to run it.")
            Button(onClick = { confirm = true }, enabled = connected && st.busy == null, colors = ButtonDefaults.buttonColors(containerColor = Palette.Orange)) { Text("Request EVAP test") }
            st.evapResult?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp)) }
        }
        if (confirm) ConfirmDestructive(
            title = "Run on-board EVAP test?",
            consequences = listOf("The ECU will close the EVAP vent valve and may run the leak-check pump.", "Do not refuel during the test.",
                "Only supported by some vehicles; it does not damage anything if refused.", "Use with the vehicle parked."),
            acknowledge = "The vehicle is parked and I want to run the test",
            confirmLabel = "Run test",
            onConfirm = { g.diagnostics.runEvapTest() },
            onDismiss = { confirm = false },
        )
    }
    item {
        SectionCard("Actuator tests and coding") {
            Text("Actuator tests, adaptation resets and ECU coding are manufacturer-specific and can change how the vehicle behaves. They are deliberately not offered as ordinary features in this app.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun DtcDetailScreen(nav: NavHostController, code: String) {
    val g = graph()
    val vehicle by g.activeVehicle.collectAsStateWithLifecycle()
    val st by g.diagnostics.state.collectAsStateWithLifecycle()
    val values by g.live.values.collectAsStateWithLifecycle()
    val settings by g.settings.state.collectAsStateWithLifecycle()
    val info = DtcKnowledge.info(code, vehicle?.make)
    RequireChannels("dtc_$code", info.relevantChannels)
    ListScreen(code, nav) {
        item {
            SectionCard {
                Text(info.code, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.displaySmall, color = severityColor(info.severity))
                Text(info.description, style = MaterialTheme.typography.titleMedium)
                Text(DtcDatabase.structure(code), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Chip("Severity: ${info.severity.label}", severityColor(info.severity))
                }
                Text(info.severity.advice, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
                if (info.genericGuidance) InfoNote("No specific entry for this code: the guidance below is general advice derived from the code type.", Palette.Amber)
            }
        }
        if (info.symptoms.isNotEmpty()) item { SectionCard("Common symptoms") { info.symptoms.forEach { Text("• $it") } } }
        item {
            SectionCard("Possible causes") {
                InfoNote("Possible causes only. None is confirmed until a test proves it.")
                info.possibleCauses.forEach { Text("• $it", modifier = Modifier.padding(vertical = 2.dp)) }
            }
        }
        if (info.diagnosticSteps.isNotEmpty()) item {
            SectionCard("Diagnostic procedure / inspection order") { info.diagnosticSteps.forEachIndexed { i, s -> Text("${i + 1}. $s", modifier = Modifier.padding(vertical = 3.dp)) } }
        }
        if (info.relevantChannels.isNotEmpty()) item {
            SectionCard("Relevant live readings") {
                info.relevantChannels.forEach { k ->
                    val c = Pids.channel(k) ?: return@forEach
                    KeyValue(c.name, Units.format(c, values[k]?.value, settings.units))
                }
                TextButton(onClick = { nav.navigate("livegraph?keys=${info.relevantChannels.take(4).joinToString(",")}") }) { Text("Graph these") }
            }
        }
        st.freezeFrames.firstOrNull { it.dtc == code }?.let { ff ->
            item { SectionCard("Freeze frame") { ff.values.forEach { (k, v) -> val c = Pids.channel(k); KeyValue(c?.name ?: k, Units.format(c, v, settings.units)) } } }
        }
        if (info.related.isNotEmpty()) item {
            SectionCard("Related codes") {
                info.related.forEach { r ->
                    Row(Modifier.fillMaxWidth().clickable { nav.navigate("dtc/$r") }.padding(vertical = 4.dp)) {
                        Text(r, fontFamily = FontFamily.Monospace, modifier = Modifier.width(70.dp)); Text(DtcDatabase.describe(r) ?: "", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (info.repairNotes.isNotEmpty()) item { SectionCard("Repair considerations") { info.repairNotes.forEach { Text("• $it") } } }
        item { OutlinedButton(onClick = { nav.navigate("assistant") }) { Text("Open diagnostic assistant") } }
    }
}

@Composable
fun DtcLibraryScreen(nav: NavHostController) {
    var q by remember { mutableStateOf("") }
    val results = remember(q) { DtcDatabase.search(q) }
    ListScreen("Trouble code library", nav) {
        item {
            OutlinedTextField(q, { q = it }, label = { Text("Code or words (e.g. P0171, misfire)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            InfoNote("${DtcDatabase.size} SAE generic codes. Manufacturer-specific codes appear only from vehicle profiles you import, so none are invented.")
        }
        if (q.isNotBlank() && results.isEmpty()) item {
            val c = q.trim().uppercase()
            if (Regex("[PCBU][0-9A-F]{4}").matches(c)) TextButton(onClick = { nav.navigate("dtc/$c") }) { Text("Open $c") } else Text("No matches")
        }
        items(results, key = { it.first }) { (code, text) ->
            Row(Modifier.fillMaxWidth().clickable { nav.navigate("dtc/$code") }.padding(vertical = 6.dp)) {
                Text(code, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, modifier = Modifier.width(72.dp))
                Text(text, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Suppress("unused")
private fun unusedAnalysisRef() = EngineAnalysis.fmt(0.0)
