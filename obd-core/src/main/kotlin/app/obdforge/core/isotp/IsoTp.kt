package app.obdforge.core.isotp

/**
 * ISO 15765-2 (ISO-TP) transport layer: splitting payloads into CAN frames and putting
 * received frames back together. The ELM327 normally handles flow control itself, but it
 * passes every frame through to us when headers are on, so we reassemble per ECU here.
 */
object IsoTp {
    const val SINGLE = 0x0
    const val FIRST = 0x1
    const val CONSECUTIVE = 0x2
    const val FLOW_CONTROL = 0x3

    /** Splits [payload] into 8-byte CAN data fields. Pads with [padding] when [pad] is true. */
    fun segment(payload: IntArray, pad: Boolean = true, padding: Int = 0xAA): List<IntArray> {
        require(payload.isNotEmpty()) { "Empty ISO-TP payload" }
        require(payload.size <= 4095) { "Payload too long for classic ISO-TP" }
        val frames = ArrayList<IntArray>()
        if (payload.size <= 7) {
            val f = IntArray(1 + payload.size)
            f[0] = payload.size
            payload.copyInto(f, 1)
            frames.add(if (pad) padTo8(f, padding) else f)
            return frames
        }
        val first = IntArray(8)
        first[0] = 0x10 or (payload.size shr 8)
        first[1] = payload.size and 0xFF
        payload.copyInto(first, 2, 0, 6)
        frames.add(first)
        var offset = 6
        var seq = 1
        while (offset < payload.size) {
            val n = minOf(7, payload.size - offset)
            val f = IntArray(1 + n)
            f[0] = 0x20 or (seq and 0x0F)
            payload.copyInto(f, 1, offset, offset + n)
            frames.add(if (pad) padTo8(f, padding) else f)
            offset += n
            seq = (seq + 1) and 0x0F
        }
        return frames
    }

    private fun padTo8(f: IntArray, padding: Int): IntArray {
        if (f.size >= 8) return f
        val out = IntArray(8) { padding }
        f.copyInto(out)
        return out
    }

    data class FlowControl(val status: Status, val blockSize: Int, val separationTimeMicros: Int) {
        enum class Status { CONTINUE, WAIT, OVERFLOW }
    }

    fun parseFlowControl(frame: IntArray): FlowControl? {
        if (frame.isEmpty() || (frame[0] shr 4) != FLOW_CONTROL) return null
        val status = when (frame[0] and 0x0F) {
            0 -> FlowControl.Status.CONTINUE
            1 -> FlowControl.Status.WAIT
            2 -> FlowControl.Status.OVERFLOW
            else -> return null
        }
        val bs = if (frame.size > 1) frame[1] else 0
        val st = if (frame.size > 2) frame[2] else 0
        val micros = when (st) {
            in 0x00..0x7F -> st * 1000
            in 0xF1..0xF9 -> (st - 0xF0) * 100
            else -> 127_000 // reserved values must be treated as the maximum
        }
        return FlowControl(status, bs, micros)
    }

    fun flowControlFrame(blockSize: Int = 0, stMinMs: Int = 0): IntArray =
        intArrayOf(0x30, blockSize and 0xFF, stMinMs.coerceIn(0, 0x7F), 0, 0, 0, 0, 0)
}

/**
 * Collects frames from possibly several ECUs answering at once and emits complete messages.
 * Each ECU (CAN id) has its own reassembly state, so interleaved responses are handled.
 */
class IsoTpReassembler {
    class Message(val canId: Int, val payload: IntArray)

    private class State(val expected: Int) {
        val data = ArrayList<Int>(expected)
        var nextSeq = 1
    }

    private val states = HashMap<Int, State>()
    private val done = ArrayList<Message>()
    val errors = ArrayList<String>()

    fun accept(canId: Int, frame: IntArray) {
        if (frame.isEmpty()) return
        when (frame[0] shr 4) {
            IsoTp.SINGLE -> {
                var len = frame[0] and 0x0F
                var start = 1
                if (len == 0 && frame.size > 1) { // CAN FD style escape
                    len = frame[1]; start = 2
                }
                if (len == 0 || start + len > frame.size) {
                    // Some adapters strip padding and the length is still right; if the frame is
                    // shorter than declared, it is corrupt.
                    errors.add("Bad single frame from ${canId.toString(16)}")
                    return
                }
                done.add(Message(canId, frame.copyOfRange(start, start + len)))
            }
            IsoTp.FIRST -> {
                if (frame.size < 2) { errors.add("Short first frame"); return }
                val len = ((frame[0] and 0x0F) shl 8) or frame[1]
                val st = State(len)
                for (i in 2 until frame.size) if (st.data.size < len) st.data.add(frame[i])
                states[canId] = st
            }
            IsoTp.CONSECUTIVE -> {
                val st = states[canId] ?: run { errors.add("Consecutive frame without first frame"); return }
                val seq = frame[0] and 0x0F
                if (seq != st.nextSeq) {
                    errors.add("Sequence error from ${canId.toString(16)}: expected ${st.nextSeq} got $seq")
                    states.remove(canId)
                    return
                }
                st.nextSeq = (st.nextSeq + 1) and 0x0F
                for (i in 1 until frame.size) if (st.data.size < st.expected) st.data.add(frame[i])
                if (st.data.size >= st.expected) {
                    done.add(Message(canId, st.data.toIntArray()))
                    states.remove(canId)
                }
            }
            IsoTp.FLOW_CONTROL -> Unit // flow control from the ECU is consumed by the sender
            else -> errors.add("Unknown PCI ${frame[0].toString(16)}")
        }
    }

    /** Messages that started but never finished (lost frames). */
    fun incomplete(): List<Int> = states.keys.toList()

    fun messages(): List<Message> = done.toList()
}
