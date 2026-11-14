package app.obdforge.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import app.obdforge.AppGraph
import app.obdforge.core.analysis.Series
import app.obdforge.core.live.VehicleSpec
import app.obdforge.core.perf.FreeRevTest
import app.obdforge.core.perf.PerfResult
import app.obdforge.core.perf.PerfTestType
import app.obdforge.core.perf.PerformanceTimer
import app.obdforge.core.util.Json
import app.obdforge.data.PerfResultEntity
import app.obdforge.ui.components.Chip
import app.obdforge.ui.components.InfoNote
import app.obdforge.ui.components.RequireChannels
import app.obdforge.ui.components.SectionCard
import app.obdforge.ui.components.SimBadge
import app.obdforge.ui.components.XyChart
import app.obdforge.ui.components.graph
import app.obdforge.ui.theme.Palette
import app.obdforge.util.Units
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun PerformanceScreen(nav: NavHostController) {
    val g = graph()
    val values by g.live.values.collectAsStateWithLifecycle()
    val settings by g.settings.state.collectAsStateWithLifecycle()
    val vehicle by g.activeVehicle.collectAsStateWithLifecycle()
    val history by g.db.misc().perfResults().collectAsStateWithLifecycle(initialValue = emptyList())
    val gpsOk by g.gps.available.collectAsStateWithLifecycle()
    val gpsAcc by g.gps.accuracyM.collectAsStateWithLifecycle()
    var type by rememberSaveable { mutableStateOf(PerfTestType.ZERO_100) }
    var useGps by rememberSaveable { mutableStateOf(true) }
    var accepted by rememberSaveable { mutableStateOf(false) }
    var stationary by rememberSaveable { mutableStateOf(false) }
    val spec = vehicle?.let { AppGraph.specOf(it) } ?: VehicleSpec()
    var timer by remember(type, spec) { mutableStateOf(PerformanceTimer(type, spec)) }
    val freeRev = remember { FreeRevTest() }
    var tick by remember { mutableIntStateOf(0) }
    var result by remember { mutableStateOf<PerfResult?>(null) }
    var showSafety by remember { mutableStateOf(false) }
    @Suppress("UNUSED_VARIABLE") val redrawTick = tick
    val scope = rememberCoroutineScope()
    val locLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) { g.gps.release(); g.gps.acquire() } }
    RequireChannels("perf", listOf("speed", "rpm", "boost", "tps", "gps_speed"))
    DisposableEffect(Unit) {
        if (!g.gps.hasPermission()) locLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        g.gps.acquire()
        onDispose { g.gps.release() }
    }

    val gpsSpeed = values["gps_speed"]
    val obdSpeed = values["speed"]
    val src = if (useGps && gpsSpeed != null && System.currentTimeMillis() - gpsSpeed.time < 2000) "GPS" else "OBD"
    val sp = if (src == "GPS") gpsSpeed else obdSpeed
    val rpm = values["rpm"]?.value
    LaunchedEffect(sp?.time, accepted) {
        if (!accepted || sp == null) return@LaunchedEffect
        if (stationary) {
            if (rpm != null) freeRev.feed(sp.time, rpm, obdSpeed?.value)
        } else {
            val before = timer.state
            timer.feed(sp.time, sp.value, rpm, src)
            if (timer.state != before) tick++
            if (timer.state == PerformanceTimer.State.DONE && before != PerformanceTimer.State.DONE) {
                val r = timer.result!!
                result = r
                scope.launch(Dispatchers.IO) {
                    g.db.misc().insertPerf(PerfResultEntity(vehicleId = vehicle?.id, time = System.currentTimeMillis(), type = r.type.name, elapsedS = r.elapsedS,
                        trapSpeedKmh = r.trapSpeedKmh, distanceM = r.distanceM, speedSource = r.speedSource, peakKw = r.peakCrankKw, peakNm = r.peakCrankNm,
                        json = Json.write(mapOf("points" to r.points.map { listOf(it.tMs, it.speedKmh, it.rpm) }, "power" to r.power.map { listOf(it.rpm, it.speedKmh, it.crankKw, it.crankNm) }, "notes" to r.notes)),
                        simulated = g.connection.isSimulated))
                }
            }
        }
    }

    ListScreen("Performance", nav, actions = { if (g.connection.isSimulated) SimBadge() }) {
        item {
            SectionCard {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(!stationary, { stationary = false }, label = { Text("Acceleration") })
                    FilterChip(stationary, { stationary = true }, label = { Text("Stationary free-rev") })
                }
                if (!stationary) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PerfTestType.entries.forEach { t -> FilterChip(type == t, { type = t; result = null }, label = { Text(t.label) }) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Speed source: $src" + if (src == "GPS") gpsAcc?.let { " (±${it.toInt()} m)" } ?: "" else "", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    FilterChip(useGps, { useGps = !useGps }, label = { Text("Prefer GPS") })
                }
                if (useGps && !gpsOk) InfoNote("Waiting for a GPS fix (needs location permission and a view of the sky). OBD speed is used until then.", Palette.Amber)
            }
        }
        item {
            SectionCard {
                if (!accepted) {
                    Button(onClick = { showSafety = true }) { Text("Arm test") }
                } else if (stationary) {
                    Text("Park the vehicle in neutral/park. Rev from idle past 3000 rpm and release.", style = MaterialTheme.typography.bodyMedium)
                    Text("RPM ${rpm?.toInt() ?: "--"}", fontFamily = FontFamily.Monospace, fontSize = 40.sp, fontWeight = FontWeight.Bold)
                    Text("Rise 1200→3000 rpm: ${freeRev.riseS?.let { String.format(Locale.US, "%.2f s", it) } ?: "--"}   Fall 3000→1200: ${freeRev.fallS?.let { String.format(Locale.US, "%.2f s", it) } ?: "--"}")
                    OutlinedButton(onClick = { freeRev.reset(); tick++ }) { Text("Reset") }
                    InfoNote("Limited by the logging rate; compare runs done the same way (before/after a repair).")
                } else {
                    val stateText = when (timer.state) {
                        PerformanceTimer.State.WAITING_TO_ARM -> if (type.standingStart) "Stop the vehicle to arm" else "Slow below ${(type.fromKmh - 5).toInt()} km/h to arm"
                        PerformanceTimer.State.ARMED -> "ARMED — timing starts when the vehicle moves"
                        PerformanceTimer.State.RUNNING -> "RUNNING"
                        PerformanceTimer.State.DONE -> "Finished"
                        PerformanceTimer.State.ABORTED -> "Aborted: ${timer.abortReason}"
                    }
                    Text(stateText, color = when (timer.state) { PerformanceTimer.State.ARMED -> Palette.Green; PerformanceTimer.State.RUNNING -> Palette.Orange; PerformanceTimer.State.ABORTED -> Palette.Red; else -> MaterialTheme.colorScheme.onSurface },
                        style = MaterialTheme.typography.titleMedium)
                    Text(Units.speed(sp?.value ?: 0.0, settings.units), fontFamily = FontFamily.Monospace, fontSize = 56.sp, fontWeight = FontWeight.Bold)
                    result?.let { r -> Text(String.format(Locale.US, "%.2f s", r.elapsedS), fontFamily = FontFamily.Monospace, fontSize = 40.sp, color = Palette.Cyan) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { timer = PerformanceTimer(type, spec); result = null; tick++ }) { Text("Reset") }
                        TextButton(onClick = { accepted = false }) { Text("Disarm") }
                    }
                }
            }
        }
        result?.let { r ->
            item {
                SectionCard("Result: ${r.type.label}") {
                    Text(String.format(Locale.US, "%.2f s", r.elapsedS) + (r.trapSpeedKmh?.let { " · trap ${Units.speed(it, settings.units)}" } ?: "") + " · ${r.distanceM.toInt()} m",
                        style = MaterialTheme.typography.titleMedium)
                    r.peakCrankKw?.let { Text(String.format(Locale.US, "Estimated peak power %.0f kW (%.0f hp) at the crank", it, it * 1.341), style = MaterialTheme.typography.bodyMedium) }
                    r.peakCrankNm?.let { Text(String.format(Locale.US, "Estimated peak torque %.0f Nm", it), style = MaterialTheme.typography.bodyMedium) }
                    Chip("estimate", Palette.Purple)
                    r.notes.forEach { InfoNote(it) }
                    val speedSeries = Series("speed", r.points.map { it.tMs }.toLongArray(), r.points.map { it.speedKmh }.toDoubleArray())
                    val rpmPts = r.power.filter { it.rpm != null }
                    if (rpmPts.size > 3) {
                        Text("Estimated power vs RPM", style = MaterialTheme.typography.titleSmall)
                        val t = rpmPts.indices.map { it.toLong() }.toLongArray()
                        XyChart(Series("rpm", t, rpmPts.map { it.rpm!! }.toDoubleArray()), Series("power", t, rpmPts.map { it.crankKw }.toDoubleArray()), height = 200.dp)
                    }
                    Text("Speed trace", style = MaterialTheme.typography.titleSmall)
                    app.obdforge.ui.components.TimeChart(listOf(speedSeries), height = 160.dp, units = settings.units)
                }
            }
        }
        item {
            SectionCard("Vehicle parameters used") {
                Text("Mass ${spec.massKg.toInt()} kg · CdA ${spec.dragArea} m² · drivetrain loss ${spec.drivetrainLossPct.toInt()} % · ${spec.displacementL} L", style = MaterialTheme.typography.bodySmall)
                vehicle?.let { v -> TextButton(onClick = { nav.navigate("vehicle/${v.id}") }) { Text("Edit vehicle parameters") } }
            }
        }
        item {
            SectionCard("Safety") {
                InfoNote("Run acceleration tests only on a closed course or where it is legal and safe. The driver must not look at or touch the phone: mount it and let it arm and time automatically, or have a passenger operate it.", Palette.Amber)
            }
        }
        if (history.isNotEmpty()) item { Text("History", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
        items(history.take(30), key = { it.id }) { h ->
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${runCatching { PerfTestType.valueOf(h.type).label }.getOrDefault(h.type)}: ${String.format(Locale.US, "%.2f s", h.elapsedS)}", style = MaterialTheme.typography.titleSmall)
                        Text(SimpleDateFormat("d MMM yyyy HH:mm", Locale.getDefault()).format(Date(h.time)) + " · ${h.speedSource}" +
                            (h.peakKw?.let { String.format(Locale.US, " · ~%.0f kW", it) } ?: ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (h.simulated) SimBadge()
                    TextButton(onClick = { scope.launch(Dispatchers.IO) { g.db.misc().deletePerf(h.id) } }) { Text("Delete") }
                }
            }
        }
    }
    if (showSafety) AlertDialog(
        onDismissRequest = { showSafety = false },
        title = { Text("Before you start") },
        text = { Text("Only test on a closed course or where it is legal and safe. The driver must keep eyes on the road: mount the phone so the test arms and times itself, or let a passenger operate the app. Estimated power figures are approximations.") },
        confirmButton = { TextButton(onClick = { accepted = true; showSafety = false }) { Text("I understand") } },
        dismissButton = { TextButton(onClick = { showSafety = false }) { Text("Cancel") } },
    )
}
