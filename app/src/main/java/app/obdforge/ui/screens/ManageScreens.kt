package app.obdforge.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Bluetooth
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import app.obdforge.BuildConfig
import app.obdforge.core.alert.AlertCondition
import app.obdforge.core.alert.AlertRule
import app.obdforge.core.dtc.DtcDatabase
import app.obdforge.core.live.FuelKind
import app.obdforge.core.mfr.ManufacturerProfile
import app.obdforge.core.mfr.Profiles
import app.obdforge.core.pid.Pids
import app.obdforge.data.PollingMode
import app.obdforge.data.ProfileEntity
import app.obdforge.data.ServiceLogEntity
import app.obdforge.data.ThemeMode
import app.obdforge.data.UnitSystem
import app.obdforge.data.VehicleEntity
import app.obdforge.report.Share
import app.obdforge.ui.components.EmptyState
import app.obdforge.ui.components.InfoNote
import app.obdforge.ui.components.KeyValue
import app.obdforge.ui.components.SectionCard
import app.obdforge.ui.components.graph
import app.obdforge.ui.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MoreScreen(nav: NavHostController) {
    val entries = listOf(
        Triple("Connect adapter", Icons.Filled.Bluetooth, "connect"),
        Triple("Performance tests", Icons.Filled.Timer, "perf"),
        Triple("Engine analysis", Icons.Filled.Analytics, "analysis"),
        Triple("Diagnostic assistant", Icons.Filled.Psychology, "assistant"),
        Triple("Alerts", Icons.Filled.Notifications, "alerts"),
        Triple("Vehicles", Icons.Filled.DirectionsCar, "vehicles"),
        Triple("Manufacturer profiles", Icons.Filled.Extension, "profiles"),
        Triple("Trouble code library", Icons.Filled.MenuBook, "dtclib"),
        Triple("Adapter terminal", Icons.Filled.Terminal, "terminal"),
        Triple("Diagnostics", Icons.Filled.Build, "diag"),
        Triple("Settings", Icons.Filled.Settings, "settings"),
    )
    ListScreen("More", null) {
        items(entries) { (label, icon, route) -> MenuRow(label, icon) { nav.navigate(route) } }
        item { Text("OBD Forge ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp)) }
    }
}

@Composable
private fun MenuRow(label: String, icon: ImageVector, onClick: () -> Unit) {
    SectionCard(modifier = Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(14.dp))
            Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
        }
    }
}

// ------------------------------------------------------------------------------------------ Alerts

@Composable
fun AlertsScreen(nav: NavHostController) {
    val g = graph()
    val rules by g.alerts.rules.collectAsStateWithLifecycle()
    val history by g.db.misc().alertHistory().collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<AlertRule?>(null) }
    val fmt = remember { SimpleDateFormat("d MMM HH:mm:ss", Locale.getDefault()) }
    ListScreen("Alerts", nav, actions = {
        IconButton(onClick = { editing = AlertRule("custom_${System.currentTimeMillis()}", "Custom alert", "rpm", AlertCondition.ABOVE, 6000.0) }) { Icon(Icons.Filled.Add, "Add") }
    }) {
        item { InfoNote("Alerts are checked once per second while connected, also in the background. Values are compared in metric units. New trouble codes are checked every minute.") }
        items(rules, key = { it.id }) { r ->
            SectionCard(modifier = Modifier.clickable { editing = r }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.title, style = MaterialTheme.typography.titleSmall)
                        Text(r.describe() + " for ${r.sustainMs / 1000.0} s" + (r.minRpm?.let { " (engine running)" } ?: ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(r.enabled, { en -> g.alerts.setRules(rules.map { if (it.id == r.id) it.copy(enabled = en) else it }) })
                }
            }
        }
        item { TextButton(onClick = { g.alerts.resetDefaults() }) { Text("Restore default alerts") } }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Notification history", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                if (history.isNotEmpty()) TextButton(onClick = { scope.launch(Dispatchers.IO) { g.db.misc().clearAlerts() } }) { Text("Clear") }
            }
        }
        if (history.isEmpty()) item { Text("No alerts yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(history.take(200), key = { "h${it.id}" }) { e ->
            Column(Modifier.padding(vertical = 4.dp)) {
                Text(e.title, color = if (e.critical) Palette.Red else Palette.Amber, style = MaterialTheme.typography.titleSmall)
                Text("${fmt.format(Date(e.time))} · ${e.message}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    editing?.let { r -> AlertEditor(r, onSave = { n -> g.alerts.setRules(if (rules.any { it.id == n.id }) rules.map { if (it.id == n.id) n else it } else rules + n); editing = null },
        onDelete = { g.alerts.setRules(rules.filter { it.id != r.id }); editing = null }, onDismiss = { editing = null }) }
}

@Composable
private fun AlertEditor(r: AlertRule, onSave: (AlertRule) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    var title by remember { mutableStateOf(r.title) }
    var channel by remember { mutableStateOf(r.channelKey) }
    var cond by remember { mutableStateOf(r.condition) }
    var t1 by remember { mutableStateOf(r.threshold.toString()) }
    var t2 by remember { mutableStateOf(if (r.threshold2.isNaN()) "" else r.threshold2.toString()) }
    var sustain by remember { mutableStateOf((r.sustainMs / 1000.0).toString()) }
    var critical by remember { mutableStateOf(r.critical) }
    var picking by remember { mutableStateOf(false) }
    var condOpen by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Alert") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(title, { title = it }, label = { Text("Name") }, singleLine = true)
            OutlinedButton(onClick = { picking = true }) { Text("Value: ${Pids.channel(channel)?.name ?: channel}") }
            Box {
                OutlinedButton(onClick = { condOpen = true }) { Text("Condition: ${cond.label}") }
                DropdownMenu(condOpen, { condOpen = false }) { AlertCondition.entries.forEach { c -> DropdownMenuItem(text = { Text(c.label) }, onClick = { cond = c; condOpen = false }) } }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(t1, { t1 = it }, label = { Text(if (cond == AlertCondition.OUTSIDE) "Low" else "Limit (${Pids.channel(channel)?.unit ?: ""})") }, singleLine = true, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                if (cond == AlertCondition.OUTSIDE) OutlinedTextField(t2, { t2 = it }, label = { Text("High") }, singleLine = true, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            }
            OutlinedTextField(sustain, { sustain = it }, label = { Text("Must last (seconds)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Critical", modifier = Modifier.weight(1f)); Switch(critical, { critical = it }) }
        }
    }, confirmButton = {
        TextButton(onClick = {
            val a = t1.toDoubleOrNull() ?: return@TextButton
            onSave(r.copy(title = title, channelKey = channel, condition = cond, threshold = a, threshold2 = t2.toDoubleOrNull() ?: Double.NaN,
                sustainMs = ((sustain.toDoubleOrNull() ?: 2.0) * 1000).toLong(), critical = critical))
        }) { Text("Save") }
    }, dismissButton = { Row { TextButton(onClick = onDelete) { Text("Delete", color = Palette.Red) }; TextButton(onClick = onDismiss) { Text("Cancel") } } })
    if (picking) ChannelPicker(onPick = { channel = it; picking = false }, onDismiss = { picking = false })
}

// ------------------------------------------------------------------------------------------ Vehicles

@Composable
fun VehiclesScreen(nav: NavHostController) {
    val g = graph()
    val vehicles by g.vehicles.collectAsStateWithLifecycle()
    val active by g.activeVehicle.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    ListScreen("Vehicles", nav, actions = {
        IconButton(onClick = { scope.launch { val id = withContext(Dispatchers.IO) { g.db.vehicles().insert(VehicleEntity(name = "New vehicle")) }; nav.navigate("vehicle/$id") } }) { Icon(Icons.Filled.Add, "Add") }
    }) {
        if (vehicles.isEmpty()) item { EmptyState("No vehicles", "Add one, or connect and one is created from the VIN.") }
        items(vehicles, key = { it.id }) { v ->
            SectionCard(modifier = Modifier.clickable { nav.navigate("vehicle/${v.id}") }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(active?.id == v.id, { g.settings.update { it.copy(activeVehicleId = v.id) } })
                    Column(Modifier.weight(1f)) {
                        Text(v.name, style = MaterialTheme.typography.titleMedium)
                        Text(listOf(v.year?.toString() ?: "", v.make, v.model, v.engineCode).filter { it.isNotBlank() }.joinToString(" "), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (v.vin.isNotBlank()) Text(v.vin, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        item { InfoNote("The active vehicle receives new recordings and diagnostic history, and its parameters are used for calculated values and performance estimates.") }
    }
}

@Composable
fun VehicleEditScreen(nav: NavHostController, id: Long) {
    val g = graph()
    val scope = rememberCoroutineScope()
    var v by remember { mutableStateOf<VehicleEntity?>(null) }
    val log by g.db.misc().serviceLog(id).collectAsStateWithLifecycle(initialValue = emptyList())
    var history by remember { mutableStateOf<List<app.obdforge.data.DiagRecordEntity>>(emptyList()) }
    var sessionsCount by remember { mutableStateOf(0) }
    var adding by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(id) {
        withContext(Dispatchers.IO) {
            v = g.db.vehicles().get(id)
            history = g.db.misc().diagForVehicle(id)
            sessionsCount = g.db.sessions().list().count { it.vehicleId == id }
        }
    }
    val cur = v
    ListScreen(cur?.name ?: "Vehicle", nav, actions = {
        TextButton(onClick = { cur?.let { c -> scope.launch(Dispatchers.IO) { g.db.vehicles().update(c) }; nav.popBackStack() } }) { Text("Save") }
    }) {
        if (cur == null) return@ListScreen
        fun upd(f: (VehicleEntity) -> VehicleEntity) { v = f(cur) }
        item {
            SectionCard("Identity") {
                Field("Name", cur.name) { s -> upd { it.copy(name = s) } }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Field("Make", cur.make, Modifier.weight(1f)) { s -> upd { it.copy(make = s) } }
                    Field("Model", cur.model, Modifier.weight(1f)) { s -> upd { it.copy(model = s) } }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Field("Year", cur.year?.toString() ?: "", Modifier.weight(1f), KeyboardType.Number) { s -> upd { it.copy(year = s.toIntOrNull()) } }
                    Field("Mileage (km)", cur.mileageKm?.toInt()?.toString() ?: "", Modifier.weight(1f), KeyboardType.Number) { s -> upd { it.copy(mileageKm = s.toDoubleOrNull()) } }
                }
                Field("VIN", cur.vin) { s -> upd { it.copy(vin = s.uppercase()) } }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Field("Engine", cur.engine, Modifier.weight(1f)) { s -> upd { it.copy(engine = s) } }
                    Field("Engine code", cur.engineCode, Modifier.weight(1f)) { s -> upd { it.copy(engineCode = s) } }
                }
                Field("Transmission", cur.transmission) { s -> upd { it.copy(transmission = s) } }
                Text("Fuel", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { FuelKind.entries.forEach { f -> FilterChip(cur.fuelType == f.name, { upd { it.copy(fuelType = f.name) } }, label = { Text(f.label) }) } }
                if (cur.ecuInfo.isNotBlank()) KeyValue("ECUs", cur.ecuInfo)
                if (cur.adapterName.isNotBlank()) KeyValue("Adapter", cur.adapterName)
            }
        }
        item {
            SectionCard("Parameters for calculations") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Field("Displacement (L)", cur.displacementL.toString(), Modifier.weight(1f), KeyboardType.Decimal) { s -> s.toDoubleOrNull()?.let { d -> upd { it.copy(displacementL = d) } } }
                    Field("Cylinders", cur.cylinders.toString(), Modifier.weight(1f), KeyboardType.Number) { s -> s.toIntOrNull()?.let { n -> upd { it.copy(cylinders = n) } } }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Field("Mass incl. driver (kg)", cur.massKg.toString(), Modifier.weight(1f), KeyboardType.Decimal) { s -> s.toDoubleOrNull()?.let { d -> upd { it.copy(massKg = d) } } }
                    Field("Cd × area (m²)", cur.dragArea.toString(), Modifier.weight(1f), KeyboardType.Decimal) { s -> s.toDoubleOrNull()?.let { d -> upd { it.copy(dragArea = d) } } }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Field("Drivetrain loss (%)", cur.drivetrainLossPct.toString(), Modifier.weight(1f), KeyboardType.Decimal) { s -> s.toDoubleOrNull()?.let { d -> upd { it.copy(drivetrainLossPct = d) } } }
                    Field("Tyre size", cur.tyreSize, Modifier.weight(1f)) { s -> upd { it.copy(tyreSize = s) } }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("FWD", "RWD", "AWD").forEach { d -> FilterChip(cur.drivetrain == d, { upd { it.copy(drivetrain = d) } }, label = { Text(d) }) } }
                Row(verticalAlignment = Alignment.CenterVertically) { Text("Turbocharged", modifier = Modifier.weight(1f)); Switch(cur.turbocharged, { b -> upd { it.copy(turbocharged = b) } }) }
                if (cur.turbocharged) Field("Overboost limit (kPa gauge)", cur.boostLimitKpa.toString(), keyboard = KeyboardType.Decimal) { s -> s.toDoubleOrNull()?.let { d -> upd { it.copy(boostLimitKpa = d) } } }
            }
        }
        item {
            SectionCard("Manufacturer profile") {
                var open by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(onClick = { open = true }) { Text(Profiles.byId(cur.profileId)?.title ?: "Generic OBD-II") }
                    DropdownMenu(open, { open = false }) {
                        Profiles.all().forEach { p -> DropdownMenuItem(text = { Text(p.title) }, onClick = { upd { it.copy(profileId = p.id, make = it.make.ifBlank { if (p.make != "Generic") p.make else "" }) }; open = false }) }
                    }
                }
                Profiles.byId(cur.profileId)?.accessNotes?.forEach { InfoNote(it) }
            }
        }
        item {
            SectionCard("Repair & maintenance history", action = {
                TextButton(onClick = { adding = "REPAIR" }) { Text("+ Repair") }; TextButton(onClick = { adding = "MAINTENANCE" }) { Text("+ Service") }
            }) {
                if (log.isEmpty()) Text("Nothing logged.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                log.forEach { e ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text("${if (e.kind == "REPAIR") "Repair" else "Service"}: ${e.title}", style = MaterialTheme.typography.titleSmall)
                            Text(SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(e.date)) + (e.mileageKm?.let { " · ${it.toInt()} km" } ?: "") +
                                (e.cost?.let { " · cost $it" } ?: "") + if (e.relatedCodes.isNotBlank()) " · ${e.relatedCodes}" else "", style = MaterialTheme.typography.bodySmall)
                            if (e.details.isNotBlank()) Text(e.details, style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { scope.launch(Dispatchers.IO) { g.db.misc().deleteService(e.id) } }) { Text("Delete") }
                    }
                }
            }
        }
        item {
            SectionCard("Diagnostic history") {
                KeyValue("Recordings", sessionsCount.toString())
                if (history.isEmpty()) Text("No diagnostic sessions yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                history.take(30).forEach { h ->
                    Text("${SimpleDateFormat("d MMM yyyy HH:mm", Locale.getDefault()).format(Date(h.time))} · ${h.type.replace('_', ' ').lowercase()} · ${h.summary}" + if (h.simulated) " (simulated)" else "",
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp))
                }
            }
        }
        item { TextButton(onClick = { confirmDelete = true }) { Text("Delete vehicle", color = Palette.Red) } }
    }
    adding?.let { kind -> ServiceDialog(kind, onSave = { e -> scope.launch(Dispatchers.IO) { g.db.misc().insertService(e.copy(vehicleId = id)) }; adding = null }, onDismiss = { adding = null }) }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Delete this vehicle?") },
        text = { Text("Recordings stay but lose their vehicle link.") },
        confirmButton = { TextButton(onClick = { scope.launch { withContext(Dispatchers.IO) { g.db.vehicles().delete(id) }; nav.popBackStack() }; confirmDelete = false }) { Text("Delete", color = Palette.Red) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } })
}

@Composable
private fun Field(label: String, value: String, modifier: Modifier = Modifier.fillMaxWidth(), keyboard: KeyboardType = KeyboardType.Text, onChange: (String) -> Unit) {
    var text by remember(label) { mutableStateOf(value) }
    OutlinedTextField(text, { text = it; onChange(it) }, label = { Text(label) }, singleLine = true, modifier = modifier, keyboardOptions = KeyboardOptions(keyboardType = keyboard))
}

@Composable
private fun ServiceDialog(kind: String, onSave: (ServiceLogEntity) -> Unit, onDismiss: () -> Unit) {
    var title by remember { mutableStateOf("") }
    var details by remember { mutableStateOf("") }
    var km by remember { mutableStateOf("") }
    var cost by remember { mutableStateOf("") }
    var codes by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (kind == "REPAIR") "Add repair" else "Add service") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(title, { title = it }, label = { Text(if (kind == "REPAIR") "What was repaired" else "Service done (e.g. oil & filter)") })
            OutlinedTextField(details, { details = it }, label = { Text("Details / parts") })
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(km, { km = it }, label = { Text("Mileage km") }, singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(cost, { cost = it }, label = { Text("Cost") }, singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            }
            if (kind == "REPAIR") OutlinedTextField(codes, { codes = it.uppercase() }, label = { Text("Related codes (e.g. P0172)") }, singleLine = true)
        }
    }, confirmButton = {
        TextButton(enabled = title.isNotBlank(), onClick = { onSave(ServiceLogEntity(vehicleId = 0, date = System.currentTimeMillis(), kind = kind, title = title, details = details,
            mileageKm = km.toDoubleOrNull(), cost = cost.toDoubleOrNull(), relatedCodes = codes)) }) { Text("Save") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

// ------------------------------------------------------------------------------------------ Profiles

@Composable
fun ProfilesScreen(nav: NavHostController) {
    val g = graph()
    val ctx = LocalContext.current
    val version by g.profilesVersion.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            message = withContext(Dispatchers.IO) {
                runCatching {
                    val text = ctx.contentResolver.openInputStream(uri)!!.bufferedReader().readText()
                    val p = ManufacturerProfile.fromJson(text)
                    g.db.misc().putProfile(ProfileEntity(p.id, text))
                    Profiles.install(p)
                    "Imported ${p.title}: ${p.enhancedPids.size} enhanced values, ${p.dtcDescriptions.size} code descriptions."
                }.getOrElse { "Import failed: ${it.message}" }
            }
            g.profilesChanged()
        }
    }
    val all = remember(version) { Profiles.all() }
    ListScreen("Manufacturer profiles", nav) {
        item {
            SectionCard {
                InfoNote("Built-in profiles describe what standard OBD-II gives you per make and recommend channels. They contain no manufacturer-specific PIDs or codes, because those differ by ECU and must come from a verified source. Import a profile file (JSON) with your own definitions to add enhanced values and code texts.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { importer.launch(arrayOf("application/json", "text/*", "*/*")) }) { Text("Import profile") }
                    OutlinedButton(onClick = {
                        val f = File(Share.exportDir(ctx), "obdforge_profile_template.json"); f.writeText(Profiles.template("Subaru"))
                        Share.share(ctx, f, "application/json", "Profile template")
                    }) { Text("Get template") }
                }
                message?.let { Text(it, color = Palette.Cyan, style = MaterialTheme.typography.bodySmall) }
            }
        }
        items(all, key = { it.id }) { p ->
            SectionCard(p.title) {
                Text("${if (p.builtIn) "Built-in" else "Imported"} · ${p.enhancedPids.size} enhanced values · ${DtcDatabase.manufacturerCodeCount(p.make)} ${p.make} codes", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                p.accessNotes.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                if (p.enhancedPids.isNotEmpty()) {
                    Text("Enhanced values", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
                    p.enhancedPids.forEach { e -> Text("${e.name} (${e.unit}) · ${e.requestId.toString(16).uppercase()} ${e.requestHex()} · ${e.formula}", style = MaterialTheme.typography.bodySmall) }
                }
                if (!p.builtIn) TextButton(onClick = { scope.launch(Dispatchers.IO) { g.db.misc().deleteProfile(p.id); Profiles.remove(p.id); g.profilesChanged() } }) { Text("Remove", color = Palette.Red) }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------ Settings

@Composable
fun SettingsScreen(nav: NavHostController) {
    val g = graph()
    val s by g.settings.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var apiKey by remember { mutableStateOf(g.settings.apiKey) }
    var msg by remember { mutableStateOf<String?>(null) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { msg = runCatching { withContext(Dispatchers.IO) { g.backup.importFrom(uri) } }.getOrElse { "Import failed: ${it.message}" } }
    }
    ListScreen("Settings", nav) {
        item {
            SectionCard("Display") {
                Text("Theme", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { ThemeMode.entries.forEach { m -> FilterChip(s.theme == m, { g.settings.update { it.copy(theme = m) } }, label = { Text(m.name.lowercase().replaceFirstChar { c -> c.uppercase() }) }) } }
                Text("Units", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { UnitSystem.entries.forEach { u -> FilterChip(s.units == u, { g.settings.update { it.copy(units = u) } }, label = { Text(u.name.lowercase().replaceFirstChar { c -> c.uppercase() }) }) } }
                ToggleRow("Keep screen on while connected", s.keepScreenOn) { v -> g.settings.update { it.copy(keepScreenOn = v) } }
            }
        }
        item {
            SectionCard("Connection & polling") {
                Text("Refresh rate", style = MaterialTheme.typography.labelMedium)
                PollingMode.entries.forEach { m ->
                    Row(Modifier.fillMaxWidth().clickable { g.settings.update { it.copy(polling = m) } }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(s.polling == m, { g.settings.update { it.copy(polling = m) } }); Text(m.label)
                    }
                }
                InfoNote("The real rate is limited by the adapter and ECU. Temperatures and levels are always read less often to leave bandwidth for fast values.")
                ToggleRow("Reconnect automatically after drop-outs", s.autoReconnect) { v -> g.settings.update { it.copy(autoReconnect = v) } }
                ToggleRow("Connect to last adapter when the app opens", s.autoConnectOnStart) { v -> g.settings.update { it.copy(autoConnectOnStart = v) } }
            }
        }
        item {
            SectionCard("Recording") {
                ToggleRow("Start recording automatically", s.autoRecordOnConnect) { v -> g.settings.update { it.copy(autoRecordOnConnect = v) } }
                if (s.autoRecordOnConnect) {
                    Text("Start when speed reaches (0 = as soon as connected): ${s.autoRecordMinSpeed.toInt()} km/h", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(0.0, 5.0, 20.0).forEach { v -> FilterChip(s.autoRecordMinSpeed == v, { g.settings.update { it.copy(autoRecordMinSpeed = v) } }, label = { Text("${v.toInt()}") }) } }
                    Text("Stop after engine off for ${s.autoStopAfterEngineOffS} s", style = MaterialTheme.typography.bodySmall)
                }
                Text("Logging interval", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0L to "Every sample", 200L to "0.2 s", 1000L to "1 s", 5000L to "5 s").forEach { (ms, l) -> FilterChip(s.loggingIntervalMs == ms, { g.settings.update { it.copy(loggingIntervalMs = ms) } }, label = { Text(l) }) }
                }
            }
        }
        item {
            SectionCard("Data & backup") {
                ToggleRow("Daily automatic backup (keeps 5)", s.dailyBackup) { v -> g.settings.update { it.copy(dailyBackup = v) } }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        scope.launch {
                            val f = withContext(Dispatchers.IO) { File(Share.exportDir(ctx), "OBDForge_backup_${SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())}.zip").also { f -> f.outputStream().use { g.backup.export(it) } } }
                            Share.share(ctx, f, "application/zip", "Save backup")
                        }
                    }) { Text("Export backup") }
                    OutlinedButton(onClick = { importer.launch(arrayOf("application/zip", "*/*")) }) { Text("Import backup") }
                }
                val autos = remember { g.backup.autoBackups() }
                if (autos.isNotEmpty()) Text("Automatic backups: " + autos.joinToString { it.name }, style = MaterialTheme.typography.bodySmall)
                msg?.let { Text(it, color = Palette.Cyan, style = MaterialTheme.typography.bodySmall) }
            }
        }
        item {
            SectionCard("Cloud assistant (optional)") {
                InfoNote("Leave empty to use only the offline assistant. With a key, questions and the vehicle data shown on the assistant screen are sent to Anthropic when you choose the cloud model.")
                OutlinedTextField(apiKey, { apiKey = it }, label = { Text("Anthropic API key") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { g.settings.apiKey = apiKey; msg = "API key saved" }) { Text("Save key") }
                    var model by remember { mutableStateOf(s.cloudModel) }
                    OutlinedTextField(model, { model = it; g.settings.update { st -> st.copy(cloudModel = it) } }, label = { Text("Model") }, singleLine = true, modifier = Modifier.weight(1f))
                }
            }
        }
        item {
            SectionCard("Safety") {
                ToggleRow("Expert mode (allow ECU-changing terminal commands)", s.expertMode) { v -> g.settings.update { it.copy(expertMode = v) } }
                InfoNote("Clearing codes, Mode 08 tests and ECU-changing commands always need confirmation. Expert mode only unlocks raw commands in the terminal; use it only if you know exactly what a command does.", Palette.Amber)
            }
        }
        item {
            SectionCard("About") {
                KeyValue("Version", BuildConfig.VERSION_NAME)
                KeyValue("PID definitions", "${Pids.all.size} standard PIDs, ${Pids.channels.size} channels")
                KeyValue("Generic trouble codes", DtcDatabase.size.toString())
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(value, onChange)
    }
}

