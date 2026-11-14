package app.obdforge.core.mfr

/**
 * Formula evaluator for user/profile-defined PIDs, Torque-style: A, B, C, D... are response data
 * bytes; supports + - * / ^, parentheses, unary minus, and functions SIGNED(x) (8-bit),
 * SIGNED16(x), BIT(x,n), MIN, MAX, ABS. Example: "((A*256)+B)/4".
 */
class Expression(val source: String) {
    private sealed class Node {
        data class Num(val v: Double) : Node()
        data class Byte(val idx: Int) : Node()
        data class Bin(val op: Char, val a: Node, val b: Node) : Node()
        data class Neg(val a: Node) : Node()
        data class Fn(val name: String, val args: List<Node>) : Node()
    }

    private val root: Node
    val maxByteIndex: Int

    init {
        val p = P(source.uppercase().replace(" ", ""))
        root = p.expr()
        if (p.i != p.s.length) throw IllegalArgumentException("Unexpected '${p.s[p.i]}' at ${p.i} in formula")
        maxByteIndex = p.maxByte
    }

    fun eval(data: IntArray): Double = ev(root, data)

    private fun ev(n: Node, d: IntArray): Double = when (n) {
        is Node.Num -> n.v
        is Node.Byte -> d.getOrElse(n.idx) { throw IndexOutOfBoundsException("Byte ${('A' + n.idx)} missing") }.toDouble()
        is Node.Neg -> -ev(n.a, d)
        is Node.Bin -> { val a = ev(n.a, d); val b = ev(n.b, d)
            when (n.op) { '+' -> a + b; '-' -> a - b; '*' -> a * b; '/' -> a / b; '^' -> Math.pow(a, b); else -> Double.NaN } }
        is Node.Fn -> { val a = n.args.map { ev(it, d) }
            when (n.name) {
                "SIGNED" -> { val x = a[0].toInt() and 0xFF; (if (x >= 128) x - 256 else x).toDouble() }
                "SIGNED16" -> { val x = a[0].toInt() and 0xFFFF; (if (x >= 32768) x - 65536 else x).toDouble() }
                "BIT" -> ((a[0].toInt() shr a[1].toInt()) and 1).toDouble()
                "MIN" -> a.min(); "MAX" -> a.max(); "ABS" -> Math.abs(a[0])
                else -> throw IllegalArgumentException("Unknown function ${n.name}")
            } }
    }

    private class P(val s: String) {
        var i = 0
        var maxByte = -1
        fun expr(): Node {
            var l = term()
            while (i < s.length && (s[i] == '+' || s[i] == '-')) { val op = s[i++]; l = Node.Bin(op, l, term()) }
            return l
        }
        fun term(): Node {
            var l = power()
            while (i < s.length && (s[i] == '*' || s[i] == '/')) { val op = s[i++]; l = Node.Bin(op, l, power()) }
            return l
        }
        fun power(): Node {
            val b = unary()
            if (i < s.length && s[i] == '^') { i++; return Node.Bin('^', b, power()) }
            return b
        }
        fun unary(): Node = if (i < s.length && s[i] == '-') { i++; Node.Neg(unary()) } else atom()
        fun atom(): Node {
            if (i >= s.length) throw IllegalArgumentException("Formula ends unexpectedly")
            val c = s[i]
            if (c == '(') { i++; val e = expr(); expect(')'); return e }
            if (c.isDigit() || c == '.') {
                val st = i
                if (s.startsWith("0X", i)) { i += 2; while (i < s.length && s[i].isLetterOrDigit()) i++; return Node.Num(s.substring(st + 2, i).toLong(16).toDouble()) }
                while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
                return Node.Num(s.substring(st, i).toDouble())
            }
            if (c.isLetter()) {
                val st = i
                while (i < s.length && (s[i].isLetterOrDigit())) i++
                val name = s.substring(st, i)
                if (i < s.length && s[i] == '(') {
                    i++
                    val args = ArrayList<Node>()
                    if (s[i] != ')') { args.add(expr()); while (s[i] == ',') { i++; args.add(expr()) } }
                    expect(')')
                    return Node.Fn(name, args)
                }
                if (name.length == 1) { val idx = name[0] - 'A'; maxByte = maxOf(maxByte, idx); return Node.Byte(idx) }
                if (name.length == 2 && name[0] == name[1]) { val idx = 26 + (name[0] - 'A'); maxByte = maxOf(maxByte, idx); return Node.Byte(idx) }
                throw IllegalArgumentException("Unknown name $name")
            }
            throw IllegalArgumentException("Unexpected '$c' at $i")
        }
        fun expect(c: Char) { if (i >= s.length || s[i] != c) throw IllegalArgumentException("Expected '$c' at $i"); i++ }
    }
}
