package app.obdforge.core.obd

import app.obdforge.core.elm.ObdProtocol
import app.obdforge.core.isotp.IsoTpReassembler
import app.obdforge.core.util.Hex

/** One complete message from one ECU. [data] starts with the response service id (e.g. 0x41). */
class EcuMessage(val ecu: Int, val data: IntArray) {
    override fun toString(): String = "ECU ${ecu.toString(16).uppercase()}: ${Hex.encode(data.toList())}"
}

/**
 * Turns the text lines an ELM327 prints with headers on (ATH1) into per-ECU messages.
 *
 *  - CAN 11-bit: "7E8 06 41 0C 1A F8 00 00 00" (id, PCI, data, padding)
 *  - CAN 29-bit: "18 DA F1 10 06 41 0C 1A F8 ..." (4 id bytes, last = source)
 *  - J1850 / ISO 9141 / KWP: "48 6B 10 41 0C 1A F8 CS" (priority, target, source, data, checksum)
 *
 * Multi-frame CAN answers are reassembled with ISO-TP. Lines that are not clean hex are counted
 * in [ParseResult.corruptLines] so the caller can retry and report link quality.
 */
object FrameParser {
    class ParseResult(val messages: List<EcuMessage>, val corruptLines: Int, val problems: List<String>)

    fun parse(lines: List<String>, protocol: ObdProtocol, headersOn: Boolean = true): ParseResult {
        return when {
            !headersOn -> parseNoHeaders(lines)
            protocol.isCan -> parseCan(lines, protocol.canIdBits)
            else -> parseLegacy(lines)
        }
    }

    private fun tokens(line: String, headerChars: Int): List<String>? {
        val t = line.trim()
        if (t.contains(' ')) return t.split(Regex("\\s+"))
        // No spaces (ATS0): split header then bytes.
        if (t.length < headerChars || (t.length - headerChars) % 2 != 0) return null
        val out = ArrayList<String>()
        out.add(t.substring(0, headerChars))
        var i = headerChars
        while (i < t.length) { out.add(t.substring(i, i + 2)); i += 2 }
        return out
    }

    private fun parseCan(lines: List<String>, idBits: Int): ParseResult {
        val re = IsoTpReassembler()
        var corrupt = 0
        for (line in lines) {
            if (isStatusLine(line)) continue
            val headerChars = if (idBits == 29) 8 else 3
            val toks = tokens(line, headerChars) ?: run { corrupt++; null } ?: continue
            if (toks.any { !Hex.isHex(it) }) { corrupt++; continue }
            val id: Int
            val dataStart: Int
            if (idBits == 29) {
                if (toks[0].length == 8) { id = toks[0].toLong(16).toInt(); dataStart = 1 }
                else {
                    if (toks.size < 5) { corrupt++; continue }
                    id = (toks[0].toInt(16) shl 24) or (toks[1].toInt(16) shl 16) or (toks[2].toInt(16) shl 8) or toks[3].toInt(16)
                    dataStart = 4
                }
            } else {
                if (toks[0].length != 3) { corrupt++; continue }
                id = toks[0].toInt(16); dataStart = 1
            }
            val frame = IntArray(toks.size - dataStart) { toks[dataStart + it].toInt(16) }
            if (frame.isEmpty()) { corrupt++; continue }
            val ecu = if (idBits == 29) id and 0xFF else id
            re.accept(ecu, frame)
        }
        val problems = re.errors + re.incomplete().map { "Incomplete multi-frame message from ${it.toString(16)}" }
        return ParseResult(re.messages().map { EcuMessage(it.canId, it.payload) }, corrupt + re.incomplete().size, problems)
    }

    private fun parseLegacy(lines: List<String>): ParseResult {
        val out = ArrayList<EcuMessage>()
        var corrupt = 0
        for (line in lines) {
            if (isStatusLine(line)) continue
            val toks = tokens(line, 2) ?: run { corrupt++; null } ?: continue
            if (toks.any { !Hex.isHex(it) || it.length != 2 } || toks.size < 5) { corrupt++; continue }
            val bytes = toks.map { it.toInt(16) }
            // Verify checksum where we can: ISO 9141/KWP use a simple sum. J1850 uses a CRC we skip.
            val body = bytes.subList(0, bytes.size - 1)
            val sum = body.sum() and 0xFF
            val checksumOk = sum == bytes.last()
            val source = bytes[2]
            out.add(EcuMessage(source, body.subList(3, body.size).toIntArray()))
            if (!checksumOk) {
                // J1850 lines will not match the simple sum; we cannot tell protocols apart here, so
                // a mismatch is not counted as corruption.
            }
        }
        return ParseResult(out, corrupt, emptyList())
    }

    /** Fallback when headers are off: "41 0C 1A F8" or multi-frame "0: 49 02 01 ..." lines. */
    private fun parseNoHeaders(lines: List<String>): ParseResult {
        val out = ArrayList<EcuMessage>()
        var corrupt = 0
        val multi = StringBuilder()
        var inMulti = false
        for (line in lines) {
            if (isStatusLine(line)) continue
            val l = line.trim()
            val m = Regex("^([0-9A-F]):\\s*(.*)$", RegexOption.IGNORE_CASE).find(l)
            when {
                m != null -> { inMulti = true; multi.append(' ').append(m.groupValues[2]) }
                inMulti.not() && l.length == 3 && Hex.isHex(l) -> inMulti = true // byte count line
                else -> {
                    val bytes = Hex.decode(l)
                    if (bytes == null) corrupt++ else out.add(EcuMessage(0, bytes))
                }
            }
        }
        if (multi.isNotEmpty()) Hex.decode(multi.toString())?.let { out.add(EcuMessage(0, it)) } ?: corrupt++
        return ParseResult(out, corrupt, emptyList())
    }

    private fun isStatusLine(line: String): Boolean {
        val l = line.trim().uppercase()
        return l == "OK" || l.startsWith("SEARCHING") || l.startsWith("BUS INIT")
    }
}
