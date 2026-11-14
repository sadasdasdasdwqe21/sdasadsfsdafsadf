package app.obdforge.core.elm

import app.obdforge.core.transport.ObdTransport
import app.obdforge.core.transport.TransportException

/** What came back from one adapter command, after removing the prompt and echo. */
data class ElmReply(
    val command: String,
    val lines: List<String>,
    val raw: String,
    val elapsedMs: Long,
) {
    val text: String get() = lines.joinToString("\n")
    val error: ElmError? get() = ElmError.detect(lines)
    val isOk: Boolean get() = lines.any { it.trim() == "OK" }
}

enum class ElmError(val token: String, val meaning: String, val retryable: Boolean) {
    NO_DATA("NO DATA", "The vehicle did not answer this request (often: not supported)", false),
    UNABLE_TO_CONNECT("UNABLE TO CONNECT", "The adapter could not find a working OBD protocol. Is the ignition on?", true),
    CAN_ERROR("CAN ERROR", "CAN bus error (wrong protocol/baud, or bus problem)", true),
    BUS_INIT("BUS INIT", "Bus initialisation failed", true),
    BUS_BUSY("BUS BUSY", "The bus is too busy to send", true),
    BUS_ERROR("BUS ERROR", "Bus error", true),
    FB_ERROR("FB ERROR", "Feedback error (wiring/bus fault)", true),
    DATA_ERROR("DATA ERROR", "Corrupted data / checksum error", true),
    RX_ERROR("<RX ERROR", "Receive error", true),
    BUFFER_FULL("BUFFER FULL", "Adapter buffer overflow", true),
    STOPPED("STOPPED", "Request was interrupted", true),
    LV_RESET("LV RESET", "Adapter reset due to low voltage", true),
    ACT_ALERT("ACT ALERT", "Adapter went to low-power mode", true),
    UNKNOWN_COMMAND("?", "Adapter did not understand the command", false),
    ERR("ERR", "Adapter internal error", true);

    companion object {
        fun detect(lines: List<String>): ElmError? {
            for (line in lines) {
                val l = line.trim().uppercase()
                if (l == "?") return UNKNOWN_COMMAND
                for (e in entries) {
                    if (e == UNKNOWN_COMMAND) continue
                    if (e == BUS_INIT) {
                        if (l.startsWith("BUS INIT") && l.contains("ERROR")) return BUS_INIT
                        continue
                    }
                    if (e == ERR) { if (Regex("^ERR\\d+").containsMatchIn(l)) return ERR; continue }
                    if (l.contains(e.token)) return e
                }
            }
            return null
        }
    }
}

class ElmTimeoutException(val command: String, val partial: String) :
    Exception("Adapter did not answer '$command' in time")

class ElmException(message: String, val reply: ElmReply? = null) : Exception(message)

data class AdapterInfo(
    val idString: String,          // ATI e.g. "ELM327 v1.5"
    val description: String?,      // AT@1
    val stnId: String?,            // STI, only on STN chips (OBDLink etc.)
    val stnDevice: String?,        // STDI
    val firmwareVersion: Double?,  // parsed from ATI
    val voltage: Double?,          // ATRV
    val supported: Map<String, Boolean>,
) {
    val isStn: Boolean get() = stnId != null
    /** Most "v2.1" and many "v1.5" adapters are clones running copied firmware with gaps. */
    val likelyClone: Boolean get() = !isStn && (idString.contains("v2.1") || idString.contains("v1.5"))
    val chip: String get() = if (isStn) "STN (${stnId})" else idString
}

/**
 * Talks to an ELM327 (or STN) adapter: sends one command at a time and waits for the '>' prompt.
 * Not thread-safe by design: every call is made from the single command thread owned by
 * [app.obdforge.core.obd.CommandExecutor], which is what stops overlapping requests from
 * corrupting the conversation.
 */
class Elm327(
    private val transport: ObdTransport,
    private val traffic: (TrafficEntry) -> Unit = {},
) {
    data class TrafficEntry(val timeMs: Long, val outgoing: Boolean, val text: String)

    var defaultTimeoutMs = 2_500L
    var protocol: ObdProtocol = ObdProtocol.AUTO
        private set
    var info: AdapterInfo? = null
        private set
    var headersOn = false
        private set
    /** Count of replies that had non-hex junk in them; shown as a connection quality hint. */
    var corruptReplies = 0
        private set
    var totalReplies = 0
        private set

    val transportOpen: Boolean get() = transport.isOpen

    @Throws(TransportException::class, ElmTimeoutException::class)
    fun send(command: String, timeoutMs: Long = defaultTimeoutMs): ElmReply {
        transport.drain()
        val start = System.currentTimeMillis()
        traffic(TrafficEntry(start, true, command))
        transport.write((command + "\r").toByteArray(Charsets.US_ASCII))
        val sb = StringBuilder()
        val deadline = start + timeoutMs
        while (true) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) {
                // Interrupt the adapter so its late answer does not leak into the next command.
                runCatching {
                    transport.write("\r".toByteArray())
                    val until = System.currentTimeMillis() + 600
                    while (System.currentTimeMillis() < until) {
                        val b = transport.read(100)
                        if (String(b, Charsets.ISO_8859_1).contains('>')) break
                    }
                    transport.drain()
                }
                traffic(TrafficEntry(System.currentTimeMillis(), false, "[timeout] $sb"))
                throw ElmTimeoutException(command, sb.toString())
            }
            val chunk = transport.read(minOf(left, 250))
            if (chunk.isEmpty()) continue
            sb.append(String(chunk, Charsets.ISO_8859_1))
            if (sb.indexOf(">") >= 0) break
        }
        val raw = sb.substring(0, sb.indexOf(">"))
        val reply = ElmReply(command, cleanLines(raw, command), raw, System.currentTimeMillis() - start)
        totalReplies++
        traffic(TrafficEntry(System.currentTimeMillis(), false, reply.text.ifEmpty { "(empty)" }))
        return reply
    }

    /** Splits the reply into lines and removes the echo, status chatter and NULs some clones send. */
    internal fun cleanLines(raw: String, command: String): List<String> {
        return raw.replace("\u0000", "")
            .split('\r', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filter { !it.equals(command, ignoreCase = true) && !it.replace(" ", "").equals(command.replace(" ", ""), ignoreCase = true) }
            .filter { !it.startsWith("SEARCHING", ignoreCase = true) }
            .filter { !(it.startsWith("BUS INIT", ignoreCase = true) && !it.contains("ERROR", ignoreCase = true)) }
    }

    fun noteCorrupt() { corruptReplies++ }

    private fun okOrUnknown(cmd: String): Boolean = try {
        val r = send(cmd, 1500)
        r.isOk || (r.error == null && r.lines.isNotEmpty() && r.lines.none { it.contains("?") })
    } catch (e: ElmTimeoutException) { false }

    /**
     * Resets and configures the adapter. Echo and line feeds off, spaces and headers on (so we
     * can tell ECUs apart and reassemble multi-frame answers ourselves), adaptive timing on.
     */
    fun initialize(): AdapterInfo {
        // ATZ reboots the chip; some clones need a moment. ATWS is a softer reset used as fallback.
        val reset = try { send("ATZ", 4000) } catch (e: ElmTimeoutException) { send("ATWS", 4000) }
        var id = reset.lines.lastOrNull { it.contains("ELM", true) || it.contains("STN", true) || it.contains("OBD", true) } ?: ""
        expectOk("ATE0")
        runCatching { send("ATL0") }
        runCatching { send("ATS1") }
        expectOk("ATH1"); headersOn = true
        runCatching { send("ATAT1") }
        if (id.isEmpty()) id = runCatching { send("ATI").lines.firstOrNull() }.getOrNull() ?: "Unknown adapter"
        val desc = runCatching { send("AT@1").lines.firstOrNull()?.takeIf { it != "?" } }.getOrNull()
        val sti = runCatching { send("STI", 1500).lines.firstOrNull()?.takeIf { it != "?" && !it.contains("?") } }.getOrNull()
        val stdi = if (sti != null) runCatching { send("STDI", 1500).lines.firstOrNull() }.getOrNull() else null
        val volts = readVoltage()
        val version = Regex("v(\\d+\\.\\d+)").find(id)?.groupValues?.get(1)?.toDoubleOrNull()
        val caps = linkedMapOf(
            "ATDPN (describe protocol number)" to okOrAnswer("ATDPN"),
            "ATCAF (CAN auto-formatting control)" to okOrUnknown("ATCAF1"),
            "ATSH (set header)" to okOrUnknown("ATSH7DF"),
            "ATCRA (CAN receive filter)" to okOrUnknown("ATCRA"),
            "ATFCSM (flow control mode, v1.4+)" to okOrUnknown("ATFCSM0"),
            "ATIB (ISO baud rate)" to okOrUnknown("ATIB10"),
            "ATRV (voltage)" to (volts != null),
            "STN extended commands" to (sti != null),
        )
        val i = AdapterInfo(id.trim(), desc, sti, stdi, version, volts, caps)
        info = i
        return i
    }

    private fun okOrAnswer(cmd: String): Boolean = try {
        val r = send(cmd, 1500); r.error == null && r.lines.isNotEmpty()
    } catch (e: ElmTimeoutException) { false }

    private fun expectOk(cmd: String) {
        val r = send(cmd)
        if (!r.isOk) {
            // Some clones answer with the echo only; try once more before failing.
            val again = send(cmd)
            if (!again.isOk) throw ElmException("Adapter rejected $cmd: ${again.text}", again)
        }
    }

    fun readVoltage(): Double? = runCatching {
        val r = send("ATRV", 1500)
        r.lines.firstOrNull()?.replace("V", "", ignoreCase = true)?.trim()?.toDoubleOrNull()
    }.getOrNull()

    /**
     * Finds the vehicle's protocol. With [preferred] null the ELM automatic search is used
     * (ATSP0); otherwise that protocol is forced. A "0100" request is used as the probe because
     * every OBD-II vehicle must answer it.
     */
    fun connectToVehicle(preferred: ObdProtocol? = null, isoBaud: Int? = null): ObdProtocol {
        if (isoBaud != null) runCatching { send(if (isoBaud == 9600) "ATIB96" else "ATIB10") }
        val candidates = if (preferred == null || preferred == ObdProtocol.AUTO) listOf(ObdProtocol.AUTO) + ObdProtocol.searchOrder else listOf(preferred)
        var lastError: String? = null
        for (p in candidates) {
            send("ATSP${p.elmCode}")
            val probe = try { send("0100", if (p == ObdProtocol.AUTO) 20_000 else 8_000) } catch (e: ElmTimeoutException) {
                lastError = "Timeout on ${p.label}"; continue
            }
            val err = probe.error
            if (err == null && probe.lines.any { it.replace(" ", "").contains("4100") }) {
                val dpn = send("ATDPN").lines.firstOrNull() ?: ""
                protocol = ObdProtocol.fromDpn(dpn)?.takeIf { it != ObdProtocol.AUTO } ?: p
                // Lock the found protocol so later requests do not trigger a new search.
                runCatching { send("ATSP${protocol.elmCode}") }
                return protocol
            }
            lastError = err?.meaning ?: probe.text
        }
        throw ElmException("Could not connect to the vehicle: ${lastError ?: "no answer"}")
    }

    /** Sets the CAN/legacy header for physical addressing; null restores functional broadcast. */
    fun setHeader(header: String?) {
        val h = header ?: if (protocol.isCan) (if (protocol.canIdBits == 29) "18DB33F1" else "7DF") else "686AF1"
        send("ATSH$h")
        if (protocol.isCan && header != null) {
            // Only listen to the matching response id (request id + 8 for 11-bit).
            val rx = header.toIntOrNull(16)?.let { if (protocol.canIdBits == 11) (it + 8).toString(16).uppercase() else null }
            if (rx != null) runCatching { send("ATCRA$rx") } else runCatching { send("ATCRA") }
        } else runCatching { send("ATCRA") }
    }

    fun setAutoFormatting(on: Boolean) { send(if (on) "ATCAF1" else "ATCAF0") }

    fun close() = transport.close()
}
