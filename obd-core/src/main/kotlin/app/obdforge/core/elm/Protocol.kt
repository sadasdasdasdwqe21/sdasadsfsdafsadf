package app.obdforge.core.elm

/** OBD-II bus protocols, numbered the way the ELM327 ATSP / ATDPN commands number them. */
enum class ObdProtocol(
    val elmCode: Char,
    val label: String,
    val isCan: Boolean,
    val canIdBits: Int = 0,
    val busKbps: Double = 0.0,
) {
    AUTO('0', "Automatic", false),
    J1850_PWM('1', "SAE J1850 PWM (41.6 kbaud)", false, busKbps = 41.6),
    J1850_VPW('2', "SAE J1850 VPW (10.4 kbaud)", false, busKbps = 10.4),
    ISO_9141_2('3', "ISO 9141-2 (5 baud init)", false, busKbps = 10.4),
    KWP2000_5BAUD('4', "ISO 14230-4 KWP (5 baud init)", false, busKbps = 10.4),
    KWP2000_FAST('5', "ISO 14230-4 KWP (fast init)", false, busKbps = 10.4),
    CAN_11_500('6', "ISO 15765-4 CAN (11 bit, 500 kbaud)", true, 11, 500.0),
    CAN_29_500('7', "ISO 15765-4 CAN (29 bit, 500 kbaud)", true, 29, 500.0),
    CAN_11_250('8', "ISO 15765-4 CAN (11 bit, 250 kbaud)", true, 11, 250.0),
    CAN_29_250('9', "ISO 15765-4 CAN (29 bit, 250 kbaud)", true, 29, 250.0),
    SAE_J1939('A', "SAE J1939 CAN (29 bit, 250 kbaud)", true, 29, 250.0),
    USER1('B', "User CAN 1", true, 11, 125.0),
    USER2('C', "User CAN 2", true, 11, 50.0);

    val isLegacy: Boolean get() = !isCan && this != AUTO

    companion object {
        /** Parses ATDPN output such as "6", "A6" (auto, currently 6) or "A". */
        fun fromDpn(text: String): ObdProtocol? {
            val t = text.trim().uppercase()
            if (t.isEmpty()) return null
            val code = if (t.length >= 2 && t[0] == 'A') t[1] else t[0]
            return entries.firstOrNull { it.elmCode == code }
        }

        /** Protocols a scan tool should try, most common first, when auto-detection fails. */
        val searchOrder = listOf(
            CAN_11_500, CAN_29_500, CAN_11_250, CAN_29_250,
            KWP2000_FAST, KWP2000_5BAUD, ISO_9141_2, J1850_VPW, J1850_PWM,
        )
    }
}
