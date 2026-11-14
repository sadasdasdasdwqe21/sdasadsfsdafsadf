package app.obdforge.data

import android.content.Context
import android.content.SharedPreferences
import app.obdforge.core.alert.AlertCondition
import app.obdforge.core.alert.AlertEngine
import app.obdforge.core.alert.AlertRule
import app.obdforge.core.util.Json
import app.obdforge.core.util.asDouble
import app.obdforge.core.util.asList
import app.obdforge.core.util.asMap
import app.obdforge.core.util.asStr
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ThemeMode { SYSTEM, DARK, LIGHT }
enum class UnitSystem { METRIC, IMPERIAL }
enum class PollingMode(val label: String, val fastMs: Long, val slowMs: Long) {
    FASTEST("Fastest (no delay)", 0, 1000),
    BALANCED("Balanced", 100, 2000),
    SAVER("Battery saver", 500, 5000),
}

/** Last adapter used, so the app can reconnect automatically. */
data class LastAdapter(val kind: String, val address: String, val name: String, val port: Int = 35000)

data class AppSettings(
    val theme: ThemeMode = ThemeMode.DARK,
    val units: UnitSystem = UnitSystem.METRIC,
    val polling: PollingMode = PollingMode.FASTEST,
    val autoReconnect: Boolean = true,
    val autoConnectOnStart: Boolean = false,
    val keepScreenOn: Boolean = true,
    val expertMode: Boolean = false,
    val preferredProtocol: String = "AUTO",
    val isoBaud: Int = 10400,
    val autoRecordOnConnect: Boolean = false,
    val autoRecordMinSpeed: Double = 0.0,
    val autoStopAfterEngineOffS: Int = 60,
    val loggingIntervalMs: Long = 0,
    val activeVehicleId: Long? = null,
    val lastAdapter: LastAdapter? = null,
    val cloudModel: String = "claude-sonnet-5-5",
    val dailyBackup: Boolean = true,
    val lastBackupDay: Long = 0,
)

class SettingsStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val secrets: SharedPreferences = context.getSharedPreferences("secrets", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppSettings> = _state
    val value: AppSettings get() = _state.value

    private fun load(): AppSettings {
        val p = prefs
        val last = p.getString("last_kind", null)?.let {
            LastAdapter(it, p.getString("last_addr", "") ?: "", p.getString("last_name", "") ?: "", p.getInt("last_port", 35000))
        }
        return AppSettings(
            theme = runCatching { ThemeMode.valueOf(p.getString("theme", "DARK")!!) }.getOrDefault(ThemeMode.DARK),
            units = runCatching { UnitSystem.valueOf(p.getString("units", "METRIC")!!) }.getOrDefault(UnitSystem.METRIC),
            polling = runCatching { PollingMode.valueOf(p.getString("polling", "FASTEST")!!) }.getOrDefault(PollingMode.FASTEST),
            autoReconnect = p.getBoolean("auto_reconnect", true),
            autoConnectOnStart = p.getBoolean("auto_connect", false),
            keepScreenOn = p.getBoolean("keep_screen_on", true),
            expertMode = p.getBoolean("expert", false),
            preferredProtocol = p.getString("protocol", "AUTO") ?: "AUTO",
            isoBaud = p.getInt("iso_baud", 10400),
            autoRecordOnConnect = p.getBoolean("auto_record", false),
            autoRecordMinSpeed = p.getFloat("auto_record_speed", 0f).toDouble(),
            autoStopAfterEngineOffS = p.getInt("auto_stop_s", 60),
            loggingIntervalMs = p.getLong("log_interval", 0),
            activeVehicleId = p.getLong("active_vehicle", -1).takeIf { it >= 0 },
            lastAdapter = last,
            cloudModel = p.getString("cloud_model", "claude-sonnet-5-5") ?: "claude-sonnet-5-5",
            dailyBackup = p.getBoolean("daily_backup", true),
            lastBackupDay = p.getLong("last_backup_day", 0),
        )
    }

    fun update(f: (AppSettings) -> AppSettings) {
        val n = f(_state.value)
        prefs.edit().apply {
            putString("theme", n.theme.name); putString("units", n.units.name); putString("polling", n.polling.name)
            putBoolean("auto_reconnect", n.autoReconnect); putBoolean("auto_connect", n.autoConnectOnStart)
            putBoolean("keep_screen_on", n.keepScreenOn); putBoolean("expert", n.expertMode)
            putString("protocol", n.preferredProtocol); putInt("iso_baud", n.isoBaud)
            putBoolean("auto_record", n.autoRecordOnConnect); putFloat("auto_record_speed", n.autoRecordMinSpeed.toFloat())
            putInt("auto_stop_s", n.autoStopAfterEngineOffS); putLong("log_interval", n.loggingIntervalMs)
            putLong("active_vehicle", n.activeVehicleId ?: -1)
            if (n.lastAdapter != null) {
                putString("last_kind", n.lastAdapter.kind); putString("last_addr", n.lastAdapter.address)
                putString("last_name", n.lastAdapter.name); putInt("last_port", n.lastAdapter.port)
            } else remove("last_kind")
            putString("cloud_model", n.cloudModel); putBoolean("daily_backup", n.dailyBackup); putLong("last_backup_day", n.lastBackupDay)
        }.apply()
        _state.value = n
    }

    var apiKey: String
        get() = secrets.getString("anthropic_key", "") ?: ""
        set(v) { secrets.edit().putString("anthropic_key", v.trim()).apply() }

    // Alert rules are stored as JSON so users can edit thresholds.
    fun alertRules(): List<AlertRule> {
        val text = prefs.getString("alert_rules", null) ?: return AlertEngine.defaults()
        return runCatching {
            Json.parse(text).asList().map { e ->
                val m = e.asMap()
                AlertRule(
                    id = m["id"].asStr()!!, title = m["title"].asStr() ?: "", channelKey = m["channel"].asStr()!!,
                    condition = AlertCondition.valueOf(m["condition"].asStr()!!), threshold = m["threshold"].asDouble()!!,
                    threshold2 = m["threshold2"].asDouble() ?: Double.NaN, sustainMs = m["sustainMs"].asDouble()?.toLong() ?: 2000,
                    cooldownMs = m["cooldownMs"].asDouble()?.toLong() ?: 60000, enabled = m["enabled"] as? Boolean ?: true,
                    critical = m["critical"] as? Boolean ?: false, minRpm = m["minRpm"].asDouble(),
                )
            }
        }.getOrElse { AlertEngine.defaults() }
    }

    fun saveAlertRules(rules: List<AlertRule>) {
        prefs.edit().putString("alert_rules", Json.write(rules.map {
            mapOf("id" to it.id, "title" to it.title, "channel" to it.channelKey, "condition" to it.condition.name, "threshold" to it.threshold,
                "threshold2" to if (it.threshold2.isNaN()) null else it.threshold2, "sustainMs" to it.sustainMs, "cooldownMs" to it.cooldownMs,
                "enabled" to it.enabled, "critical" to it.critical, "minRpm" to it.minRpm)
        })).apply()
    }

    fun pinned(): Set<String> = prefs.getStringSet("pinned", setOf("rpm", "speed", "ect", "boost"))!!.toSet()
    fun setPinned(s: Set<String>) { prefs.edit().putStringSet("pinned", s).apply() }
}
