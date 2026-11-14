package app.obdforge.core.pid

/**
 * Mode 06 (on-board monitoring test results) for CAN vehicles, per SAE J1979:
 * response "46 MID TID UASID valueHi valueLo minHi minLo maxHi maxLo" repeated.
 * Scaling comes from the unit-and-scaling id (UASID). Ids we do not have a verified scaling
 * for are shown as raw counts rather than guessed.
 */
object Mode06 {
    data class TestResult(
        val mid: Int,
        val tid: Int,
        val uasId: Int,
        val value: Double,
        val min: Double,
        val max: Double,
        val unit: String,
        val scaled: Boolean,
    ) {
        val passed: Boolean get() = value >= min && value <= max
        /** Within 10 % of the band edge (or of a one-sided limit) = marginal. */
        val marginal: Boolean get() {
            if (!passed) return false
            val span = (max - min)
            if (span <= 0) return false
            val margin = span * 0.10
            return value - min < margin || max - value < margin
        }
        val status: String get() = if (!passed) "FAIL" else if (marginal) "MARGINAL" else "PASS"
        val monitorName: String get() = midName(mid)
    }

    fun midName(mid: Int): String = when (mid) {
        in 0x01..0x04 -> "O2 sensor monitor B1S${mid}"
        in 0x05..0x08 -> "O2 sensor monitor B2S${mid - 4}"
        in 0x09..0x0C -> "O2 sensor monitor B3S${mid - 8}"
        in 0x0D..0x10 -> "O2 sensor monitor B4S${mid - 12}"
        in 0x21..0x24 -> "Catalyst monitor bank ${mid - 0x20}"
        0x31 -> "EGR monitor bank 1"; 0x32 -> "EGR monitor bank 2"; 0x33 -> "EGR monitor bank 3"; 0x34 -> "EGR monitor bank 4"
        0x35 -> "VVT monitor bank 1"; 0x36 -> "VVT monitor bank 2"; 0x37 -> "VVT monitor bank 3"; 0x38 -> "VVT monitor bank 4"
        0x39 -> "EVAP monitor (cap off / 0.150\")"; 0x3A -> "EVAP monitor (0.090\")"; 0x3B -> "EVAP monitor (0.040\")"
        0x3C -> "EVAP monitor (0.020\")"; 0x3D -> "Purge flow monitor"
        in 0x41..0x44 -> "O2 sensor heater monitor B1S${mid - 0x40}"
        in 0x45..0x48 -> "O2 sensor heater monitor B2S${mid - 0x44}"
        in 0x61..0x64 -> "Heated catalyst monitor bank ${mid - 0x60}"
        in 0x71..0x74 -> "Secondary air monitor ${mid - 0x70}"
        in 0x81..0x84 -> "Fuel system monitor bank ${mid - 0x80}"
        in 0x85..0x88 -> "Boost pressure control monitor bank ${mid - 0x84}"
        0x90 -> "NOx adsorber monitor bank 1"; 0x91 -> "NOx adsorber monitor bank 2"
        0x98 -> "NOx catalyst monitor bank 1"; 0x99 -> "NOx catalyst monitor bank 2"
        0xA1 -> "Misfire monitor (general)"
        in 0xA2..0xAD -> "Misfire cylinder ${mid - 0xA1}"
        0xB0 -> "PM filter monitor bank 1"; 0xB1 -> "PM filter monitor bank 2"
        else -> "Monitor 0x" + mid.toString(16).uppercase().padStart(2, '0')
    }

    fun isMisfireCylinder(mid: Int) = mid in 0xA2..0xAD

    private class Scale(val unit: String, val signed: Boolean, val f: (Double) -> Double)

    // Unit and scaling IDs (SAE J1979) that this app decodes. Any other id is shown as raw
    // counts with its id, rather than with a guessed unit.
    private val scales: Map<Int, Scale> = mapOf(
        0x01 to Scale("counts", false) { it },
        0x02 to Scale("counts", false) { it * 0.1 },
        0x03 to Scale("counts", false) { it * 0.01 },
        0x04 to Scale("counts", false) { it * 0.001 },
        0x05 to Scale("counts", false) { it * 0.0000305 },
        0x06 to Scale("counts", false) { it * 0.000305 },
        0x07 to Scale("rpm", false) { it * 0.25 },
        0x08 to Scale("km/h", false) { it * 0.01 },
        0x09 to Scale("km/h", false) { it },
        0x0A to Scale("mV", false) { it * 0.122 },
        0x0B to Scale("V", false) { it * 0.001 },
        0x0C to Scale("V", false) { it * 0.01 },
        0x10 to Scale("ms", false) { it },
        0x14 to Scale("Ω", false) { it },
        0x16 to Scale("°C", false) { it * 0.1 - 40 },
        0x17 to Scale("kPa", false) { it * 0.01 },
        0x19 to Scale("kPa", false) { it * 0.079 },
        0x1A to Scale("kPa", false) { it },
        0x1B to Scale("kPa", false) { it * 10 },
        0x1E to Scale("λ", false) { it * 0.0000305 },
        0x20 to Scale("ratio", false) { it * 0.0039062 },
        0x24 to Scale("counts", false) { it },
        0x25 to Scale("km", false) { it },
        0x27 to Scale("g/s", false) { it * 0.01 },
        0x29 to Scale("Pa/s", false) { it * 0.25 },
        0x81 to Scale("counts", true) { it },
        0x82 to Scale("counts", true) { it * 0.1 },
        0x83 to Scale("counts", true) { it * 0.01 },
        0x84 to Scale("counts", true) { it * 0.001 },
        0x8A to Scale("mV", true) { it * 0.122 },
        0x8B to Scale("V", true) { it * 0.001 },
        0x8C to Scale("V", true) { it * 0.01 },
    )

    private fun raw16(hi: Int, lo: Int, signed: Boolean): Double {
        val v = hi * 256 + lo
        return (if (signed && v >= 32768) v - 65536 else v).toDouble()
    }

    /**
     * Decodes the bytes after the "46" service byte of one CAN Mode 06 response.
     * Each record is 9 bytes: MID, TID, UASID, value(2), min(2), max(2).
     */
    fun decodeCan(data: IntArray): List<TestResult> {
        if (data.isEmpty()) return emptyList()
        if (data[0] % 0x20 == 0 && data.size == 5) return emptyList() // support bitmap, not results
        val out = ArrayList<TestResult>()
        var i = 0
        while (i + 9 <= data.size) {
            val mid = data[i]; val tid = data[i + 1]; val uas = data[i + 2]
            val sc = scales[uas]
            val signed = sc?.signed ?: false
            val v = raw16(data[i + 3], data[i + 4], signed)
            val mn = raw16(data[i + 5], data[i + 6], signed)
            val mx = raw16(data[i + 7], data[i + 8], signed)
            out.add(
                if (sc != null) TestResult(mid, tid, uas, sc.f(v), sc.f(mn), sc.f(mx), sc.unit, true)
                else TestResult(mid, tid, uas, v, mn, mx, "raw (UASID 0x${uas.toString(16).uppercase()})", false)
            )
            i += 9
        }
        return out
    }

    /** Mode 05 (non-CAN oxygen sensor tests) test ids and scalings per SAE J1979. */
    fun mode05TestName(tid: Int): String = when (tid) {
        0x01 -> "Rich to lean sensor threshold voltage"
        0x02 -> "Lean to rich sensor threshold voltage"
        0x03 -> "Low sensor voltage for switch time calculation"
        0x04 -> "High sensor voltage for switch time calculation"
        0x05 -> "Rich to lean sensor switch time"
        0x06 -> "Lean to rich sensor switch time"
        0x07 -> "Minimum sensor voltage for test cycle"
        0x08 -> "Maximum sensor voltage for test cycle"
        0x09 -> "Time between sensor transitions"
        else -> "Manufacturer test 0x" + tid.toString(16).uppercase()
    }

    fun mode05Value(tid: Int, raw: Int): Pair<Double, String> = when (tid) {
        0x01, 0x02, 0x03, 0x04, 0x07, 0x08 -> raw * 0.005 to "V"
        0x05, 0x06 -> raw * 0.004 to "s"
        0x09 -> raw * 0.04 to "s"
        else -> raw.toDouble() to "raw"
    }
}
