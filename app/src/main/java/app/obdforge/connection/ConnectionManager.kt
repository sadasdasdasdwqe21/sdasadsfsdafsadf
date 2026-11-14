package app.obdforge.connection

import android.content.Context
import app.obdforge.core.elm.Elm327
import app.obdforge.core.elm.ElmTimeoutException
import app.obdforge.core.elm.ObdProtocol
import app.obdforge.core.obd.ConnectReport
import app.obdforge.core.obd.ObdClient
import app.obdforge.core.obd.ObdSession
import app.obdforge.core.obd.Priority
import app.obdforge.core.sim.SimScenario
import app.obdforge.core.sim.SimulatedElm327
import app.obdforge.core.transport.ConnectionKind
import app.obdforge.core.transport.ObdTransport
import app.obdforge.core.transport.TransportException
import app.obdforge.data.LastAdapter
import app.obdforge.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutionException

sealed class AdapterTarget {
    abstract val label: String
    abstract val kind: ConnectionKind
    data class Classic(val address: String, val name: String) : AdapterTarget() { override val label get() = name; override val kind get() = ConnectionKind.BLUETOOTH_CLASSIC }
    data class Ble(val address: String, val name: String) : AdapterTarget() { override val label get() = name; override val kind get() = ConnectionKind.BLUETOOTH_LE }
    data class Wifi(val host: String, val port: Int, val ssid: String? = null) : AdapterTarget() { override val label get() = ssid ?: "$host:$port"; override val kind get() = ConnectionKind.WIFI }
    data class Simulator(val scenario: SimScenario, val legacy: Boolean = false) : AdapterTarget() { override val label get() = "Simulator · ${scenario.label}"; override val kind get() = ConnectionKind.SIMULATOR }
}

sealed class LinkState {
    data object Idle : LinkState()
    data class Connecting(val target: AdapterTarget, val step: String) : LinkState()
    data class Connected(val target: AdapterTarget, val report: ConnectReport, val since: Long) : LinkState()
    data class Reconnecting(val target: AdapterTarget, val attempt: Int, val reason: String) : LinkState()
    data class Failed(val target: AdapterTarget, val message: String) : LinkState()
}

class NotConnectedException(msg: String = "Not connected to an adapter") : Exception(msg)

data class TrafficLine(val time: Long, val outgoing: Boolean, val text: String)

/**
 * Owns the one adapter connection: building transports, the connect handshake, the command
 * thread, the traffic log, and automatic reconnection when Bluetooth/Wi-Fi drops.
 */
class ConnectionManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settings: SettingsStore,
    val scanner: AdapterScanner,
    val wifiBinder: WifiNetworkBinder,
) {
    private val _state = MutableStateFlow<LinkState>(LinkState.Idle)
    val state: StateFlow<LinkState> = _state
    @Volatile var session: ObdSession? = null; private set
    @Volatile var simulator: SimulatedElm327? = null; private set
    private var connectJob: Job? = null
    @Volatile private var userDisconnected = false
    @Volatile private var consecutiveTimeouts = 0

    private val trafficBuf = ArrayDeque<TrafficLine>()
    private val _traffic = MutableStateFlow<List<TrafficLine>>(emptyList())
    val traffic: StateFlow<List<TrafficLine>> = _traffic
    private var lastTrafficPublish = 0L

    val isConnected: Boolean get() = _state.value is LinkState.Connected
    val isSimulated: Boolean get() = (_state.value as? LinkState.Connected)?.target is AdapterTarget.Simulator
    val report: ConnectReport? get() = (_state.value as? LinkState.Connected)?.report

    private fun log(e: Elm327.TrafficEntry) = addTraffic(TrafficLine(e.timeMs, e.outgoing, e.text))

    fun note(text: String) = addTraffic(TrafficLine(System.currentTimeMillis(), false, "# $text"))

    private fun addTraffic(l: TrafficLine) {
        synchronized(trafficBuf) {
            trafficBuf.addLast(l)
            while (trafficBuf.size > 600) trafficBuf.removeFirst()
            val now = System.currentTimeMillis()
            if (now - lastTrafficPublish > 250 || l.text.startsWith("#")) { _traffic.value = trafficBuf.toList(); lastTrafficPublish = now }
        }
    }

    fun trafficText(): String {
        val f = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        return synchronized(trafficBuf) { trafficBuf.joinToString("\n") { "${f.format(Date(it.time))} ${if (it.outgoing) ">>" else "<<"} ${it.text}" } }
    }

    private fun buildTransport(t: AdapterTarget): ObdTransport = when (t) {
        is AdapterTarget.Classic -> BluetoothClassicTransport(scanner.device(t.address) ?: throw TransportException("Bluetooth device ${t.address} unavailable (is Bluetooth on?)"), scanner.bluetooth)
        is AdapterTarget.Ble -> BleTransport(context, scanner.device(t.address) ?: throw TransportException("Bluetooth device ${t.address} unavailable (is Bluetooth on?)"))
        is AdapterTarget.Wifi -> WifiTransport(t.host, t.port, wifiBinder.network.value ?: wifiBinder.currentWifi())
        is AdapterTarget.Simulator -> SimulatedElm327(t.scenario, t.legacy).also { simulator = it }
    }

    fun connect(target: AdapterTarget) {
        userDisconnected = false
        connectJob?.cancel()
        connectJob = scope.launch(Dispatchers.IO) {
            closeSession()
            _state.value = LinkState.Connecting(target, "Opening ${target.kind.label}…")
            try {
                establish(target)
            } catch (e: Exception) {
                note("Connect failed: ${e.message}")
                _state.value = LinkState.Failed(target, friendly(e))
                closeSession()
            }
        }
    }

    private suspend fun establish(target: AdapterTarget) {
        val transport = buildTransport(target)
        val s = ObdSession(transport) { log(it) }
        session = s
        note("Opening ${transport.description}")
        withContext(Dispatchers.IO) { transport.open() }
        _state.value = LinkState.Connecting(target, "Initialising adapter…")
        val preferred = runCatching { ObdProtocol.valueOf(settings.value.preferredProtocol) }.getOrDefault(ObdProtocol.AUTO)
        val iso = settings.value.isoBaud.takeIf { preferred.isLegacy }
        val report = try {
            s.submit(Priority.HIGH) { _ -> s.connect(preferred, if (iso == 9600) 9600 else null) }.get()
        } catch (e: ExecutionException) { throw (e.cause as? Exception) ?: e }
        consecutiveTimeouts = 0
        _state.value = LinkState.Connected(target, report, System.currentTimeMillis())
        note("Connected: ${report.protocol.label}, ${report.supportedPids.size} PIDs, ECUs ${report.ecus.joinToString { it.name }}")
        if (target !is AdapterTarget.Simulator) {
            settings.update {
                it.copy(lastAdapter = when (target) {
                    is AdapterTarget.Classic -> LastAdapter("classic", target.address, target.name)
                    is AdapterTarget.Ble -> LastAdapter("ble", target.address, target.name)
                    is AdapterTarget.Wifi -> LastAdapter("wifi", target.host, target.ssid ?: target.host, target.port)
                    else -> it.lastAdapter
                })
            }
        }
    }

    private fun friendly(e: Throwable): String = when (e) {
        is TransportException -> e.message ?: "Connection failed"
        is ElmTimeoutException -> "The adapter stopped answering (${e.command}). Check it is powered and not in use by another app."
        else -> e.message ?: e.javaClass.simpleName
    }

    fun disconnect() {
        userDisconnected = true
        connectJob?.cancel()
        scope.launch(Dispatchers.IO) {
            closeSession()
            _state.value = LinkState.Idle
            note("Disconnected by user")
        }
    }

    private fun closeSession() {
        session?.let { runCatching { it.close() } }
        session = null
        simulator?.let { runCatching { it.shutdown() } }
        simulator = null
    }

    /**
     * Runs a block on the command thread. Link failures start automatic reconnection and are
     * rethrown so callers can show an error rather than stale data.
     */
    suspend fun <T> run(priority: Priority, block: (ObdClient) -> T): T {
        val s = session ?: throw NotConnectedException()
        if (_state.value !is LinkState.Connected) throw NotConnectedException(if (_state.value is LinkState.Reconnecting) "Reconnecting…" else "Not connected")
        return withContext(Dispatchers.IO) {
            try {
                val r = s.submit(priority, block).get()
                consecutiveTimeouts = 0
                r
            } catch (e: ExecutionException) {
                val cause = e.cause ?: e
                when (cause) {
                    is TransportException -> { onLinkLost(cause.message ?: "Link lost"); throw cause }
                    is ElmTimeoutException -> {
                        consecutiveTimeouts++
                        if (consecutiveTimeouts >= 4) onLinkLost("Adapter not responding")
                        throw cause
                    }
                    else -> throw cause
                }
            }
        }
    }

    private fun onLinkLost(reason: String) {
        val st = _state.value as? LinkState.Connected ?: return
        if (userDisconnected) return
        note("Link lost: $reason")
        if (!settings.value.autoReconnect) {
            closeSession(); _state.value = LinkState.Failed(st.target, "Connection lost: $reason"); return
        }
        connectJob?.cancel()
        connectJob = scope.launch(Dispatchers.IO) { reconnectLoop(st.target, reason) }
    }

    private suspend fun reconnectLoop(target: AdapterTarget, reason: String) {
        val waits = listOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L, 15_000L, 30_000L, 30_000L)
        for ((i, w) in waits.withIndex()) {
            if (!scope.isActive || userDisconnected) return
            _state.value = LinkState.Reconnecting(target, i + 1, reason)
            val keepSim = simulator
            session?.let { runCatching { it.close() } }
            session = null
            delay(w)
            try {
                if (target is AdapterTarget.Simulator && keepSim != null) {
                    // Reuse the same simulated car so its state (codes, warm engine) carries over.
                    keepSim.open()
                    val s = ObdSession(keepSim) { log(it) }
                    session = s
                    val report = s.submit(Priority.HIGH) { _ -> s.connect() }.get()
                    simulator = keepSim
                    _state.value = LinkState.Connected(target, report, System.currentTimeMillis())
                } else establish(target)
                note("Reconnected after ${i + 1} attempt(s)")
                consecutiveTimeouts = 0
                return
            } catch (e: Exception) {
                note("Reconnect attempt ${i + 1} failed: ${(e.cause ?: e).message}")
            }
        }
        _state.value = LinkState.Failed(target, "Could not reconnect: $reason")
        closeSession()
    }

    fun reconnectLast() {
        val last = settings.value.lastAdapter ?: return
        val t = when (last.kind) {
            "classic" -> AdapterTarget.Classic(last.address, last.name)
            "ble" -> AdapterTarget.Ble(last.address, last.name)
            "wifi" -> AdapterTarget.Wifi(last.address, last.port, last.name)
            else -> return
        }
        connect(t)
    }

    fun signalQuality(): Int? = session?.transport?.signalQuality()

    fun linkQualityText(): String {
        val s = session ?: return "--"
        val total = s.elm.totalReplies
        val bad = s.elm.corruptReplies
        val q = signalQuality()
        val errPct = if (total > 0) bad * 100.0 / total else 0.0
        return (if (q != null) "Signal $q% · " else "") + "Errors ${String.format(Locale.US, "%.1f", errPct)}% of $total replies"
    }
}
