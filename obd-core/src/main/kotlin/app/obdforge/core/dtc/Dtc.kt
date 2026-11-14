package app.obdforge.core.dtc

enum class DtcKind(val label: String, val mode: Int) {
    STORED("Stored (confirmed)", 0x03),
    PENDING("Pending", 0x07),
    PERMANENT("Permanent", 0x0A),
    ENHANCED("Manufacturer (UDS)", 0x19),
}

enum class DtcSystem(val letter: Char, val label: String) {
    POWERTRAIN('P', "Powertrain"), CHASSIS('C', "Chassis"), BODY('B', "Body"), NETWORK('U', "Network");
    companion object { fun of(code: String) = entries.firstOrNull { it.letter == code.firstOrNull() } ?: POWERTRAIN }
}

data class Dtc(
    val code: String,
    val kind: DtcKind,
    val ecu: Int,
    /** UDS status byte for enhanced codes (ISO 14229), null for OBD-II modes. */
    val udsStatus: Int? = null,
) {
    val system: DtcSystem get() = DtcSystem.of(code)
    /** Second character 0/2 = SAE generic, 1/3 = manufacturer-defined (for P codes, P3 is split). */
    val isGeneric: Boolean get() = code.length == 5 && (code[1] == '0' || code[1] == '2' || (code[0] == 'P' && code[1] == '3' && code[2] in "4567"))
    val isManufacturerSpecific: Boolean get() = !isGeneric

    /** Powertrain sub-system from the third character (SAE J2012). */
    val subsystem: String get() = if (code[0] != 'P') system.label else when (code[2]) {
        '0' -> "Fuel/air metering & auxiliary emission controls"
        '1', '2' -> "Fuel and air metering"
        '3' -> "Ignition system or misfire"
        '4' -> "Auxiliary emission controls"
        '5' -> "Vehicle speed, idle control & auxiliary inputs"
        '6' -> "Computer & output circuits"
        '7', '8', '9' -> "Transmission"
        'A', 'B', 'C' -> "Hybrid propulsion"
        else -> "Powertrain"
    }

    companion object {
        /** Decodes two DTC bytes into "P0301" form; returns null for the 0000 filler. */
        fun decode(b1: Int, b2: Int): String? {
            if (b1 == 0 && b2 == 0) return null
            val letter = "PCBU"[(b1 shr 6) and 0x03]
            val d1 = (b1 shr 4) and 0x03
            val d2 = b1 and 0x0F
            val d3 = (b2 shr 4) and 0x0F
            val d4 = b2 and 0x0F
            return "$letter$d1${d2.toString(16).uppercase()}${d3.toString(16).uppercase()}${d4.toString(16).uppercase()}"
        }

        /** Encodes "P0301" back to two bytes (used by the simulator and tests). */
        fun encode(code: String): IntArray {
            require(code.length == 5) { "Bad DTC $code" }
            val letter = "PCBU".indexOf(code[0].uppercaseChar())
            require(letter >= 0) { "Bad DTC letter in $code" }
            val d1 = code[1].digitToInt(16); val d2 = code[2].digitToInt(16)
            val d3 = code[3].digitToInt(16); val d4 = code[4].digitToInt(16)
            return intArrayOf((letter shl 6) or (d1 shl 4) or d2, (d3 shl 4) or d4)
        }

        /** UDS DTC status bits (ISO 14229-1). */
        fun udsStatusText(s: Int): List<String> {
            val names = listOf("test failed", "failed this operation cycle", "pending", "confirmed",
                "not completed since clear", "failed since clear", "not completed this cycle", "warning indicator requested")
            return names.filterIndexed { i, _ -> s and (1 shl i) != 0 }
        }
    }
}
