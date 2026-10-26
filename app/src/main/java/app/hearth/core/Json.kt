package app.hearth.core

/**
 * Small, allocation-light JSON reader/writer.
 * Objects -> LinkedHashMap<String, Any?>, arrays -> ArrayList<Any?>,
 * integers -> Long, other numbers -> Double.
 * Keeping this in-house avoids a reflection/codegen serialization library,
 * which keeps the APK small and cold start fast.
 */
typealias JObj = Map<String, Any?>
typealias MJObj = LinkedHashMap<String, Any?>

class JsonException(msg: String) : RuntimeException(msg)

object Json {
    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipWs()
        val v = p.value()
        p.skipWs()
        if (p.i != text.length) p.err("Trailing characters")
        return v
    }

    fun parseObj(text: String): MJObj {
        @Suppress("UNCHECKED_CAST")
        return parse(text) as? MJObj ?: throw JsonException("Expected a JSON object")
    }

    fun stringify(v: Any?, pretty: Boolean = false): String {
        val sb = StringBuilder(256)
        write(sb, v, if (pretty) 0 else -1)
        return sb.toString()
    }

    private fun indent(sb: StringBuilder, level: Int) {
        sb.append('\n')
        repeat(level) { sb.append("  ") }
    }

    private fun write(sb: StringBuilder, v: Any?, level: Int) {
        when (v) {
            null -> sb.append("null")
            is String -> quote(sb, v)
            is Boolean -> sb.append(if (v) "true" else "false")
            is Int, is Long, is Short, is Byte -> sb.append(v.toString())
            is Float -> writeDouble(sb, v.toDouble())
            is Double -> writeDouble(sb, v)
            is Number -> writeDouble(sb, v.toDouble())
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, value) in v) {
                    if (!first) sb.append(',')
                    first = false
                    if (level >= 0) indent(sb, level + 1)
                    quote(sb, k.toString())
                    sb.append(if (level >= 0) ": " else ":")
                    write(sb, value, if (level >= 0) level + 1 else -1)
                }
                if (level >= 0 && !first) indent(sb, level)
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (item in v) {
                    if (!first) sb.append(',')
                    first = false
                    if (level >= 0) indent(sb, level + 1)
                    write(sb, item, if (level >= 0) level + 1 else -1)
                }
                if (level >= 0 && !first) indent(sb, level)
                sb.append(']')
            }
            is Array<*> -> write(sb, v.asList(), level)
            else -> quote(sb, v.toString())
        }
    }

    private fun writeDouble(sb: StringBuilder, d: Double) {
        when {
            d.isNaN() || d.isInfinite() -> sb.append("null")
            d == Math.rint(d) && kotlin.math.abs(d) < 1e15 -> sb.append(d.toLong().toString())
            else -> sb.append(d.toString())
        }
    }

    fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ' || c == ' ' || c == ' ') {
                    sb.append("\\u")
                    val h = Integer.toHexString(c.code)
                    repeat(4 - h.length) { sb.append('0') }
                    sb.append(h)
                } else sb.append(c)
            }
        }
        sb.append('"')
    }

    private class Parser(val s: String) {
        var i = 0

        fun err(msg: String): Nothing = throw JsonException("$msg at position $i")

        fun skipWs() {
            while (i < s.length) {
                val c = s[i]
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t' || c == '﻿') i++ else break
            }
        }

        fun value(): Any? {
            if (i >= s.length) err("Unexpected end")
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c in '0'..'9') num() else err("Unexpected '$c'")
            }
        }

        fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) err("Invalid literal")
            i += word.length
            return v
        }

        fun obj(): MJObj {
            i++ // {
            val m = MJObj()
            skipWs()
            if (i < s.length && s[i] == '}') { i++; return m }
            while (true) {
                skipWs()
                if (i >= s.length || s[i] != '"') err("Expected key")
                val k = str()
                skipWs()
                if (i >= s.length || s[i] != ':') err("Expected ':'")
                i++
                skipWs()
                m[k] = value()
                skipWs()
                if (i >= s.length) err("Unterminated object")
                when (s[i]) {
                    ',' -> i++
                    '}' -> { i++; return m }
                    else -> err("Expected ',' or '}'")
                }
            }
        }

        fun arr(): ArrayList<Any?> {
            i++ // [
            val a = ArrayList<Any?>()
            skipWs()
            if (i < s.length && s[i] == ']') { i++; return a }
            while (true) {
                skipWs()
                a.add(value())
                skipWs()
                if (i >= s.length) err("Unterminated array")
                when (s[i]) {
                    ',' -> i++
                    ']' -> { i++; return a }
                    else -> err("Expected ',' or ']'")
                }
            }
        }

        fun str(): String {
            i++ // opening quote
            val start = i
            // Fast path: no escapes
            while (i < s.length) {
                val c = s[i]
                if (c == '"') { val r = s.substring(start, i); i++; return r }
                if (c == '\\') break
                i++
            }
            val sb = StringBuilder(i - start + 16).append(s, start, i)
            while (i < s.length) {
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= s.length) err("Bad escape")
                        when (val e = s[i++]) {
                            '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000C'); 'n' -> sb.append('\n')
                            'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) err("Bad unicode escape")
                                sb.append(s.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> err("Bad escape '\\$e'")
                        }
                    }
                    else -> sb.append(c)
                }
            }
            err("Unterminated string")
        }

        fun num(): Any {
            val start = i
            if (s[i] == '-') i++
            var isFloat = false
            while (i < s.length) {
                val c = s[i]
                if (c in '0'..'9') i++
                else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') { isFloat = true; i++ }
                else break
            }
            val t = s.substring(start, i)
            return if (!isFloat) t.toLongOrNull() ?: t.toDouble() else t.toDoubleOrNull() ?: err("Bad number")
        }
    }
}

// ---- Typed accessors (lenient: accept numbers-as-strings and vice versa) ----

fun JObj.str(k: String, d: String = ""): String = when (val v = this[k]) {
    is String -> v
    is Number, is Boolean -> v.toString()
    else -> d
}

fun JObj.strOrNull(k: String): String? = this[k] as? String

fun JObj.num(k: String, d: Double): Double = when (val v = this[k]) {
    is Number -> v.toDouble()
    is String -> v.toDoubleOrNull() ?: d
    else -> d
}

fun JObj.int(k: String, d: Int): Int = when (val v = this[k]) {
    is Number -> v.toInt()
    is String -> v.toDoubleOrNull()?.toInt() ?: d
    else -> d
}

fun JObj.intOrNull(k: String): Int? = when (val v = this[k]) {
    is Number -> v.toInt()
    is String -> v.toDoubleOrNull()?.toInt()
    else -> null
}

fun JObj.bool(k: String, d: Boolean): Boolean = when (val v = this[k]) {
    is Boolean -> v
    is String -> v.equals("true", true)
    is Number -> v.toInt() != 0
    else -> d
}

fun JObj.boolOrNull(k: String): Boolean? = this[k] as? Boolean

@Suppress("UNCHECKED_CAST")
fun JObj.obj(k: String): JObj? = this[k] as? JObj

@Suppress("UNCHECKED_CAST")
fun JObj.list(k: String): List<Any?> = this[k] as? List<Any?> ?: emptyList()

fun JObj.strList(k: String): List<String> = when (val v = this[k]) {
    is List<*> -> v.mapNotNull { (it as? String) ?: (it as? Number)?.toString() }
    is String -> if (v.isBlank()) emptyList() else v.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    else -> emptyList()
}

@Suppress("UNCHECKED_CAST")
fun JObj.objList(k: String): List<JObj> = list(k).mapNotNull { it as? JObj }

fun jsonOf(vararg pairs: Pair<String, Any?>): MJObj {
    val m = MJObj()
    for ((k, v) in pairs) m[k] = v
    return m
}

/** Deep-copies a parsed JSON tree into fresh mutable containers. */
@Suppress("UNCHECKED_CAST")
fun deepCopy(v: Any?): Any? = when (v) {
    is Map<*, *> -> MJObj().also { m -> for ((k, x) in v) m[k.toString()] = deepCopy(x) }
    is List<*> -> ArrayList<Any?>(v.size).also { l -> for (x in v) l.add(deepCopy(x)) }
    else -> v
}
