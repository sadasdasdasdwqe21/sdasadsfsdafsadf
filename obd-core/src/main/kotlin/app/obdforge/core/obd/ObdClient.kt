package app.obdforge.core.obd

import app.obdforge.core.dtc.Dtc
import app.obdforge.core.dtc.DtcKind
import app.obdforge.core.elm.Elm327
import app.obdforge.core.elm.ElmError
import app.obdforge.core.elm.ElmReply
import app.obdforge.core.elm.ElmTimeoutException
import app.obdforge.core.elm.ObdProtocol
import app.obdforge.core.pid.Mode06
import app.obdforge.core.pid.Pids
import app.obdforge.core.util.Hex

/** Result of one service request across all ECUs that answered. */
class ServiceResult(
    val service: Int,
    /** ECU id -> list of positive response payloads (bytes after the response SID). */
    val positive: Map<Int, List<IntArray>>,
    /** ECU id -> negative response code (ISO 14229 NRC). */
    val negative: Map<Int, Int>,
    val error: ElmError?,
    val reply: ElmReply?,
) {
    val answered: Boolean get() = positive.isNotEmpty()
    fun first(): IntArray? = positive.values.firstOrNull()?.firstOrNull()

    companion object {
        fun nrcText(nrc: Int): String = when (nrc) {
            0x10 -> "General reject"; 0x11 -> "Service not supported"; 0x12 -> "Sub-function not supported"
            0x13 -> "Incorrect message length or format"; 0x14 -> "Response too long"; 0x21 -> "Busy, repeat request"
            0x22 -> "Conditions not correct"; 0x24 -> "Request sequence error"; 0x31 -> "Request out of range"
            0x33 -> "Security access denied"; 0x35 -> "Invalid key"; 0x36 -> "Exceeded number of attempts"
            0x37 -> "Required time delay not expired"; 0x72 -> "General programming failure"
            0x78 -> "Response pending"; 0x7E -> "Sub-function not supported in active session"
            0x7F -> "Service not supported in active session"
            else -> "NRC 0x" + nrc.toString(16).uppercase()
        }
    }
}

data class EcuInfo(val id: Int, val name: String)

data class VehicleInfoItem(val infoType: Int, val name: String, val ecu: Int, val value: String)

data class FreezeFrame(
    val frame: Int,
    val ecu: Int,
    val dtc: String?,
    /** channel key -> value */
    val values: Map<String, Double>,
    /** PID -> decoded text for status-type PIDs */
    val texts: Map<Int, String>,
)

data class O2TestResult(val sensor: String, val tid: Int, val name: String, val value: Double, val unit: String)

/**
 * All standard OBD-II services on top of an [Elm327] session. Every method is blocking and must
 * run on the command thread (see [CommandExecutor]).
 */
class ObdClient(val elm: Elm327) {
    val protocol: ObdProtocol get() = elm.protocol
    var retries = 1

    /** Sends a hex request (e.g. "010C") and parses the reply into per-ECU messages. */
    fun raw(hex: String, timeoutMs: Long = elm.defaultTimeoutMs): Pair<ElmReply, FrameParser.ParseResult> {
        var attempt = 0
        while (true) {
            val reply = elm.send(hex, timeoutMs)
            val parsed = FrameParser.parse(reply.lines.filter { ElmError.detect(listOf(it)) == null }, protocol, elm.headersOn)
            if (parsed.corruptLines > 0) elm.noteCorrupt()
            val retryable = (parsed.corruptLines > 0 && parsed.messages.isEmpty()) || (reply.error?.retryable == true && reply.error != ElmError.UNABLE_TO_CONNECT)
            if (retryable && attempt < retries) { attempt++; continue }
            return reply to parsed
        }
    }

    fun request(service: Int, vararg params: Int, timeoutMs: Long = elm.defaultTimeoutMs): ServiceResult {
        val hex = Hex.encode((listOf(service) + params.toList()), "")
        val (reply, parsed) = try { raw(hex, timeoutMs) } catch (e: ElmTimeoutException) {
            return ServiceResult(service, emptyMap(), emptyMap(), null, null)
        }
        val pos = LinkedHashMap<Int, MutableList<IntArray>>()
        val neg = LinkedHashMap<Int, Int>()
        for (m in parsed.messages) {
            if (m.data.isEmpty()) continue
            when {
                m.data[0] == service + 0x40 -> pos.getOrPut(m.ecu) { ArrayList() }.add(m.data.copyOfRange(1, m.data.size))
                m.data[0] == 0x7F && m.data.size >= 3 && m.data[1] == service -> if (m.data[2] != 0x78) neg[m.ecu] = m.data[2]
            }
        }
        // Response pending (0x78) with no final answer: give the ECU more time once.
        if (pos.isEmpty() && neg.isEmpty() && parsed.messages.any { it.data.size >= 3 && it.data[0] == 0x7F && it.data[2] == 0x78 }) {
            if (timeoutMs < 10_000) return request(service, *params, timeoutMs = 10_000)
        }
        return ServiceResult(service, pos, neg, reply.error, reply)
    }

    fun ecuName(id: Int): String = when {
        protocol.isCan && protocol.canIdBits == 11 -> when (id) {
            0x7E8 -> "Engine (ECM)"; 0x7E9 -> "Transmission (TCM)"; 0x7EA -> "ECU #3 (7EA)"; 0x7EB -> "ECU #4 (7EB)"
            else -> "ECU " + id.toString(16).uppercase()
        }
        protocol.isCan -> when (id) { 0x10 -> "Engine (ECM)"; 0x18 -> "Transmission (TCM)"; else -> "ECU " + id.toString(16).uppercase() }
        else -> when (id) { 0x10 -> "Engine (ECM)"; 0x18 -> "Transmission (TCM)"; else -> "ECU " + id.toString(16).uppercase() }
    }

    // ---------------------------------------------------------------- Mode 01 / PID discovery

    /** Walks support bitmaps 00, 20, 40... per ECU. Only PIDs an ECU reports are ever polled. */
    fun discoverPids(mode: Int = 0x01): Map<Int, Set<Int>> {
        val result = HashMap<Int, MutableSet<Int>>()
        var base = 0x00
        val continuing = HashSet<Int>()
        while (base <= 0xE0) {
            val r = if (mode == 0x02) request(0x02, base, 0x00) else request(mode, base)
            if (!r.answered) break
            var anyContinues = false
            for ((ecu, list) in r.positive) {
                for (payload in list) {
                    // payload: PID, A, B, C, D (mode 02 adds frame byte after PID)
                    val off = if (mode == 0x02) 2 else 1
                    if (payload.isEmpty() || payload[0] != base || payload.size < off + 4) continue
                    if (base != 0 && ecu !in continuing) continue
                    val bits = Pids.decodeSupport(base, payload.copyOfRange(off, off + 4))
                    result.getOrPut(ecu) { HashSet() }.addAll(bits)
                    if ((base + 0x20) in bits) { anyContinues = true; continuing.add(ecu) } else continuing.remove(ecu)
                }
            }
            if (!anyContinues) break
            base += 0x20
        }
        return result
    }

    /**
     * Reads several Mode 01 PIDs. On CAN, up to 6 PIDs are combined in one request (the standard
     * allows it and it multiplies the refresh rate); legacy protocols get one PID per request.
     * Returns ECU-merged data: PID -> data bytes (first ECU that answered, engine preferred).
     */
    fun readPids(pids: List<Int>, multi: Boolean = protocol.isCan): Map<Int, IntArray> {
        val out = HashMap<Int, IntArray>()
        if (pids.isEmpty()) return out
        val groups = if (multi) pids.chunked(6) else pids.map { listOf(it) }
        for (g in groups) {
            val r = request(0x01, *g.toIntArray())
            val payloads = r.positive.entries.sortedBy { if (it.key == 0x7E8 || it.key == 0x10) 0 else 1 }.flatMap { it.value }
            for (p in payloads) splitPidPayload(p, out)
        }
        return out
    }

    /** Splits "PID data PID data..." using the known data lengths. */
    internal fun splitPidPayload(p: IntArray, out: MutableMap<Int, IntArray>) {
        var i = 0
        while (i < p.size) {
            val pid = p[i]
            val spec = Pids[pid]
            val len = spec?.bytes ?: if (pid % 0x20 == 0) 4 else return
            if (i + 1 + len > p.size) return
            if (!out.containsKey(pid)) out[pid] = p.copyOfRange(i + 1, i + 1 + len)
            i += 1 + len
        }
    }

    // ---------------------------------------------------------------- Mode 02 freeze frame

    fun readFreezeFrame(frame: Int = 0): List<FreezeFrame> {
        val support = discoverPids(0x02)
        val frames = ArrayList<FreezeFrame>()
        for ((ecu, pids) in support) {
            val values = LinkedHashMap<String, Double>()
            val texts = LinkedHashMap<Int, String>()
            var dtc: String? = null
            for (pid in pids.sorted()) {
                if (pid % 0x20 == 0) continue
                val r = request(0x02, pid, frame)
                val data = r.positive[ecu]?.firstOrNull { it.isNotEmpty() && it[0] == pid } ?: continue
                val bytes = data.copyOfRange(2, data.size)
                if (pid == 0x02) { dtc = if (bytes.size >= 2) Dtc.decode(bytes[0], bytes[1]) else null; continue }
                val spec = Pids[pid] ?: continue
                spec.text?.let { t -> runCatching { texts[pid] = t(bytes) } }
                for (c in spec.channels) c.decode(bytes)?.let { values[c.key] = it }
            }
            if (values.isNotEmpty() || dtc != null) frames.add(FreezeFrame(frame, ecu, dtc, values, texts))
        }
        return frames
    }

    // ---------------------------------------------------------------- Modes 03 / 07 / 0A

    fun readDtcs(kind: DtcKind): List<Dtc> {
        require(kind != DtcKind.ENHANCED)
        val r = request(kind.mode, timeoutMs = 5_000)
        val out = ArrayList<Dtc>()
        for ((ecu, payloads) in r.positive) {
            for (p in payloads) {
                // CAN: first byte is the number of codes. Legacy: no count, 3 codes per message.
                val start = if (protocol.isCan) 1 else 0
                var i = start
                while (i + 1 < p.size) {
                    Dtc.decode(p[i], p[i + 1])?.let { code -> if (out.none { it.code == code && it.ecu == ecu }) out.add(Dtc(code, kind, ecu)) }
                    i += 2
                }
            }
        }
        return out
    }

    /** Mode 04. Only ever called after explicit user confirmation in the UI. */
    fun clearDtcs(): ServiceResult = request(0x04, timeoutMs = 8_000)

    // ---------------------------------------------------------------- Mode 05 (non-CAN O2 tests)

    fun readMode05(): List<O2TestResult> {
        if (protocol.isCan) return emptyList() // CAN vehicles report these through Mode 06
        val out = ArrayList<O2TestResult>()
        val layout = readPids(listOf(0x13))[0x13]?.get(0) ?: 0x11
        for (i in 0 until 8) {
            if (layout and (1 shl i) == 0) continue
            val sensorId = i + 1
            val name = "B${i / 4 + 1}S${i % 4 + 1}"
            for (tid in 0x01..0x09) {
                val r = request(0x05, tid, sensorId)
                val p = r.first() ?: continue
                if (p.size >= 3) {
                    val (v, unit) = Mode06.mode05Value(tid, p[2])
                    out.add(O2TestResult(name, tid, Mode06.mode05TestName(tid), v, unit))
                }
            }
        }
        return out
    }

    // ---------------------------------------------------------------- Mode 06

    fun readMode06(): List<Mode06.TestResult> {
        if (!protocol.isCan) return emptyList()
        val mids = TreeSet()
        var base = 0x00
        while (base <= 0xE0) {
            val r = request(0x06, base)
            val p = r.positive.values.flatten().firstOrNull { it.size >= 5 && it[0] == base } ?: break
            val bits = Pids.decodeSupport(base, p.copyOfRange(1, 5))
            mids.addAll(bits.filter { it % 0x20 != 0 })
            if ((base + 0x20) !in bits) break
            base += 0x20
        }
        val out = ArrayList<Mode06.TestResult>()
        for (mid in mids.values()) {
            val r = request(0x06, mid)
            r.positive.values.flatten().forEach { out.addAll(Mode06.decodeCan(it)) }
        }
        return out
    }

    private class TreeSet { private val s = java.util.TreeSet<Int>(); fun addAll(c: Collection<Int>) { s.addAll(c) }; fun values(): List<Int> = s.toList() }

    // ---------------------------------------------------------------- Mode 08

    /** Mode 08 TID 01 = EVAP system leak test (seal the system). Requires explicit user confirmation. */
    fun runEvapSealTest(): ServiceResult = request(0x08, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, timeoutMs = 5_000)

    fun mode08Supported(): Set<Int> {
        val r = request(0x08, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
        val p = r.first() ?: return emptySet()
        return if (p.size >= 5) Pids.decodeSupport(0, p.copyOfRange(1, 5)) else emptySet()
    }

    // ---------------------------------------------------------------- Mode 09

    fun readVehicleInfo(): List<VehicleInfoItem> {
        val out = ArrayList<VehicleInfoItem>()
        val support = request(0x09, 0x00)
        val supported = HashSet<Int>()
        for (p in support.positive.values.flatten()) {
            if (p.isNotEmpty() && p[0] == 0x00 && p.size >= 5) supported.addAll(Pids.decodeSupport(0, p.copyOfRange(p.size - 4, p.size)))
        }
        val wanted = if (supported.isEmpty()) setOf(0x02, 0x04, 0x06, 0x0A) else supported
        for (type in listOf(0x02, 0x04, 0x06, 0x08, 0x0A, 0x0B, 0x0D).filter { it in wanted }) {
            val r = request(0x09, type, timeoutMs = 5_000)
            for ((ecu, payloads) in r.positive) {
                val data = mergeMode09(type, payloads) ?: continue
                out.add(VehicleInfoItem(type, infoTypeName(type), ecu, formatInfo(type, data)))
            }
        }
        return out
    }

    fun readVin(): String? = readVehicleInfo().firstOrNull { it.infoType == 0x02 }?.value?.takeIf { it.length >= 11 }

    /** CAN: one ISO-TP message "type count data". Legacy: several "type seq data(4)" messages. */
    private fun mergeMode09(type: Int, payloads: List<IntArray>): IntArray? {
        val mine = payloads.filter { it.isNotEmpty() && it[0] == type }
        if (mine.isEmpty()) return null
        return if (protocol.isCan) {
            val p = mine.first()
            if (p.size < 2) null else p.copyOfRange(2, p.size)
        } else {
            mine.sortedBy { it.getOrElse(1) { 0 } }.flatMap { it.drop(2) }.toIntArray()
        }
    }

    fun infoTypeName(t: Int) = when (t) {
        0x02 -> "VIN"; 0x04 -> "Calibration ID"; 0x06 -> "Calibration verification number (CVN)"
        0x08 -> "In-use performance tracking (spark)"; 0x0A -> "ECU name"; 0x0B -> "In-use performance tracking (compression)"
        0x0D -> "Engine serial number"; else -> "Info type 0x" + t.toString(16)
    }

    private fun formatInfo(type: Int, d: IntArray): String = when (type) {
        0x02, 0x04, 0x0A, 0x0D -> ascii(d)
        0x06 -> d.toList().chunked(4).joinToString(", ") { Hex.encode(it, "") }
        0x08, 0x0B -> iptText(type, d)
        else -> Hex.encode(d.toList())
    }

    private fun ascii(d: IntArray): String = d.filter { it in 0x20..0x7E }.map { it.toChar() }.joinToString("").trim()
        .let { if (it.length > 17 && it.startsWith("\u0000")) it.trimStart('\u0000') else it }

    private fun iptText(type: Int, d: IntArray): String {
        val words = d.toList().chunked(2).filter { it.size == 2 }.map { it[0] * 256 + it[1] }
        val names = if (type == 0x08) listOf("OBD monitoring conditions", "Ignition cycles", "Catalyst B1 completions", "Catalyst B1 conditions",
            "Catalyst B2 completions", "Catalyst B2 conditions", "O2 B1 completions", "O2 B1 conditions", "O2 B2 completions", "O2 B2 conditions",
            "EGR completions", "EGR conditions", "AIR completions", "AIR conditions", "EVAP completions", "EVAP conditions",
            "Secondary O2 B1 completions", "Secondary O2 B1 conditions", "Secondary O2 B2 completions", "Secondary O2 B2 conditions")
        else listOf("OBD monitoring conditions", "Ignition cycles", "NMHC completions", "NMHC conditions", "NOx catalyst completions", "NOx catalyst conditions",
            "NOx adsorber completions", "NOx adsorber conditions", "PM filter completions", "PM filter conditions", "Exhaust sensor completions",
            "Exhaust sensor conditions", "EGR/VVT completions", "EGR/VVT conditions", "Boost completions", "Boost conditions")
        return words.mapIndexed { i, w -> "${names.getOrElse(i) { "Counter ${i + 1}" }}: $w" }.joinToString("\n")
    }

    // ---------------------------------------------------------------- ECU discovery & UDS

    /** Which ECUs answer the functional 0100 request. */
    fun discoverEcus(): List<EcuInfo> {
        val r = request(0x01, 0x00)
        return r.positive.keys.sorted().map { EcuInfo(it, ecuName(it)) }
    }

    /**
     * Enhanced (ISO 14229 UDS) access to one ECU over CAN 11-bit, by physical request id.
     * Only read services are offered here (0x19 read DTC, 0x22 read data by identifier).
     */
    fun <T> withPhysicalAddress(requestId: Int, block: () -> T): T {
        require(protocol.isCan) { "Enhanced access needs a CAN vehicle" }
        val header = if (protocol.canIdBits == 11) requestId.toString(16).uppercase().padStart(3, '0') else requestId.toString(16).uppercase().padStart(8, '0')
        elm.setHeader(header)
        try { return block() } finally { runCatching { elm.setHeader(null) } }
    }

    fun udsReadDtcs(requestId: Int, statusMask: Int = 0xFF): Pair<List<Dtc>, String?> = withPhysicalAddress(requestId) {
        val r = request(0x19, 0x02, statusMask, timeoutMs = 4_000)
        val out = ArrayList<Dtc>()
        for ((ecu, payloads) in r.positive) for (p in payloads) {
            // 59 02 availabilityMask [DTC(3 bytes) status]...
            var i = 2
            while (i + 3 < p.size) {
                val code = Dtc.decode(p[i], p[i + 1])
                val status = p[i + 3]
                if (code != null) {
                    val ftb = p[i + 2]
                    out.add(Dtc(if (ftb == 0) code else "$code-" + Hex.byte(ftb), DtcKind.ENHANCED, ecu, status))
                }
                i += 4
            }
        }
        val err = r.negative.values.firstOrNull()?.let { ServiceResult.nrcText(it) } ?: r.error?.meaning
        out to (if (!r.answered) err ?: "No answer" else null)
    }

    fun udsReadDid(requestId: Int, did: Int): Pair<IntArray?, String?> = withPhysicalAddress(requestId) {
        val r = request(0x22, did shr 8, did and 0xFF, timeoutMs = 3_000)
        val p = r.first()
        if (p != null && p.size >= 2 && p[0] == did shr 8 && p[1] == did and 0xFF) p.copyOfRange(2, p.size) to null
        else null to (r.negative.values.firstOrNull()?.let { ServiceResult.nrcText(it) } ?: r.error?.meaning ?: "No answer")
    }

    /** Standard ISO 14229 identification DIDs that most UDS ECUs implement. */
    val standardIdentificationDids = linkedMapOf(
        0xF187 to "Spare part number",
        0xF188 to "ECU software number",
        0xF189 to "ECU software version",
        0xF18A to "System supplier id",
        0xF18C to "ECU serial number",
        0xF190 to "VIN",
        0xF191 to "ECU hardware number",
        0xF192 to "Supplier hardware number",
        0xF194 to "Supplier software number",
        0xF197 to "System name / engine type",
    )

    fun asciiOrHex(d: IntArray): String {
        val printable = d.count { it in 0x20..0x7E }
        return if (d.isNotEmpty() && printable >= d.size * 0.8) ascii(d) else Hex.encode(d.toList())
    }
}
