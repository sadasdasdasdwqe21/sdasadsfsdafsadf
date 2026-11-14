package app.obdforge.alerts

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.obdforge.R
import app.obdforge.core.alert.AlertEngine
import app.obdforge.core.alert.AlertEvent
import app.obdforge.core.alert.AlertRule
import app.obdforge.data.AlertEventEntity
import app.obdforge.data.AppDatabase
import app.obdforge.data.SettingsStore
import app.obdforge.live.LiveEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Evaluates alert rules once per second against live values; logs and notifies. */
class AlertCenter(
    private val context: Context,
    private val db: AppDatabase,
    private val settings: SettingsStore,
    private val engine: LiveEngine,
    private val scope: CoroutineScope,
) {
    private val alertEngine = AlertEngine(settings.alertRules())
    private val _rules = MutableStateFlow(alertEngine.rules)
    val rules: StateFlow<List<AlertRule>> = _rules
    private val _events = MutableSharedFlow<AlertEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<AlertEvent> = _events
    private var notifId = 2000

    companion object { const val CHANNEL = "alerts" }

    init {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Vehicle alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Warnings when a reading crosses your limits or a new trouble code appears"
            })
        }
        engine.require("alerts", alertEngine.rules.filter { it.enabled }.map { it.channelKey })
        scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(1000)
                val latest = engine.latestMap()
                if (latest.isEmpty()) continue
                alertEngine.evaluate(latest, System.currentTimeMillis()).forEach { fire(it) }
            }
        }
    }

    fun setRules(r: List<AlertRule>) {
        alertEngine.rules = r
        _rules.value = r
        settings.saveAlertRules(r)
        engine.require("alerts", r.filter { it.enabled }.map { it.channelKey })
    }

    fun resetDefaults() = setRules(AlertEngine.defaults())

    fun checkDtcs(codes: List<String>) {
        alertEngine.checkDtcs(codes, System.currentTimeMillis()).forEach { fire(it) }
    }

    private fun fire(e: AlertEvent) {
        _events.tryEmit(e)
        scope.launch(Dispatchers.IO) {
            db.misc().insertAlert(AlertEventEntity(time = e.timeMs, ruleId = e.ruleId, title = e.title, message = e.message, value = e.value,
                critical = e.critical, vehicleId = settings.value.activeVehicleId))
        }
        val allowed = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (allowed) runCatching {
            val n = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_obd)
                .setContentTitle(e.title)
                .setContentText(e.message)
                .setPriority(if (e.critical) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify(notifId++, n)
        }
    }
}
