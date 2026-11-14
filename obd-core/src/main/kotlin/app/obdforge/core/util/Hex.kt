package app.obdforge.core.util

/** Small helpers for the hex text that ELM327 adapters speak. */
object Hex {
    private val DIGITS = "0123456789ABCDEF".toCharArray()

    fun byte(b: Int): String {
        val v = b and 0xFF
        return "" + DIGITS[v shr 4] + DIGITS[v and 0x0F]
    }

    fun encode(bytes: ByteArray, separator: String = " "): String =
        bytes.joinToString(separator) { byte(it.toInt()) }

    fun encode(bytes: List<Int>, separator: String = " "): String =
        bytes.joinToString(separator) { byte(it) }

    /** Parses "41 0C 1A F8" or "410C1AF8" into bytes. Returns null if the text is not clean hex. */
    fun decode(text: String): IntArray? {
        val clean = text.filter { !it.isWhitespace() }
        if (clean.isEmpty() || clean.length % 2 != 0) return null
        val out = IntArray(clean.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(clean[i * 2], 16)
            val lo = Character.digit(clean[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i] = (hi shl 4) or lo
        }
        return out
    }

    fun isHex(token: String): Boolean = token.isNotEmpty() && token.all { Character.digit(it, 16) >= 0 }
}

fun Int.hex2(): String = Hex.byte(this)
fun Int.hex3(): String = String.format("%03X", this)
fun Int.hex4(): String = String.format("%04X", this and 0xFFFF)
