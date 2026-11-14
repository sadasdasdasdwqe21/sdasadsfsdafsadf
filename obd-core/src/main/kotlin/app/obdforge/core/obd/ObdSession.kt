package app.obdforge.core.obd

import app.obdforge.core.elm.AdapterInfo
import app.obdforge.core.elm.Elm327
import app.obdforge.core.elm.ObdProtocol
import app.obdforge.core.transport.ObdTransport
import java.util.concurrent.Future

data class ConnectReport(
    val adapter: AdapterInfo,
    val protocol: ObdProtocol,
    val ecus: List<EcuInfo>,
    /** ECU -> supported Mode 01 PIDs */
    val supportedByEcu: Map<Int, Set<Int>>,
    val vin: String?,
    val obdStandard: String?,
    val warnings: List<String>,
    val connectMs: Long,
) {
    val supportedPids: Set<Int> get() = supportedByEcu.values.flatten().toSet()
}

/**
 * One connection to one adapter + vehicle. Owns the command thread. Build a new session for
 * every (re)connection; the old one is closed.
 */
class ObdSession(
    val transport: ObdTransport,
    traffic: (Elm327.TrafficEntry) -> Unit = {},
) {
    val executor = CommandExecutor()
    val elm = Elm327(transport, traffic)
    val client = ObdClient(elm)
    @Volatile var report: ConnectReport? = null; private set

    /** Blocking. Opens the transport, initialises the adapter and finds the vehicle. */
    fun connect(preferred: ObdProtocol? = null, isoBaud: Int? = null, readVin: Boolean = true): ConnectReport {
        val start = System.currentTimeMillis()
        if (!transport.isOpen) transport.open()
        val warnings = ArrayList<String>()
        val adapter = elm.initialize()
        if (adapter.likelyClone) warnings.add("Adapter reports '${adapter.idString}', a version string common on clone chips. Some commands may be missing or slow.")
        val proto = elm.connectToVehicle(preferred, isoBaud)
        elm.defaultTimeoutMs = if (proto.isCan) 1_500 else 3_000
        val supported = client.discoverPids(0x01)
        if (supported.isEmpty()) warnings.add("The vehicle answered but reported no supported Mode 01 PIDs.")
        val ecus = supported.keys.sorted().map { EcuInfo(it, client.ecuName(it)) }
        val vin = if (readVin) runCatching { client.readVin() }.getOrNull() else null
        val std = runCatching { client.readPids(listOf(0x1C))[0x1C]?.let { app.obdforge.core.pid.Pids.obdStandard(it[0]) } }.getOrNull()
        val r = ConnectReport(adapter, proto, ecus, supported, vin, std, warnings, System.currentTimeMillis() - start)
        report = r
        return r
    }

    fun <T> submit(priority: Priority, block: (ObdClient) -> T): Future<T> = executor.submit(priority) { block(client) }

    fun close() {
        executor.clearPending()
        executor.shutdown()
        runCatching { transport.close() }
    }
}
