package app.obdforge.live

import app.obdforge.connection.ConnectionManager
import app.obdforge.connection.LinkState
import app.obdforge.core.dtc.Dtc
import app.obdforge.core.dtc.DtcKind
import app.obdforge.core.obd.EcuInfo
import app.obdforge.core.obd.FreezeFrame
import app.obdforge.core.obd.O2TestResult
import app.obdforge.core.obd.Priority
import app.obdforge.core.obd.ServiceResult
import app.obdforge.core.obd.VehicleInfoItem
import app.obdforge.core.pid.Mode06
import app.obdforge.core.pid.Readiness
import app.obdforge.core.util.Json
import app.obdforge.data.AppDatabase
import app.obdforge.data.DiagRecordEntity
import app.obdforge.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class EcuScanEntry(
    val name: String,
    val requestId: Int,
    val responded: Boolean,
    val identification: Map<String, String>,
    val dtcs: List<Dtc>,
    val error: String?,
)

data class DiagState(
    val busy: String? = null,
    val error: String? = null,
    val readAt: Long? = null,
    val stored: List<Dtc> = emptyList(),
    val pending: List<Dtc> = emptyList(),
    val permanent: List<Dtc> = emptyList(),
    val readiness: Readiness.Status? = null,
    val readinessThisCycle: Readiness.Status? = null,
    val freezeFrames: List<FreezeFrame> = emptyList(),
    val mode06: List<Mode06.TestResult> = emptyList(),
    val mode06Read: Boolean = false,
    val mode05: List<O2TestResult> = emptyList(),
    val vehicleInfo: List<VehicleInfoItem> = emptyList(),
    val ecus: List<EcuInfo> = emptyList(),
    val ecuScan: List<EcuScanEntry> = emptyList(),
    val lastClear: String? = null,
    val evapResult: String? = null,
    /** Codes seen pending in an earlier read: used to show whether a fault keeps recurring. */
    val pendingHistory: Map<String, Int> = emptyMap(),
) {
    val allCodes: List<Dtc> get() = stored + pending + permanent
    val dtcCount: Int get() = (stored.map { it.code } + pending.map { it.code }).toSet().size
}

/**
 * Runs the diagnostic services (Modes 02-0A, UDS reads) and keeps the last results so they
 * survive navigation. Results are also written to the history table.
 */
class DiagnosticsManager(
    private val conn: ConnectionManager,
    private val db: AppDatabase,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
    private val onNewCodes: (List<String>) -> Unit,
) {
    private val _state = MutableStateFlow(DiagState())
    val state: StateFlow<DiagState> = _state

    init {
        // Background check for new codes every 60 s while connected.
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(60_000)
                if (conn.isConnected && _state.value.busy == null) runCatching {
                    val codes = conn.run(Priority.LOW) { c -> c.readDtcs(DtcKind.STORED) + c.readDtcs(DtcKind.PENDING) }
                    onNewCodes(codes.map { it.code })
                }
            }
        }
        scope.launch { conn.state.collect { if (it is LinkState.Connected) _state.value = _state.value.copy(ecus = it.report.ecus) } }
    }

    private fun busy(msg: String?) { _state.value = _state.value.copy(busy = msg, error = if (msg != null) null else _state.value.error) }

    private suspend fun <T> op(label: String, block: suspend () -> T): T? {
        busy(label)
        return try { block() } catch (e: Exception) {
            _state.value = _state.value.copy(error = e.message ?: e.javaClass.simpleName); null
        } finally { busy(null) }
    }

    fun readAll() = scope.launch(Dispatchers.IO) {
        op("Reading trouble codes…") {
            val r = conn.run(Priority.HIGH) { c ->
                val stored = c.readDtcs(DtcKind.STORED)
                val pending = c.readDtcs(DtcKind.PENDING)
                val permanent = runCatching { c.readDtcs(DtcKind.PERMANENT) }.getOrDefault(emptyList())
                val r01 = c.readPids(listOf(0x01, 0x41))
                Quad(stored, pending, permanent, r01)
            }
            val ff = runCatching { conn.run(Priority.HIGH) { it.readFreezeFrame() } }.getOrDefault(emptyList())
            val hist = HashMap(_state.value.pendingHistory)
            r.b.forEach { hist[it.code] = (hist[it.code] ?: 0) + 1 }
            _state.value = _state.value.copy(
                stored = r.a, pending = r.b, permanent = r.c, freezeFrames = ff, readAt = System.currentTimeMillis(),
                readiness = r.d[0x01]?.let { Readiness.decode(it) }, readinessThisCycle = r.d[0x41]?.let { Readiness.decode(it) },
                pendingHistory = hist,
            )
            onNewCodes((r.a + r.b).map { it.code })
            record("READ_CODES", "${r.a.size} stored, ${r.b.size} pending, ${r.c.size} permanent",
                mapOf("stored" to r.a.map { it.code }, "pending" to r.b.map { it.code }, "permanent" to r.c.map { it.code },
                    "freezeFrames" to ff.map { mapOf("ecu" to it.ecu, "dtc" to it.dtc, "values" to it.values) }))
        }
    }

    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

    /** Mode 04. The UI only calls this after the user confirmed the consequences. */
    fun clearCodes() = scope.launch(Dispatchers.IO) {
        op("Clearing codes…") {
            val before = _state.value.stored.map { it.code } + _state.value.pending.map { it.code }
            val r: ServiceResult = conn.run(Priority.HIGH) { it.clearDtcs() }
            val msg = when {
                r.answered -> "Clear command accepted by ${r.positive.size} ECU(s)."
                r.negative.isNotEmpty() -> "ECU refused: " + r.negative.values.joinToString { ServiceResult.nrcText(it) } + " (often: engine running; try ignition on, engine off)."
                else -> "No ECU confirmed the clear (${r.error?.meaning ?: "no answer"})."
            }
            _state.value = _state.value.copy(lastClear = msg)
            record("CLEAR_CODES", msg, mapOf("codesBefore" to before))
        }
        readAll()
    }

    fun readMode06() = scope.launch(Dispatchers.IO) {
        op("Reading on-board monitor tests (Mode 06)…") {
            val r = conn.run(Priority.HIGH) { it.readMode06() }
            _state.value = _state.value.copy(mode06 = r, mode06Read = true)
            record("MODE06", "${r.size} results, ${r.count { !it.passed }} failed", mapOf("results" to r.map {
                mapOf("mid" to it.mid, "tid" to it.tid, "value" to it.value, "min" to it.min, "max" to it.max, "unit" to it.unit, "status" to it.status) }))
        }
    }

    fun readMode05() = scope.launch(Dispatchers.IO) {
        op("Reading oxygen sensor tests (Mode 05)…") {
            _state.value = _state.value.copy(mode05 = conn.run(Priority.HIGH) { it.readMode05() })
        }
    }

    fun readVehicleInfo() = scope.launch(Dispatchers.IO) {
        op("Reading vehicle information (Mode 09)…") {
            _state.value = _state.value.copy(vehicleInfo = conn.run(Priority.HIGH) { it.readVehicleInfo() })
        }
    }

    /** Mode 08 EVAP seal test, only after explicit confirmation. */
    fun runEvapTest() = scope.launch(Dispatchers.IO) {
        op("Requesting EVAP system seal test (Mode 08)…") {
            val r = conn.run(Priority.HIGH) { it.runEvapSealTest() }
            val msg = when {
                r.answered -> "The ECU accepted the EVAP leak test request. It will seal the system and run the test when its conditions are met."
                r.negative.isNotEmpty() -> "Refused by the ECU: " + r.negative.values.joinToString { ServiceResult.nrcText(it) } + "."
                else -> "This vehicle does not support Mode 08 tests (${r.error?.meaning ?: "no answer"})."
            }
            _state.value = _state.value.copy(evapResult = msg)
            record("EVAP_TEST", msg, emptyMap())
        }
    }

    /** Enhanced (UDS) scan of the standard engine and transmission addresses plus extra request ids. */
    fun scanEcus(extraIds: List<Int> = emptyList()) = scope.launch(Dispatchers.IO) {
        op("Scanning ECUs (UDS read-only)…") {
            val rep = conn.report
            if (rep == null || !rep.protocol.isCan) {
                _state.value = _state.value.copy(error = "Enhanced ECU scan needs a CAN (ISO 15765) vehicle."); return@op
            }
            val targets = (listOf("Engine (ECM)" to 0x7E0, "Transmission (TCM)" to 0x7E1, "ECU 7E2" to 0x7E2, "ECU 7E3" to 0x7E3) +
                extraIds.map { "ECU " + it.toString(16).uppercase() to it }).distinctBy { it.second }
            val out = ArrayList<EcuScanEntry>()
            for ((name, id) in targets) {
                val e = conn.run(Priority.HIGH) { c ->
                    val ident = LinkedHashMap<String, String>()
                    var firstErr: String? = null
                    for ((did, label) in c.standardIdentificationDids) {
                        val (data, err) = c.udsReadDid(id, did)
                        if (data != null) ident[label] = c.asciiOrHex(data) else if (firstErr == null) firstErr = err
                        if (ident.isEmpty() && err == "No answer") break // nothing at this address
                    }
                    val (codes, derr) = c.udsReadDtcs(id)
                    val responded = ident.isNotEmpty() || codes.isNotEmpty() || (derr != null && derr != "No answer")
                    EcuScanEntry(name, id, responded, ident, codes, if (ident.isEmpty() && codes.isEmpty()) (derr ?: firstErr) else null)
                }
                out.add(e)
                _state.value = _state.value.copy(ecuScan = out.toList())
            }
            record("ECU_SCAN", "${out.count { it.responded }} ECU(s) responded", mapOf("ecus" to out.map {
                mapOf("name" to it.name, "id" to it.requestId, "ident" to it.identification, "dtcs" to it.dtcs.map { d -> d.code }) }))
        }
    }

    fun clearError() { _state.value = _state.value.copy(error = null) }

    private suspend fun record(type: String, summary: String, data: Map<String, Any?>) {
        runCatching {
            db.misc().insertDiag(DiagRecordEntity(vehicleId = settings.value.activeVehicleId, time = System.currentTimeMillis(), type = type,
                summary = summary, json = Json.write(data), simulated = conn.isSimulated))
        }
    }
}
