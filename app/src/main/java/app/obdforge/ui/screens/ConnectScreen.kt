package app.obdforge.ui.screens

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import app.obdforge.connection.AdapterTarget
import app.obdforge.connection.FoundDevice
import app.obdforge.connection.LinkState
import app.obdforge.core.elm.ObdProtocol
import app.obdforge.core.obd.Priority
import app.obdforge.core.sim.SimScenario
import app.obdforge.ui.components.Busy
import app.obdforge.ui.components.Chip
import app.obdforge.ui.components.InfoNote
import app.obdforge.ui.components.SectionCard
import app.obdforge.ui.components.graph
import app.obdforge.ui.theme.Palette
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun btPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= 31)
    arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION)
else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

@Composable
fun ConnectScreen(nav: NavHostController) {
    val g = graph()
    var tab by remember { mutableIntStateOf(0) }
    val link by g.connection.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        ScreenTopBar("Connect adapter", nav) {
            IconButton(onClick = { nav.navigate("terminal") }) { Icon(Icons.Filled.Terminal, "Terminal") }
        }
        TabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background) {
            listOf("Bluetooth", "BLE", "Wi-Fi", "Simulator").forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
        }
        LinkStatusLine(link)
        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> BluetoothTab(ble = false)
                1 -> BluetoothTab(ble = true)
                2 -> WifiTab()
                3 -> SimulatorTab()
            }
        }
    }
}

@Composable
private fun LinkStatusLine(link: LinkState) {
    val (t, c) = when (link) {
        LinkState.Idle -> "Not connected" to Color.Gray
        is LinkState.Connecting -> link.step to Palette.Amber
        is LinkState.Connected -> "Connected: ${link.target.label} · ${link.report.protocol.label}" to Palette.Green
        is LinkState.Reconnecting -> "Reconnecting (attempt ${link.attempt}): ${link.reason}" to Palette.Amber
        is LinkState.Failed -> link.message to Palette.Red
    }
    Text(t, color = c, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp))
}

@Composable
private fun BluetoothTab(ble: Boolean) {
    val g = graph()
    val ctx = LocalContext.current
    val devices by g.scanner.devices.collectAsStateWithLifecycle()
    val scanning by g.scanner.scanning.collectAsStateWithLifecycle()
    var permOk by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        permOk = res.values.all { it } || (Build.VERSION.SDK_INT >= 31 && res[Manifest.permission.BLUETOOTH_CONNECT] == true && res[Manifest.permission.BLUETOOTH_SCAN] == true)
        if (permOk) g.scanner.startScan(classic = !ble, ble = ble)
    }
    DisposableEffect(ble) { onDispose { g.scanner.stopScan() } }
    LaunchedEffect(ble) { launcher.launch(btPermissions()) }
    val list = devices.filter { it.ble == ble || (!ble && it.bonded && !it.ble) }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            SectionCard {
                if (g.scanner.bluetooth == null) Text("This device has no Bluetooth.", color = Palette.Red)
                else if (g.scanner.bluetooth?.isEnabled != true) {
                    Text("Bluetooth is off.", color = Palette.Amber)
                    TextButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }) { Text("Open Bluetooth settings") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { if (permOk) g.scanner.startScan(!ble, ble) else launcher.launch(btPermissions()) }) { Text(if (scanning) "Scanning…" else "Scan") }
                    Spacer(Modifier.width(8.dp))
                    if (scanning) OutlinedButton(onClick = { g.scanner.stopScan() }) { Text("Stop") }
                }
                InfoNote(if (ble) "BLE adapters (e.g. Vgate iCar Pro BLE, OBDLink CX, Veepeak BLE) do not need pairing. The app finds their serial service automatically."
                else "Classic adapters usually need pairing first in Android Bluetooth settings (PIN is often 1234 or 0000). Paired adapters are listed at the top.")
                if (!permOk) InfoNote("Bluetooth permissions are needed to find and connect to the adapter.", Palette.Amber)
            }
        }
        if (list.isEmpty()) item { Text(if (scanning) "Searching…" else "No devices found yet.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp)) }
        items(list, key = { it.address + it.ble }) { d -> DeviceRow(d, g.scanner.signalBars(d.rssi)) {
            g.scanner.stopScan()
            g.connection.connect(if (ble) AdapterTarget.Ble(d.address, d.name) else AdapterTarget.Classic(d.address, d.name))
        } }
        item { AdvancedConnection() }
    }
}

@Composable
private fun DeviceRow(d: FoundDevice, bars: Int, onClick: () -> Unit) {
    SectionCard(modifier = Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(d.name, style = MaterialTheme.typography.titleMedium)
                Text(d.address + if (d.rssi != null) " · ${d.rssi} dBm" else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (d.likelyObd) Chip("OBD", Palette.Green)
            Spacer(Modifier.width(6.dp))
            if (d.bonded) Chip("paired", Palette.Cyan)
            Spacer(Modifier.width(6.dp))
            SignalBars(bars)
        }
    }
}

@Composable
fun SignalBars(bars: Int) {
    Row(verticalAlignment = Alignment.Bottom) {
        for (i in 1..4) {
            Box(Modifier.padding(horizontal = 1.dp).width(4.dp).height((4 + i * 4).dp)
                .background(if (bars >= i) Palette.Green else MaterialTheme.colorScheme.surfaceVariant))
        }
    }
}

@Composable
private fun WifiTab() {
    val g = graph()
    val ctx = LocalContext.current
    var host by remember { mutableStateOf(g.settings.value.lastAdapter?.takeIf { it.kind == "wifi" }?.address ?: "192.168.0.10") }
    var port by remember { mutableStateOf((g.settings.value.lastAdapter?.takeIf { it.kind == "wifi" }?.port ?: 35000).toString()) }
    val wifi by g.scanner.wifi.collectAsStateWithLifecycle()
    val status by g.wifiBinder.status.collectAsStateWithLifecycle()
    val net by g.wifiBinder.network.collectAsStateWithLifecycle()
    var password by remember { mutableStateOf("") }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) g.scanner.scanWifi() }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            SectionCard("Adapter address") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(host, { host = it.trim() }, label = { Text("IP address") }, singleLine = true, modifier = Modifier.weight(2f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next))
                    OutlinedTextField(port, { port = it.filter { c -> c.isDigit() } }, label = { Text("Port") }, singleLine = true, modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("192.168.0.10" to 35000, "192.168.0.10" to 23, "192.168.1.10" to 35000, "192.168.0.123" to 35000).forEach { (h, p) ->
                        FilterChip(selected = host == h && port == p.toString(), onClick = { host = h; port = p.toString() }, label = { Text("$h:$p") })
                    }
                }
                Spacer(Modifier.height(6.dp))
                Button(onClick = { g.connection.connect(AdapterTarget.Wifi(host, port.toIntOrNull() ?: 35000)) }) { Text("Connect") }
                InfoNote("Most ELM327 Wi-Fi adapters use 192.168.0.10 port 35000. Join the adapter's Wi-Fi first (below or in Android settings)." +
                    if (net != null) " The app is bound to the adapter network." else "")
            }
        }
        item {
            SectionCard("Adapter Wi-Fi networks", action = { OutlinedButton(onClick = { launcher.launch(Manifest.permission.ACCESS_FINE_LOCATION) }) { Text("Scan") } }) {
                if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall, color = Palette.Cyan)
                OutlinedTextField(password, { password = it }, label = { Text("Password (if the adapter has one)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (wifi.isEmpty()) Text("No networks listed yet. Tap Scan (needs location permission).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                wifi.take(12).forEach { w ->
                    Row(Modifier.fillMaxWidth().clickable { g.wifiBinder.request(w.ssid, password) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(w.ssid, modifier = Modifier.weight(1f))
                        if (w.likelyObd) Chip("OBD", Palette.Green)
                        Spacer(Modifier.width(6.dp))
                        SignalBars(g.scanner.signalBars(w.level))
                    }
                }
                TextButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }) { Text("Open Wi-Fi settings") }
            }
        }
        item { AdvancedConnection() }
    }
}

@Composable
private fun SimulatorTab() {
    val g = graph()
    val link by g.connection.state.collectAsStateWithLifecycle()
    var scenario by remember { mutableStateOf(SimScenario.NORMAL) }
    var legacy by remember { mutableStateOf(false) }
    val connectedSim = (link as? LinkState.Connected)?.target is AdapterTarget.Simulator
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            SectionCard("Simulated vehicle") {
                InfoNote("The simulator is a software ELM327 with a modelled 2.0 L turbo engine. Use it to try every feature without a car. All its data is labelled SIMULATED, including recordings and reports.", Palette.Amber)
                SimScenario.entries.forEach { s ->
                    Row(Modifier.fillMaxWidth().clickable {
                        scenario = s
                        if (connectedSim) g.connection.simulator?.scenario = s
                    }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = scenario == s, onClick = { scenario = s; if (connectedSim) g.connection.simulator?.scenario = s })
                        Column { Text(s.label); Text(s.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Legacy protocol (ISO 9141-2)", modifier = Modifier.weight(1f))
                    Switch(checked = legacy, onCheckedChange = { legacy = it })
                }
                Button(onClick = { g.connection.connect(AdapterTarget.Simulator(scenario, legacy)) }) { Text(if (connectedSim) "Restart simulator" else "Connect to simulator") }
            }
        }
        if (connectedSim) item {
            SectionCard("Simulator controls") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { g.connection.simulator?.fastForward(300.0) }) { Text("Skip 5 min") }
                    OutlinedButton(onClick = { g.connection.simulator?.dropLink() }) { Text("Drop link") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("P0300", "P0420", "P0128").forEach { c -> OutlinedButton(onClick = { g.connection.simulator?.injectDtc(c) }) { Text("+$c") } }
                }
                InfoNote("“Drop link” simulates the adapter disappearing so you can watch automatic reconnection.")
            }
        }
    }
}

@Composable
private fun AdvancedConnection() {
    val g = graph()
    val s by g.settings.state.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    SectionCard("Advanced") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Protocol", modifier = Modifier.weight(1f))
            Box {
                OutlinedButton(onClick = { open = true }) { Text(runCatching { ObdProtocol.valueOf(s.preferredProtocol).label }.getOrDefault("Automatic"), maxLines = 1) }
                DropdownMenu(open, { open = false }) {
                    ObdProtocol.entries.filter { it != ObdProtocol.SAE_J1939 && it != ObdProtocol.USER1 && it != ObdProtocol.USER2 }.forEach { p ->
                        DropdownMenuItem(text = { Text(p.label) }, onClick = { g.settings.update { it.copy(preferredProtocol = p.name) }; open = false })
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("ISO 9141 / KWP baud", modifier = Modifier.weight(1f))
            listOf(10400, 9600).forEach { b -> FilterChip(selected = s.isoBaud == b, onClick = { g.settings.update { it.copy(isoBaud = b) } }, label = { Text("$b") }, modifier = Modifier.padding(start = 6.dp)) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Reconnect automatically", modifier = Modifier.weight(1f))
            Switch(s.autoReconnect, { v -> g.settings.update { it.copy(autoReconnect = v) } })
        }
        InfoNote("Automatic protocol detection works for nearly all cars. Forcing a protocol speeds up connection when you know it (e.g. CAN 11-bit 500k for most 2008+ cars). The UART baud rate inside Bluetooth/Wi-Fi adapters is fixed by the adapter and is not changed by the app.")
    }
}

/** Raw adapter console. Commands that change ECU state are blocked unless Expert mode is on. */
@Composable
fun TerminalScreen(nav: NavHostController) {
    val g = graph()
    val traffic by g.connection.traffic.collectAsStateWithLifecycle()
    val settings by g.settings.state.collectAsStateWithLifecycle()
    var cmd by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val ctx = LocalContext.current
    LaunchedEffect(traffic.size) { if (traffic.isNotEmpty()) listState.scrollToItem(traffic.size - 1) }
    val fmt = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }
    Column(Modifier.fillMaxSize()) {
        ScreenTopBar("Adapter terminal", nav) {
            TextButton(onClick = {
                val i = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, g.connection.trafficText()); putExtra(Intent.EXTRA_SUBJECT, "OBD Forge adapter log") }
                ctx.startActivity(Intent.createChooser(i, "Share log"))
            }) { Text("Share log") }
        }
        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.dp)) {
            items(traffic) { l ->
                Text("${fmt.format(Date(l.time))} ${if (l.outgoing) ">>" else "<<"} ${l.text}", fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                    color = if (l.outgoing) Palette.Cyan else if (l.text.startsWith("#")) Palette.Amber else MaterialTheme.colorScheme.onSurface)
            }
        }
        msg?.let { Text(it, color = Palette.Amber, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 12.dp)) }
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(cmd, { cmd = it.uppercase() }, singleLine = true, label = { Text("AT or OBD command (e.g. ATRV, 010C)") }, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                val c = cmd.trim().replace(" ", "")
                val blocked = TerminalGuard.check(c)
                if (blocked != null && !settings.expertMode) { msg = "$blocked Enable Expert mode in Settings to send it."; return@Button }
                msg = null
                scope.launch {
                    runCatching { g.connection.run(Priority.HIGH) { it.elm.send(c, 5000) } }.onFailure { msg = it.message }
                }
                cmd = ""
            }, enabled = cmd.isNotBlank()) { Text("Send") }
        }
    }
}

object TerminalGuard {
    /** Services that change ECU state; returns a reason if [cmd] would send one. */
    fun check(cmd: String): String? {
        if (cmd.startsWith("AT") || cmd.startsWith("ST")) {
            if (cmd.startsWith("ATPP") || cmd.startsWith("STSBR") || cmd.startsWith("ATBRD")) return "Adapter configuration change blocked."
            return null
        }
        val sid = cmd.take(2).uppercase()
        return when (sid) {
            "04" -> "Mode 04 clears codes. Use Diagnostics → Clear codes so you see the consequences first."
            "08" -> "Mode 08 controls on-board systems."
            "10", "11", "14", "27", "28", "2E", "2F", "31", "34", "35", "36", "37", "3B", "3D", "85" -> "UDS service 0x$sid can change ECU state (session/reset/clear/write/actuate/programming)."
            else -> null
        }
    }
}
