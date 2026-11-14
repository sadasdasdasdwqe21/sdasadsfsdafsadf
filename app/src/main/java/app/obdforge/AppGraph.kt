package app.obdforge

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import app.obdforge.alerts.AlertCenter
import app.obdforge.connection.AdapterScanner
import app.obdforge.connection.ConnectionManager
import app.obdforge.connection.LinkState
import app.obdforge.connection.WifiNetworkBinder
import app.obdforge.core.live.FuelKind
import app.obdforge.core.live.VehicleSpec
import app.obdforge.core.mfr.ManufacturerProfile
import app.obdforge.core.mfr.Profiles
import app.obdforge.data.AppDatabase
import app.obdforge.data.BackupManager
import app.obdforge.data.DashboardCodec
import app.obdforge.data.DashboardEntity
import app.obdforge.data.SettingsStore
import app.obdforge.data.VehicleEntity
import app.obdforge.live.DiagnosticsManager
import app.obdforge.live.LiveEngine
import app.obdforge.record.Recorder
import app.obdforge.service.ObdService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Application-wide objects (manual dependency injection). */
class AppGraph(val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db = AppDatabase.create(context)
    val settings = SettingsStore(context)
    val scanner = AdapterScanner(context)
    val wifiBinder = WifiNetworkBinder(context)
    val connection = ConnectionManager(context, scope, settings, scanner, wifiBinder)
    val live = LiveEngine(connection, settings, scope)
    val recorder = Recorder(db, live, connection, settings, scope)
    val alerts = AlertCenter(context, db, settings, live, scope)
    val diagnostics = DiagnosticsManager(connection, db, settings, scope) { codes -> alerts.checkDtcs(codes) }
    val backup = BackupManager(context, db)
    val gps = GpsTracker(context) { kmh, t -> live.pushGps(kmh, t) }

    val vehicles = db.vehicles().all().stateIn(scope, SharingStarted.Eagerly, emptyList())
    val activeVehicle: StateFlow<VehicleEntity?> = combine(vehicles, settings.state) { list, s -> list.firstOrNull { it.id == s.activeVehicleId } ?: list.firstOrNull() }
        .stateIn(scope, SharingStarted.Eagerly, null)

    private val _profilesVersion = MutableStateFlow(0)
    val profilesVersion: StateFlow<Int> = _profilesVersion

    init {
        scope.launch(Dispatchers.IO) {
            db.misc().profiles().forEach { p -> runCatching { Profiles.install(ManufacturerProfile.fromJson(p.json)) } }
            _profilesVersion.value++
            if (db.misc().dashboardList().isEmpty()) {
                DashboardCodec.presets().forEachIndexed { i, (name, gauges) ->
                    db.misc().insertDashboard(DashboardEntity(name = name, position = i, json = DashboardCodec.encode(2, gauges), preset = true))
                }
            }
            runCatching { backup.dailyBackupIfDue(settings) }
        }
        // Vehicle spec and enhanced PIDs follow the active vehicle.
        scope.launch {
            combine(activeVehicle, profilesVersion) { v, _ -> v }.collect { v ->
                if (v != null) {
                    live.setSpec(specOf(v))
                    live.enhanced = Profiles.byId(v.profileId)?.enhancedPids ?: emptyList()
                }
            }
        }
        // Keep the process alive in the background while connected; match the vehicle by VIN.
        scope.launch {
            var active = false
            connection.state.collect { st ->
                val wantService = st is LinkState.Connecting || st is LinkState.Connected || st is LinkState.Reconnecting
                if (wantService && !active) ObdService.start(context)
                active = wantService
                if (st is LinkState.Connected) launch(Dispatchers.IO) { associateVehicle(st) }
            }
        }
    }

    private suspend fun associateVehicle(st: LinkState.Connected) {
        val vin = st.report.vin?.takeIf { it.length == 17 } ?: return
        val existing = db.vehicles().byVin(vin)
        if (existing != null) {
            if (settings.value.activeVehicleId != existing.id) settings.update { it.copy(activeVehicleId = existing.id) }
            return
        }
        val active = activeVehicle.value
        if (active != null && active.vin.isBlank()) {
            db.vehicles().update(active.copy(vin = vin, ecuInfo = st.report.ecus.joinToString { it.name }))
        } else {
            val id = db.vehicles().insert(VehicleEntity(name = if (st.target is app.obdforge.connection.AdapterTarget.Simulator) "Simulated car" else "Vehicle ${vin.takeLast(6)}",
                vin = vin, ecuInfo = st.report.ecus.joinToString { it.name }, adapterName = st.target.label))
            settings.update { it.copy(activeVehicleId = id) }
        }
    }

    fun profilesChanged() { _profilesVersion.value++ }

    companion object {
        fun specOf(v: VehicleEntity) = VehicleSpec(
            displacementL = v.displacementL, fuel = runCatching { FuelKind.valueOf(v.fuelType) }.getOrDefault(FuelKind.GASOLINE),
            massKg = v.massKg, dragArea = v.dragArea, drivetrainLossPct = v.drivetrainLossPct, turbocharged = v.turbocharged,
            boostLimitKpa = v.boostLimitKpa, cylinders = v.cylinders,
        )
    }
}

/** Phone GPS speed for performance timing and as a cross-check of OBD speed. */
class GpsTracker(private val context: Context, private val onSpeed: (Double, Long) -> Unit) {
    private val lm = context.getSystemService(LocationManager::class.java)
    private var users = 0
    val available = MutableStateFlow(false)
    val accuracyM = MutableStateFlow<Float?>(null)
    private val listener = LocationListener { loc: Location ->
        if (loc.hasSpeed()) onSpeed(loc.speed * 3.6, System.currentTimeMillis())
        accuracyM.value = if (loc.hasAccuracy()) loc.accuracy else null
        available.value = true
    }

    fun hasPermission() = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    @Synchronized fun acquire() {
        users++
        if (users == 1 && hasPermission()) runCatching { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 100L, 0f, listener, Looper.getMainLooper()) }
    }

    @Synchronized fun release() {
        users = (users - 1).coerceAtLeast(0)
        if (users == 0) { runCatching { lm.removeUpdates(listener) }; available.value = false }
    }
}
