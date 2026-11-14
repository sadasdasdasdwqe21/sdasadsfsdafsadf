package app.obdforge.core.sim

import app.obdforge.core.dtc.Dtc
import app.obdforge.core.isotp.IsoTp
import app.obdforge.core.transport.ConnectionKind
import app.obdforge.core.transport.QueuedTransport
import app.obdforge.core.transport.TransportException
import app.obdforge.core.util.Hex
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A software ELM327 with a simulated car behind it. It answers AT commands like a v1.4 chip,
 * speaks CAN 11-bit (two ECUs: engine 7E8 and transmission 7E9) or ISO 9141-2, does ISO-TP
 * multi-frame output, keeps DTC/freeze-frame/readiness state, and can inject faults and link
 * drops. Everything it produces is labelled as simulated by the app.
 */
class SimulatedElm327(
    scenario: SimScenario = SimScenario.NORMAL,
    val legacyProtocol: Boolean = false,
    private val latencyMs: Long = 25,
    private val clock: () -> Long = { System.currentTimeMillis() },
    seed: Long = 7,
) : QueuedTransport() {
    override val kind = ConnectionKind.SIMULATOR
    override val description = "OBD Forge simulator (${if (legacyProtocol) "ISO 9141-2" else "CAN 11-bit 500k"})"

    val model = VehicleModel(scenario, seed)
    var scenario: SimScenario
        get() = model.scenario
        set(v) { model.scenario = v; scenarioStartT = model.t }

    /** Simulated time multiplier (tests run the model faster than real time). */
    var timeScale = 1.0

    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "sim-elm").apply { isDaemon = true } }
    private val rx = StringBuilder()
    private var lastStepMs = 0L
    private var scenarioStartT = 0.0

    // Adapter state
    private var echo = true
    private var headers = false
    private var spaces = true
    private var linefeeds = false
    private var protocolSet = '0'
    private var protocolFound = false
    private var header = "7DF"
    private var caf = true
    @Volatile private var linkDown = false
    var requestCount = 0; private set

    // Vehicle diagnostic state
    private val stored = LinkedHashSet<String>()
    private val pending = LinkedHashSet<String>()
    private val permanent = LinkedHashSet<String>()
    private var freezeFrame: Map<Int, IntArray>? = null
    private var freezeDtc: String? = null
    private var monitorsComplete = true
    private var cleanDriveSeconds = 0.0
    val vin = "SIMULATED0VIN0001"

    override fun open() {
        if (worker.isShutdown) throw TransportException("Simulator closed")
        resetQueue()
        linkDown = false
        opened = true
        lastStepMs = clock()
    }

    override fun write(data: ByteArray) {
        if (!opened) throw TransportException("Simulator not open")
        if (linkDown) throw TransportException("Simulated link drop")
        val s = String(data, Charsets.US_ASCII)
        for (ch in s) {
            if (ch == '\r') {
                val cmd = rx.toString().trim()
                rx.setLength(0)
                val delay = latencyMs + if (cmd.startsWith("AT", true)) 0 else 10
                worker.schedule({ respond(cmd) }, delay, TimeUnit.MILLISECONDS)
            } else if (ch != '\n') rx.append(ch)
        }
    }

    /** Closes the link but keeps the simulated car, so a reconnect finds it in the same state. */
    override fun close() {
        opened = false
    }

    /** Releases the simulator thread for good. */
    fun shutdown() {
        opened = false
        worker.shutdownNow()
    }

    override fun signalQuality(): Int = if (scenario == SimScenario.COMM_FAILURE) 55 else 100

    /** Forces a link drop, as if Bluetooth went out of range. */
    fun dropLink() {
        linkDown = true
        fail(TransportException("Simulated Bluetooth disconnect"))
    }

    private fun advance() {
        val now = clock()
        val dt = ((now - lastStepMs).coerceIn(0, 2000)) / 1000.0 * timeScale
        lastStepMs = now
        // Step in small increments so the model stays stable.
        var left = dt
        while (left > 0) { val s = minOf(0.1, left); model.step(s); left -= s; updateFaults(s) }
    }

    /** Advances simulated time directly (used by tests and the "fast forward" button). */
    fun fastForward(seconds: Double) {
        var left = seconds
        while (left > 0) { val s = minOf(0.1, left); model.step(s); left -= s; updateFaults(s) }
    }

    private fun updateFaults(dt: Double) {
        val since = model.t - scenarioStartT
        val code = when (scenario) {
            SimScenario.RICH -> "P0172"
            SimScenario.LEAN -> "P0171"
            SimScenario.OVERHEAT -> if (model.ect > 115) "P0217" else null
            SimScenario.MISFIRE -> "P0302"
            SimScenario.SENSOR_FAULT -> "P0134"
            SimScenario.UNDERBOOST -> if (model.throttle > 80) "P0299" else null
            else -> null
        }
        if (code != null && model.engineRunning) {
            if (since > 15 && code !in pending && code !in stored) pending.add(code)
            if (since > 45 && code !in stored) {
                stored.add(code); permanent.add(code)
                if (freezeFrame == null) { freezeFrame = snapshot(); freezeDtc = code }
            }
        }
        if (stored.isEmpty() && pending.isEmpty() && model.speed > 10) {
            cleanDriveSeconds += dt
            if (cleanDriveSeconds > 120) { monitorsComplete = true; permanent.clear() }
        }
    }

    private fun snapshot(): Map<Int, IntArray> = listOf(0x03, 0x04, 0x05, 0x06, 0x07, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x11)
        .associateWith { pidData(it, 0x7E8)!! }

    fun injectDtc(code: String, kind: String = "stored") {
        when (kind) { "pending" -> pending.add(code); else -> { stored.add(code); permanent.add(code); if (freezeFrame == null) { freezeFrame = snapshot(); freezeDtc = code } } }
    }

    // ------------------------------------------------------------------ command handling

    private fun respond(cmdRaw: String) {
        if (!opened || linkDown) return
        requestCount++
        advance()
        val cmd = cmdRaw.uppercase().replace(" ", "")
        val sb = StringBuilder()
        if (echo) sb.append(cmdRaw).append('\r')
        val body: String = if (cmd.startsWith("AT")) at(cmd.substring(2)) else if (cmd.startsWith("ST")) "?" else obd(cmd)
        sb.append(body)
        val eol = if (linefeeds) "\r\n" else "\r"
        val out = sb.toString().replace("\r", eol) + eol + eol + ">"
        if (scenario == SimScenario.COMM_FAILURE && !cmd.startsWith("AT")) {
            if (model.chance(0.02)) { dropLink(); return }
            if (model.chance(0.05)) { deliverText(out.replaceFirst(Regex("[0-9A-F]{2} "), "Z\u0007 ")); return }
        }
        deliverText(out)
    }

    private fun deliverText(s: String) {
        // Split into chunks like a real serial link would.
        val bytes = s.toByteArray(Charsets.ISO_8859_1)
        var i = 0
        while (i < bytes.size) {
            val n = minOf(20, bytes.size - i)
            deliverBytes(bytes.copyOfRange(i, i + n))
            i += n
        }
    }

    private fun deliverBytes(b: ByteArray) = deliver(b)

    private fun at(c: String): String {
        return when {
            c == "Z" -> { reset(); "\rELM327 v1.4b" }
            c == "WS" -> { reset(); "\rELM327 v1.4b" }
            c == "I" -> "ELM327 v1.4b"
            c == "@1" -> "OBD Forge Simulator"
            c == "E0" -> { echo = false; "OK" }
            c == "E1" -> { echo = true; "OK" }
            c == "L0" -> { linefeeds = false; "OK" }
            c == "L1" -> { linefeeds = true; "OK" }
            c == "S0" -> { spaces = false; "OK" }
            c == "S1" -> { spaces = true; "OK" }
            c == "H0" -> { headers = false; "OK" }
            c == "H1" -> { headers = true; "OK" }
            c.startsWith("AT") -> "OK"
            c.startsWith("SP") || c.startsWith("TP") -> {
                val p = c.last()
                protocolSet = p
                protocolFound = p != '0' && p != 'A'
                if (protocolFound && !protocolMatches(p)) protocolFound = false
                "OK"
            }
            c == "DPN" -> {
                val code = if (legacyProtocol) '3' else '6'
                if (protocolSet == '0') (if (protocolFound) "A$code" else "A0") else protocolSet.toString()
            }
            c == "DP" -> if (legacyProtocol) "ISO 9141-2" else "ISO 15765-4 (CAN 11/500)"
            c == "RV" -> String.format("%.1fV", if (model.engineRunning) model.voltage else 12.3)
            c.startsWith("SH") -> { header = c.substring(2); "OK" }
            c.startsWith("CRA") || c.startsWith("CAF") || c.startsWith("FC") || c.startsWith("IB") || c.startsWith("ST") ||
                c.startsWith("D") || c.startsWith("M0") || c.startsWith("CFC") -> {
                if (c == "CAF0") caf = false; if (c == "CAF1") caf = true; "OK" }
            else -> "?"
        }
    }

    private fun protocolMatches(p: Char) = if (legacyProtocol) p == '3' else p == '6'

    private fun reset() {
        echo = true; headers = false; spaces = true; linefeeds = false; header = "7DF"; caf = true
        protocolSet = '0'; protocolFound = false
    }

    private fun obd(c0: String): String {
        if (!Hex.isHex(c0) || c0.length < 2) return "?"
        // ELM allows a trailing single digit = number of expected responses.
        val c = if (c0.length % 2 == 1) c0.dropLast(1) else c0
        val req = Hex.decode(c) ?: return "?"
        var prefix = ""
        if (protocolSet != '0' && !protocolMatches(protocolSet)) {
            return if (legacyProtocol) "BUS INIT: ...ERROR" else "CAN ERROR"
        }
        if (!protocolFound) {
            prefix = "SEARCHING...\r"
            if (protocolSet == '0') protocolFound = true
        }
        if (scenario == SimScenario.COMM_FAILURE && model.chance(0.12)) return prefix + "NO DATA"
        val physical = header != "7DF" && header != "686AF1"
        val responses = ArrayList<Pair<Int, IntArray>>() // ecu id, payload
        if (physical) {
            val target = header.toIntOrNull(16) ?: 0
            val ecu = when (target) { 0x7E0 -> 0x7E8; 0x7E1 -> 0x7E9; else -> null }
            if (ecu != null) answer(ecu, req, true)?.let { responses.add(ecu to it) }
        } else {
            for (ecu in listOf(0x7E8, 0x7E9)) answer(ecu, req, false)?.let { responses.add(ecu to it) }
        }
        if (responses.isEmpty()) return prefix + "NO DATA"
        return prefix + responses.joinToString("\r") { format(it.first, it.second) }
    }

    /** Formats one ECU payload the way an ELM327 prints it with the current settings. */
    private fun format(ecu: Int, payload: IntArray): String {
        val sep = if (spaces) " " else ""
        if (legacyProtocol) {
            // ISO 9141: 48 6B <src> data... checksum ; one message per line, max 7 data bytes.
            val src = if (ecu == 0x7E8) 0x10 else 0x18
            val chunks = if (payload.size <= 7) listOf(payload.toList()) else legacySplit(payload)
            return chunks.joinToString("\r") { data ->
                val msg = listOf(0x48, 0x6B, src) + data
                val cs = msg.sum() and 0xFF
                if (headers) Hex.encode(msg + cs, sep) else Hex.encode(data, sep)
            }
        }
        val id = ecu.toString(16).uppercase()
        if (headers) {
            return IsoTp.segment(payload, pad = true, padding = 0x00).joinToString("\r") { f -> id + sep + Hex.encode(f.toList(), sep) }
        }
        if (payload.size <= 7) return Hex.encode(payload.toList(), sep)
        // Headers off multi-frame: byte count then "n: data" lines.
        val frames = IsoTp.segment(payload, pad = false)
        val sb = StringBuilder(String.format("%03X", payload.size))
        frames.forEachIndexed { i, f ->
            val data = if (i == 0) f.copyOfRange(2, f.size) else f.copyOfRange(1, f.size)
            sb.append('\r').append(Integer.toHexString(i and 0xF).uppercase()).append(": ").append(Hex.encode(data.toList(), sep))
        }
        return sb.toString()
    }

    /** Legacy protocols send Mode 09 / Mode 03 data as several numbered 7-byte messages. */
    private fun legacySplit(payload: IntArray): List<List<Int>> {
        val sid = payload[0]
        if (sid == 0x49 && payload.size > 3) {
            val type = payload[1]
            val data = payload.drop(3) // after count byte
            return data.chunked(4).mapIndexed { i, ch -> listOf(sid, type, i + 1) + ch + List(4 - ch.size) { 0 } }
        }
        if (sid == 0x43) {
            val codes = payload.drop(2) // after count byte
            val padded = if (codes.isEmpty()) List(6) { 0 } else codes + List((6 - codes.size % 6) % 6) { 0 }
            return padded.chunked(6).map { listOf(sid) + it }
        }
        return payload.toList().chunked(7)
    }

    private fun bitmap(base: Int, pids: Set<Int>): IntArray {
        val b = IntArray(4)
        for (p in pids) {
            val idx = p - base - 1
            if (idx in 0..31) b[idx / 8] = b[idx / 8] or (0x80 shr (idx % 8))
        }
        return b
    }

    private val ecmPids = sortedSetOf(0x01, 0x03, 0x04, 0x05, 0x06, 0x07, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x11, 0x13, 0x15,
        0x1C, 0x1F, 0x20, 0x21, 0x23, 0x24, 0x2E, 0x2F, 0x30, 0x31, 0x33, 0x3C, 0x40, 0x41, 0x42, 0x43, 0x44, 0x45, 0x46,
        0x47, 0x49, 0x4A, 0x4C, 0x4D, 0x4E, 0x51, 0x5C)
    private val tcmPids = sortedSetOf(0x01, 0x05, 0x0C, 0x0D, 0x1C)

    private fun supportedFor(ecu: Int) = if (ecu == 0x7E8) ecmPids else tcmPids

    private fun answer(ecu: Int, req: IntArray, physical: Boolean): IntArray? {
        val sid = req[0]
        val engine = ecu == 0x7E8
        fun neg(nrc: Int) = intArrayOf(0x7F, sid, nrc)
        return when (sid) {
            0x01 -> {
                val out = ArrayList<Int>()
                out.add(0x41)
                for (pid in req.drop(1)) {
                    val d = pidResponse(pid, ecu) ?: continue
                    out.add(pid); out.addAll(d.toList())
                }
                if (out.size == 1) null else out.toIntArray()
            }
            0x02 -> {
                if (!engine || req.size < 2) return null
                val pid = req[1]; val frame = req.getOrElse(2) { 0 }
                val ff = freezeFrame ?: return if (pid == 0x00) intArrayOf(0x42, 0x00, frame, 0, 0, 0, 0) else null
                if (pid == 0x00) return intArrayOf(0x42, 0x00, frame) + bitmap(0, ff.keys + 0x02)
                if (pid == 0x02) return intArrayOf(0x42, 0x02, frame) + Dtc.encode(freezeDtc ?: return null)
                val d = ff[pid] ?: return null
                intArrayOf(0x42, pid, frame) + d
            }
            0x03, 0x07, 0x0A -> {
                if (!engine) return if (sid == 0x03) intArrayOf(0x43, 0) else null
                val set = when (sid) { 0x03 -> stored; 0x07 -> pending; else -> permanent }
                val bytes = set.flatMap { Dtc.encode(it).toList() }
                (listOf(sid + 0x40, set.size) + bytes).toIntArray()
            }
            0x04 -> {
                stored.clear(); pending.clear(); freezeFrame = null; freezeDtc = null
                monitorsComplete = false; cleanDriveSeconds = 0.0; model.clearAdaptives()
                scenarioStartT = model.t
                intArrayOf(0x44)
            }
            0x05 -> if (!legacyProtocol || !engine) null else {
                val tid = req.getOrElse(1) { 0 }; val sensor = req.getOrElse(2) { 1 }
                val raw = when (tid) { 1 -> 90; 2 -> 90; 3 -> 60; 4 -> 120; 5 -> 18; 6 -> 20; 7 -> 12; 8 -> 178; 9 -> 25; else -> return null }
                intArrayOf(0x45, tid, sensor, raw)
            }
            0x06 -> if (legacyProtocol || !engine) null else mode06(req.getOrElse(1) { 0 })
            0x08 -> if (!engine) null else when (req.getOrElse(1) { 0 }) {
                0x00 -> intArrayOf(0x48, 0x00, 0x80, 0x00, 0x00, 0x00)
                0x01 -> if (model.engineRunning && model.rpm > 0) neg(0x22) else intArrayOf(0x48, 0x01)
                else -> neg(0x12)
            }
            0x09 -> mode09(ecu, req.getOrElse(1) { 0 })
            0x19 -> if (!physical) null else if (req.getOrElse(1) { 0 } == 0x02) {
                val list = if (engine) stored.map { it to 0x2F } + pending.filter { it !in stored }.map { it to 0x24 } else listOf<Pair<String, Int>>()
                val out = arrayListOf(0x59, 0x02, 0xFF)
                for ((code, status) in list) { out.addAll(Dtc.encode(code).toList()); out.add(0x00); out.add(status) }
                if (!engine) { out.addAll(listOf(0x07, 0x15, 0x00, 0x08)) } // TCM history code, not confirmed
                out.toIntArray()
            } else neg(0x12)
            0x22 -> if (!physical || req.size < 3) null else {
                val did = req[1] * 256 + req[2]
                val text = when (did) {
                    0xF190 -> vin
                    0xF187 -> if (engine) "SIM-ECM-22611" else "SIM-TCM-31711"
                    0xF189 -> if (engine) "SW 1.04" else "SW 2.10"
                    0xF18C -> if (engine) "ECM0042" else "TCM0042"
                    0xF197 -> if (engine) "SIM 2.0T DI" else "SIM CVT"
                    else -> null
                }
                if (text == null) neg(0x31) else intArrayOf(0x62, req[1], req[2]) + text.map { it.code }.toIntArray()
            }
            0x10, 0x11, 0x14, 0x27, 0x2E, 0x2F, 0x31, 0x34, 0x3D -> if (physical) neg(0x11) else null
            else -> if (engine) neg(0x11) else null
        }
    }

    private fun pidResponse(pid: Int, ecu: Int): IntArray? {
        val sup = supportedFor(ecu)
        if (pid % 0x20 == 0) {
            val inRange = sup.filter { it > pid && it <= pid + 0x20 }.toSet()
            if (pid != 0 && pid !in sup) return null
            return bitmap(pid, inRange)
        }
        if (pid !in sup) return null
        return pidData(pid, ecu)
    }

    private fun u8(v: Double) = v.coerceIn(0.0, 255.0).toInt()
    private fun u16(v: Double): IntArray { val x = v.coerceIn(0.0, 65535.0).toInt(); return intArrayOf(x shr 8, x and 0xFF) }
    private fun pctByte(v: Double) = u8(v * 255 / 100)
    private fun trimByte(v: Double) = u8(v * 128 / 100 + 128)

    internal fun pidData(pid: Int, ecu: Int): IntArray? {
        val m = model
        return when (pid) {
            0x01 -> {
                val dtcs = if (ecu == 0x7E8) stored.size else 0
                val mil = if (dtcs > 0) 0x80 else 0
                // spark ignition: misfire, fuel, components available; catalyst, evap, O2, O2 heater available
                val incompleteC = if (monitorsComplete) 0 else 0b0110_0101
                intArrayOf(mil or dtcs, 0b0000_0111, 0b0110_0101, incompleteC)
            }
            0x03 -> intArrayOf(if (!m.engineRunning) 0 else if (m.closedLoop) 2 else if (m.throttle > 79 || m.load == 0.0) 4 else 1, 0)
            0x04 -> intArrayOf(pctByte(m.load))
            0x05 -> intArrayOf(u8(m.ect + 40))
            0x06 -> intArrayOf(trimByte(m.stft1))
            0x07 -> intArrayOf(trimByte(m.ltft1))
            0x0B -> intArrayOf(u8(m.map))
            0x0C -> u16(m.rpm * 4)
            0x0D -> intArrayOf(u8(m.speed))
            0x0E -> intArrayOf(u8((m.timing + 64) * 2))
            0x0F -> intArrayOf(u8(m.iat + 40))
            0x10 -> u16(m.maf * 100)
            0x11 -> intArrayOf(pctByte(m.throttle * 0.83 + 14))
            0x13 -> intArrayOf(0b0000_0011)
            0x15 -> intArrayOf(u8(m.o2s2 * 200), 0xFF)
            0x1C -> intArrayOf(if (legacyProtocol) 3 else 11)
            0x1F -> u16(m.runtime)
            0x21 -> u16(if (stored.isEmpty()) 0.0 else 12.0)
            0x23 -> u16(m.railPressureKpa / 10)
            0x24 -> u16(m.lambdaWide * 32768) + u16((if (m.scenario == SimScenario.SENSOR_FAULT) 0.45 else m.o2s1) * 8192)
            0x2E -> intArrayOf(pctByte(if (m.closedLoop && m.speed > 10) 22.0 else 0.0))
            0x2F -> intArrayOf(pctByte(m.fuelLevel))
            0x30 -> intArrayOf(m.warmups.coerceAtMost(255))
            0x31 -> u16(m.distanceSinceClear)
            0x33 -> intArrayOf(u8(m.baro))
            0x3C -> u16((m.catTemp + 40) * 10)
            0x41 -> intArrayOf(0, 0b0000_0111, 0b0110_0101, if (m.runtime > 300) 0b0000_0100 else 0b0110_0101)
            0x42 -> u16((if (m.engineRunning) m.voltage else 12.3) * 1000)
            0x43 -> u16(m.load * 255 / 100 * (m.map / m.baro).coerceAtLeast(0.2))
            0x44 -> u16(m.lambdaCmd * 32768)
            0x45 -> intArrayOf(pctByte(m.throttle))
            0x46 -> intArrayOf(u8(m.ambient + 40))
            0x47 -> intArrayOf(pctByte(m.throttle * 0.83 + 30))
            0x49 -> intArrayOf(pctByte(m.throttle * 0.8 + 15))
            0x4A -> intArrayOf(pctByte(m.throttle * 0.4 + 7))
            0x4C -> intArrayOf(pctByte(m.throttle))
            0x4D -> u16(if (stored.isEmpty()) 0.0 else 9.0)
            0x4E -> u16(m.timeSinceClearMin)
            0x51 -> intArrayOf(1)
            0x5C -> intArrayOf(u8(m.oilTemp + 40))
            else -> null
        }
    }

    private fun mode06(mid: Int): IntArray? {
        val supported = setOf(0x01, 0x02, 0x21, 0x41, 0xA1, 0xA2, 0xA3, 0xA4, 0xA5)
        if (mid == 0x00) return intArrayOf(0x46, 0x00) + bitmap(0, supported.filter { it <= 0x20 }.toSet() + 0x20)
        if (mid == 0x20) return intArrayOf(0x46, 0x20) + bitmap(0x20, supported.filter { it in 0x21..0x40 }.toSet() + 0x40)
        if (mid == 0x40) return intArrayOf(0x46, 0x40) + bitmap(0x40, supported.filter { it in 0x41..0x60 }.toSet() + 0x60)
        if (mid == 0x60) return intArrayOf(0x46, 0x60) + bitmap(0x60, setOf(0x80))
        if (mid == 0x80) return intArrayOf(0x46, 0x80) + bitmap(0x80, setOf(0xA0))
        if (mid == 0xA0) return intArrayOf(0x46, 0xA0) + bitmap(0xA0, supported.filter { it in 0xA1..0xC0 }.toSet())
        if (mid !in supported) return null
        fun rec(tid: Int, uas: Int, v: Int, min: Int, max: Int) = listOf(mid, tid, uas, v shr 8, v and 0xFF, min shr 8, min and 0xFF, max shr 8, max and 0xFF)
        val records: List<Int> = when (mid) {
            // O2 sensor B1S1: rich-lean switch time in ms (UASID 0x10), and threshold voltage (0x0B, mV units of 0.001 V)
            0x01 -> rec(0x05, 0x10, if (scenario == SimScenario.SENSOR_FAULT) 1650 else 72, 0, 400) + rec(0x01, 0x0B, 450, 300, 600)
            0x02 -> rec(0x07, 0x0B, 620, 400, 1000)
            // Catalyst B1: oxygen storage ratio, counts (UASID 0x01)
            0x21 -> rec(0x80, 0x24, if (scenario == SimScenario.RICH) 120 else 35, 0, 100)
            0x41 -> rec(0x81, 0x14, 6, 2, 12)
            0xA1 -> rec(0x0B, 0x24, m6misfire(-1), 0, 0xFFFF) + rec(0x0C, 0x24, m6misfire(-1), 0, 0xFFFF)
            in 0xA2..0xA5 -> { val cyl = mid - 0xA2; rec(0x0B, 0x24, m6misfire(cyl) / 10, 0, 0xFFFF) + rec(0x0C, 0x24, m6misfire(cyl), 0, 0xFFFF) }
            else -> return null
        }
        return (listOf(0x46) + records).toIntArray()
    }

    private fun m6misfire(cyl: Int): Int = if (cyl < 0) model.misfireCounts.sum() else model.misfireRate(cyl)

    private fun mode09(ecu: Int, type: Int): IntArray? {
        val engine = ecu == 0x7E8
        fun text(t: String, pad: Int) = t.padEnd(pad, '\u0000').map { it.code }
        return when (type) {
            0x00 -> intArrayOf(0x49, 0x00) + bitmap(0, if (engine) setOf(0x02, 0x04, 0x06, 0x08, 0x0A) else setOf(0x04, 0x0A))
            0x02 -> if (!engine) null else (listOf(0x49, 0x02, 0x01) + text(vin, 17)).toIntArray()
            0x04 -> (listOf(0x49, 0x04, 0x01) + text(if (engine) "SIMCAL-ECM-0042" else "SIMCAL-TCM-0007", 16)).toIntArray()
            0x06 -> intArrayOf(0x49, 0x06, 0x01, 0x1A, 0x2B, 0x3C, if (engine) 0x4D else 0x5E)
            0x08 -> if (!engine) null else {
                val counters = listOf(412, 1630, 380, 412, 0, 0, 395, 412, 0, 0, 0, 0, 0, 0, 210, 412, 388, 412, 0, 0)
                (listOf(0x49, 0x08, counters.size) + counters.flatMap { listOf(it shr 8, it and 0xFF) }).toIntArray()
            }
            0x0A -> (listOf(0x49, 0x0A, 0x01) + text(if (engine) "ECM-EngineControl" else "TCM-TransmissionC", 20)).toIntArray()
            else -> null
        }
    }
}
