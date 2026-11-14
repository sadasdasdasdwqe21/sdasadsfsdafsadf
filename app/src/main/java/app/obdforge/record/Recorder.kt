package app.obdforge.record

import androidx.room.withTransaction
import app.obdforge.connection.ConnectionManager
import app.obdforge.connection.LinkState
import app.obdforge.core.analysis.Series
import app.obdforge.core.live.Sample
import app.obdforge.data.AppDatabase
import app.obdforge.data.SampleEntity
import app.obdforge.data.SessionEntity
import app.obdforge.data.SettingsStore
import app.obdforge.live.LiveEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RecordingState(
    val sessionId: Long,
    val title: String,
    val startedAt: Long,
    val channels: Set<String>,
    val samples: Long,
    val simulated: Boolean,
)

/**
 * Writes live samples to the database in one-second batches. A crash can lose at most the last
 * batch; sessions left in RECORDING state are marked RECOVERED at the next start so the data
 * already written is kept and clearly flagged.
 */
class Recorder(
    private val db: AppDatabase,
    private val engine: LiveEngine,
    private val conn: ConnectionManager,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<RecordingState?>(null)
    val state: StateFlow<RecordingState?> = _state
    private val buffer = ArrayList<SampleEntity>()
    private val lastWritten = HashMap<String, Long>()
    private val mutex = Mutex()
    private var engineOffSince: Long? = null

    init {
        engine.addListener { onSamples(it) }
        scope.launch(Dispatchers.IO) {
            db.sessions().unfinished().forEach { s ->
                val last = db.sessions().lastT(s.id)
                db.sessions().update(s.copy(status = "RECOVERED", endedAt = last?.let { s.startedAt + it } ?: s.startedAt,
                    sampleCount = db.sessions().count(s.id), description = (s.description + "\nRecovered after the app was closed while recording.").trim()))
            }
        }
        scope.launch(Dispatchers.IO) {
            while (isActive) { delay(1000); flush() }
        }
        scope.launch {
            conn.state.collect { st ->
                when (st) {
                    is LinkState.Connected -> {
                        if (settings.value.autoRecordOnConnect && _state.value == null && settings.value.autoRecordMinSpeed <= 0.0)
                            start(null, null, defaultChannels(), "auto: on connect")
                    }
                    is LinkState.Idle, is LinkState.Failed -> if (_state.value != null) stop()
                    else -> Unit // keep recording through short reconnects
                }
            }
        }
    }

    fun defaultChannels(): Set<String> = setOf("rpm", "speed", "load", "ect", "iat", "map", "baro", "boost", "maf", "tps", "stft1", "ltft1",
        "stft2", "ltft2", "timing", "frp", "lambda_cmd", "o2_b1s1_v", "o2w1_lambda", "o2c1_lambda", "ecu_voltage", "fuel_rate", "trim_total1", "gps_speed")

    private fun onSamples(samples: List<Sample>) {
        val rec = _state.value
        if (rec == null) {
            checkAutoStart(samples); return
        }
        val interval = settings.value.loggingIntervalMs
        val toAdd = ArrayList<SampleEntity>()
        synchronized(buffer) {
            for (s in samples) {
                if (s.key !in rec.channels) continue
                val t = s.timeMs - rec.startedAt
                if (t < 0) continue
                if (interval > 0 && t - (lastWritten[s.key] ?: Long.MIN_VALUE / 2) < interval) continue
                lastWritten[s.key] = t
                toAdd.add(SampleEntity(rec.sessionId, s.key, t, s.value))
            }
            buffer.addAll(toAdd)
        }
        if (toAdd.isNotEmpty()) _state.value = rec.copy(samples = rec.samples + toAdd.size)
        checkAutoStop(samples)
    }

    private fun checkAutoStart(samples: List<Sample>) {
        val st = settings.value
        if (!st.autoRecordOnConnect || st.autoRecordMinSpeed <= 0.0) return
        val sp = samples.firstOrNull { it.key == "speed" }?.value ?: return
        if (sp >= st.autoRecordMinSpeed) scope.launch { start(null, null, defaultChannels(), "auto: speed ≥ ${st.autoRecordMinSpeed.toInt()} km/h") }
    }

    private fun checkAutoStop(samples: List<Sample>) {
        val rec = _state.value ?: return
        if (!rec.title.startsWith("Auto")) return
        val rpm = samples.firstOrNull { it.key == "rpm" }?.value ?: return
        val now = System.currentTimeMillis()
        if (rpm < 100) {
            val since = engineOffSince ?: now.also { engineOffSince = it }
            if (now - since > settings.value.autoStopAfterEngineOffS * 1000L) scope.launch { stop() }
        } else engineOffSince = null
    }

    suspend fun start(title: String?, description: String?, channels: Set<String>, trigger: String = "manual"): Long = mutex.withLock {
        _state.value?.let { return it.sessionId }
        val now = System.currentTimeMillis()
        val t = title ?: ((if (trigger.startsWith("auto")) "Auto " else "") + "Log " + SimpleDateFormat("d MMM yyyy HH:mm", Locale.getDefault()).format(Date(now)))
        val sim = conn.isSimulated
        val id = db.sessions().insert(SessionEntity(vehicleId = settings.value.activeVehicleId, title = t, description = description ?: "",
            startedAt = now, simulated = sim, channels = channels.joinToString(","), trigger = trigger))
        synchronized(buffer) { buffer.clear(); lastWritten.clear() }
        engineOffSince = null
        engine.require("recorder", channels)
        _state.value = RecordingState(id, t, now, channels, 0, sim)
        id
    }

    suspend fun stop() = mutex.withLock {
        val rec = _state.value ?: return@withLock
        _state.value = null
        engine.release("recorder")
        flush(rec.sessionId)
        val count = db.sessions().count(rec.sessionId)
        db.sessions().get(rec.sessionId)?.let { db.sessions().update(it.copy(status = "COMPLETE", endedAt = System.currentTimeMillis(), sampleCount = count)) }
    }

    private suspend fun flush(sessionId: Long? = null) {
        val batch: List<SampleEntity>
        synchronized(buffer) {
            if (buffer.isEmpty()) return
            batch = ArrayList(buffer); buffer.clear()
        }
        val target = sessionId ?: batch.first().sessionId
        db.withTransaction { db.sessions().insertSamples(batch.filter { it.sessionId == target || sessionId == null }) }
    }

    /** Loads a session's channels as series (times relative to session start). */
    suspend fun load(sessionId: Long, keys: Collection<String>? = null): Map<String, Series> {
        val dao = db.sessions()
        val channels = keys ?: dao.channelCounts(sessionId).map { it.channel }
        return channels.associateWith { k ->
            val rows = dao.samples(sessionId, k)
            Series(k, LongArray(rows.size) { rows[it].t }, DoubleArray(rows.size) { rows[it].v })
        }.filterValues { it.size > 0 }
    }
}
