package app.obdforge.live

import app.obdforge.connection.ConnectionManager
import app.obdforge.connection.LinkState
import app.obdforge.core.analysis.Series
import app.obdforge.core.elm.ElmTimeoutException
import app.obdforge.core.live.DerivedCalculator
import app.obdforge.core.live.LivePoller
import app.obdforge.core.live.Sample
import app.obdforge.core.live.VehicleSpec
import app.obdforge.core.mfr.EnhancedPid
import app.obdforge.core.obd.Priority
import app.obdforge.core.pid.Mode06
import app.obdforge.core.pid.Pids
import app.obdforge.core.transport.TransportException
import app.obdforge.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Current value of a channel plus running statistics since the last reset. */
data class LiveValue(val value: Double, val time: Long, val min: Double, val max: Double, val sum: Double, val count: Long) {
    val avg: Double get() = if (count == 0L) value else sum / count
    fun next(v: Double, t: Long) = LiveValue(v, t, minOf(min, v), maxOf(max, v), sum + v, count + 1)
    companion object { fun first(v: Double, t: Long) = LiveValue(v, t, v, v, v, 1) }
}

/** Fixed-size ring buffer of samples for one channel (graph history). */
class History(private val capacity: Int = 12_000) {
    private val t = LongArray(capacity)
    private val v = DoubleArray(capacity)
    private var start = 0
    var size = 0; private set

    @Synchronized fun add(time: Long, value: Double) {
        val idx = (start + size) % capacity
        t[idx] = time; v[idx] = value
        if (size < capacity) size++ else start = (start + 1) % capacity
    }

    @Synchronized fun snapshot(key: String, sinceMs: Long = 0): Series {
        val times = ArrayList<Long>(size); val vals = ArrayList<Double>(size)
        for (i in 0 until size) { val j = (start + i) % capacity; if (t[j] >= sinceMs) { times.add(t[j]); vals.add(v[j]) } }
        return Series(key, times.toLongArray(), vals.toDoubleArray())
    }

    @Synchronized fun clear() { start = 0; size = 0 }
}

/**
 * Keeps the requested channels flowing while connected. Screens, the recorder and alerts each
 * register what they need; the engine polls the union so nothing is requested that nobody shows.
 */
class LiveEngine(
    private val conn: ConnectionManager,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
) {
    private val requests = ConcurrentHashMap<String, Set<String>>()
    private val _values = MutableStateFlow<Map<String, LiveValue>>(emptyMap())
    val values: StateFlow<Map<String, LiveValue>> = _values
    private val _rate = MutableStateFlow(0.0)
    val rateHz: StateFlow<Double> = _rate
    private val _unavailable = MutableStateFlow<Set<String>>(emptySet())
    val unavailable: StateFlow<Set<String>> = _unavailable
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    private val histories = ConcurrentHashMap<String, History>()
    private val listeners = CopyOnWriteArrayList<(List<Sample>) -> Unit>()
    private val latest = ConcurrentHashMap<String, LiveValue>()
    val derived = DerivedCalculator()
    @Volatile var enhanced: List<EnhancedPid> = emptyList()
    @Volatile private var gps: Sample? = null
    private var job: Job? = null
    private var poller: LivePoller? = null
    private var pollerReportId: Any? = null
    /** null = not tried yet on this connection; false = the ECU has no Mode 06 misfire data. */
    @Volatile private var misfireSupported: Boolean? = null

    init {
        scope.launch {
            conn.state.collect { st ->
                if (st is LinkState.Connected) start() else stop()
            }
        }
    }

    fun require(owner: String, keys: Collection<String>) { requests[owner] = keys.toSet() }
    fun release(owner: String) { requests.remove(owner) }
    fun addListener(l: (List<Sample>) -> Unit) { listeners.add(l) }

    fun setSpec(spec: VehicleSpec) { derived.spec = spec }

    fun history(key: String, sinceMs: Long = 0): Series = histories[key]?.snapshot(key, sinceMs) ?: Series(key, LongArray(0), DoubleArray(0))

    fun resetStats() { latest.replaceAll { _, v -> LiveValue.first(v.value, v.time) }; _values.value = latest.toMap() }

    fun clearHistory() { histories.values.forEach { it.clear() } }

    /** Phone GPS speed (km/h), merged as its own channel. */
    fun pushGps(speedKmh: Double, t: Long) {
        val s = Sample("gps_speed", speedKmh, t)
        gps = s
        ingest(listOf(s))
    }

    private fun wanted(): Set<String> = requests.values.flatten().toSet()

    private fun start() {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) { loop() }
    }

    private fun stop() {
        job?.cancel(); job = null; poller = null; pollerReportId = null
        _rate.value = 0.0
        // Never leave the last readings on screen as if they were live.
        latest.clear()
        _values.value = emptyMap()
    }

    private suspend fun loop() {
        var lastVolt = 0L; var lastMisfire = 0L; var lastEnh = 0L; var lastRatePub = 0L
        var lastSelection: Map<String, Long> = emptyMap()
        while (scope.isActive) {
            val report = conn.report ?: run { delay(200); null } ?: continue
            if (pollerReportId !== report) {
                poller = conn.session?.let { LivePoller(it.client, report.supportedPids) }
                pollerReportId = report
                lastSelection = emptyMap()
                misfireSupported = if (report.protocol.isCan) null else false
                misfireMids = null
            }
            val p = poller ?: run { delay(200); null } ?: continue
            val keys = wanted()
            val mode = settings.value.polling
            val selection = keys.associateWith { k -> if (slowChannel(k)) mode.slowMs else mode.fastMs }
            if (selection != lastSelection) { p.select(selection); lastSelection = selection }
            try {
                val now = System.currentTimeMillis()
                val samples = ArrayList<Sample>()
                samples.addAll(conn.run(Priority.NORMAL) { p.cycle() })
                if (keys.contains("adapter_voltage") && now - lastVolt > 3000) {
                    lastVolt = now
                    conn.run(Priority.LOW) { it.elm.readVoltage() }?.let { samples.add(Sample("adapter_voltage", it, System.currentTimeMillis())) }
                }
                if (misfireSupported != false && keys.any { it.startsWith("misfire_") } && now - lastMisfire > 4000) {
                    lastMisfire = now
                    val m = readMisfires()
                    if (misfireSupported == null) misfireSupported = m.isNotEmpty()
                    samples.addAll(m)
                }
                val enh = enhanced.filter { it.key in keys }
                if (enh.isNotEmpty() && now - lastEnh > 1000) {
                    lastEnh = now
                    for (e in enh) {
                        val (v, _) = conn.run(Priority.NORMAL) { e.read(it) }
                        if (v != null) samples.add(Sample(e.key, v, System.currentTimeMillis()))
                    }
                }
                if (samples.isEmpty()) { delay(if (selection.isEmpty()) 250 else 15); continue }
                ingest(samples)
                _unavailable.value = p.unavailable.flatMap { pid -> Pids[pid]?.channels?.map { it.key } ?: emptyList() }.toSet()
                _lastError.value = null
                if (now - lastRatePub > 1000) { _rate.value = p.cycleHz; lastRatePub = now }
                if (mode.fastMs > 0) delay(mode.fastMs / 4)
            } catch (e: TransportException) {
                _lastError.value = e.message; delay(500)
            } catch (e: ElmTimeoutException) {
                _lastError.value = "Adapter timeout"; delay(200)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _lastError.value = e.message ?: e.javaClass.simpleName; delay(500)
            }
        }
    }

    @Volatile private var misfireMids: List<Int>? = null

    /** Reads the Mode 06 per-cylinder misfire monitors the ECU says it supports (bitmap 06 A0). */
    private suspend fun readMisfires(): List<Sample> {
        val mids = misfireMids ?: conn.run(Priority.LOW) { c ->
            val p = c.request(0x06, 0xA0).positive.values.flatten().firstOrNull { it.size >= 5 && it[0] == 0xA0 }
            if (p == null) emptyList() else app.obdforge.core.pid.Pids.decodeSupport(0xA0, p.copyOfRange(1, 5)).filter { Mode06.isMisfireCylinder(it) }.sorted()
        }.also { misfireMids = it }
        if (mids.isEmpty()) return emptyList()
        val res = conn.run(Priority.LOW) { c -> mids.flatMap { mid -> c.request(0x06, mid).positive.values.flatten().flatMap { Mode06.decodeCan(it) } } }
        val t = System.currentTimeMillis()
        val byCyl = res.filter { Mode06.isMisfireCylinder(it.mid) }.groupBy { it.mid - 0xA1 }.mapValues { e -> e.value.maxOf { it.value } }
        if (byCyl.isEmpty()) return emptyList()
        return byCyl.map { (c, v) -> Sample("misfire_c$c", v, t) } + Sample("misfire_total", byCyl.values.sum(), t)
    }

    private fun slowChannel(k: String) = k in setOf("ect", "oil_temp", "ambient", "fuel_level", "baro", "warmups", "dist_mil", "dist_clr",
        "time_mil", "time_clr", "odometer", "ect_1", "ect_2", "cat_b1s1", "cat_b2s1", "cat_b1s2", "cat_b2s2", "ethanol", "torque_ref", "maf_max", "hv_soc")

    private fun ingest(samples: List<Sample>) {
        val all = ArrayList<Sample>(samples)
        val snapshot = HashMap<String, Double>()
        latest.forEach { (k, v) -> snapshot[k] = v.value }
        samples.forEach { snapshot[it.key] = it.value }
        if (samples.any { it.key != "gps_speed" }) all.addAll(derived.compute(snapshot, samples.last().timeMs))
        for (s in all) {
            if (s.value.isNaN() || s.value.isInfinite()) continue
            latest.compute(s.key) { _, old -> old?.next(s.value, s.timeMs) ?: LiveValue.first(s.value, s.timeMs) }
            histories.getOrPut(s.key) { History() }.add(s.timeMs, s.value)
        }
        _values.value = latest.toMap()
        listeners.forEach { runCatching { it(all) } }
    }

    fun latestMap(): Map<String, Double> = latest.mapValues { it.value.value }
}
