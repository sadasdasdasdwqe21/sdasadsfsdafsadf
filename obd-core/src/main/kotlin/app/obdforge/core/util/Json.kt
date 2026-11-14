package app.obdforge.core.util

/**
 * Dependency-free JSON reader/writer. The core module has no third-party libraries so it
 * runs anywhere; this is enough for exports, backups and manufacturer profile files.
 */
object Json {
    fun parse(text: String): Any? = Parser(text).run {
        val v = readValue()
        skipWs()
        if (pos != text.length) throw IllegalArgumentException("Unexpected trailing data at $pos")
        v
    }

    fun write(value: Any?, pretty: Boolean = false): String {
        val sb = StringBuilder()
        writeValue(sb, value, if (pretty) 0 else -1)
        return sb.toString()
    }

    fun quote(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    private fun indent(sb: StringBuilder, level: Int) {
        if (level < 0) return
        sb.append('\n')
        repeat(level) { sb.append("  ") }
    }

    private fun writeValue(sb: StringBuilder, v: Any?, level: Int) {
        val next = if (level < 0) -1 else level + 1
        when (v) {
            null -> sb.append("null")
            is String -> sb.append(quote(v))
            is Boolean -> sb.append(v.toString())
            is Double -> sb.append(if (v.isNaN() || v.isInfinite()) "null" else trimNum(v))
            is Float -> sb.append(if (v.isNaN() || v.isInfinite()) "null" else trimNum(v.toDouble()))
            is Number -> sb.append(v.toString())
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, value) in v) {
                    if (!first) sb.append(',')
                    first = false
                    indent(sb, next)
                    sb.append(quote(k.toString())).append(':')
                    if (level >= 0) sb.append(' ')
                    writeValue(sb, value, next)
                }
                if (!first) indent(sb, level)
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (item in v) {
                    if (!first) sb.append(',')
                    first = false
                    indent(sb, next)
                    writeValue(sb, item, next)
                }
                if (!first) indent(sb, level)
                sb.append(']')
            }
            is Array<*> -> writeValue(sb, v.toList(), level)
            is DoubleArray -> writeValue(sb, v.toList(), level)
            is IntArray -> writeValue(sb, v.toList(), level)
            else -> sb.append(quote(v.toString()))
        }
    }

    private fun trimNum(d: Double): String {
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return d.toLong().toString()
        return d.toString()
    }

    private class Parser(val s: String) {
        var pos = 0
        fun skipWs() { while (pos < s.length && s[pos].isWhitespace()) pos++ }
        fun readValue(): Any? {
            skipWs()
            if (pos >= s.length) throw IllegalArgumentException("Unexpected end of JSON")
            return when (val c = s[pos]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) readNumber() else throw IllegalArgumentException("Bad JSON at $pos")
            }
        }
        fun literal(word: String, v: Any?): Any? {
            if (!s.startsWith(word, pos)) throw IllegalArgumentException("Bad literal at $pos")
            pos += word.length
            return v
        }
        fun readNumber(): Any {
            val start = pos
            if (s[pos] == '-') pos++
            while (pos < s.length && (s[pos].isDigit() || s[pos] in ".eE+-")) pos++
            val t = s.substring(start, pos)
            return if (t.any { it == '.' || it == 'e' || it == 'E' }) t.toDouble() else t.toLongOrNull() ?: t.toDouble()
        }
        fun readString(): String {
            pos++ // opening quote
            val sb = StringBuilder()
            while (pos < s.length) {
                val c = s[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        val e = s[pos++]
                        when (e) {
                            'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000C')
                            'u' -> { sb.append(s.substring(pos, pos + 4).toInt(16).toChar()); pos += 4 }
                            else -> sb.append(e)
                        }
                    }
                    else -> sb.append(c)
                }
            }
            throw IllegalArgumentException("Unterminated string")
        }
        fun readArray(): List<Any?> {
            pos++
            val out = ArrayList<Any?>()
            skipWs()
            if (s[pos] == ']') { pos++; return out }
            while (true) {
                out.add(readValue())
                skipWs()
                when (s[pos++]) {
                    ',' -> continue
                    ']' -> return out
                    else -> throw IllegalArgumentException("Expected , or ] at ${pos - 1}")
                }
            }
        }
        fun readObject(): Map<String, Any?> {
            pos++
            val out = LinkedHashMap<String, Any?>()
            skipWs()
            if (s[pos] == '}') { pos++; return out }
            while (true) {
                skipWs()
                val k = readString()
                skipWs()
                if (s[pos++] != ':') throw IllegalArgumentException("Expected : at ${pos - 1}")
                out[k] = readValue()
                skipWs()
                when (s[pos++]) {
                    ',' -> continue
                    '}' -> return out
                    else -> throw IllegalArgumentException("Expected , or } at ${pos - 1}")
                }
            }
        }
    }
}

@Suppress("UNCHECKED_CAST")
fun Any?.asMap(): Map<String, Any?> = this as? Map<String, Any?> ?: emptyMap()
fun Any?.asList(): List<Any?> = this as? List<Any?> ?: emptyList()
fun Any?.asDouble(): Double? = (this as? Number)?.toDouble() ?: (this as? String)?.toDoubleOrNull()
fun Any?.asStr(): String? = this as? String
