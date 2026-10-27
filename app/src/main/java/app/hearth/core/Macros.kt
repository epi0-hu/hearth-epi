package app.hearth.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/**
 * SillyTavern-compatible macro engine, covering both the classic macros and the newer
 * syntax used by modular presets:
 *  - {{char}}, {{user}}, {{description}}, {{persona}}… (from [vars], case-insensitive)
 *  - {{setvar::name::value}}, {{getvar::name}}, {{addvar}}, {{incvar}}, {{decvar}}, {{hasvar}},
 *    {{flushvar}} (and the chatvar / globalvar spellings)
 *  - scoped blocks: {{setvar::name}}value{{/setvar}}, {{if cond}}…{{else}}…{{/if}}, with
 *    negation ({{if !cond}}) and macros nested inside conditions and arguments
 *  - {{// comments}}, {{trim}}, {{newline}}, {{noop}}, {{random::a::b}}, {{pick}}, {{roll:2d6}},
 *    {{date}}, {{time}}, {{weekday}}, {{isodate}}, {{isotime}}, <USER>/<BOT>
 * Nested macros are resolved inside-out, left to right, so {{setvar}} side effects are
 * visible to later {{getvar}} calls in the same text.
 */
class Macros(
    private val vars: Map<String, String>,
    private val random: Random = Random.Default,
    private val now: () -> Date = { Date() },
    /** Variables for {{setvar}} / {{getvar}}; shared across every expand() on this instance. */
    val variables: MutableMap<String, String> = HashMap(),
) {
    private val lowerVars: Map<String, String> = vars.mapKeys { it.key.lowercase(Locale.ROOT) }

    fun expand(text: String): String {
        if (text.isEmpty()) return text
        var t = text
        if (t.indexOf('<') >= 0) {
            t = t.replace("<USER>", lowerVars["user"] ?: "", ignoreCase = true)
                .replace("<BOT>", lowerVars["char"] ?: "", ignoreCase = true)
                .replace("<CHAR>", lowerVars["char"] ?: "", ignoreCase = true)
        }
        if (t.indexOf("{{") < 0) return t
        t = eval(t, 0, t.length)
        if (t.contains(TRIM_MARK)) t = TRIM.replace(t, "")
        return t
    }

    // ------------------------------------------------------------ parsing

    /** Index just past the "}}" that closes the macro opening at [start], or -1. */
    private fun closeOf(s: String, start: Int, end: Int): Int {
        var depth = 0
        var i = start
        while (i < end - 1) {
            if (s[i] == '{' && s[i + 1] == '{') { depth++; i += 2; continue }
            if (s[i] == '}' && s[i + 1] == '}') {
                depth--; i += 2
                if (depth == 0) return i
                continue
            }
            i++
        }
        return -1
    }

    private fun headOf(body: String): String {
        val b = body.trimStart()
        var i = 0
        while (i < b.length && b[i] != ':' && b[i] != ' ' && b[i] != '\n' && b[i] != '\t') i++
        return b.substring(0, i).lowercase(Locale.ROOT)
    }

    private fun isScopedOpen(head: String, body: String): Boolean = when {
        head == "if" -> true
        head in SCOPED_VAR -> body.split("::").size == 2
        else -> false
    }

    /**
     * Finds the "{{/head}}" closing a block whose opening tag ends at [from].
     * Returns (closeStart, closeEnd) or null when the block isn't closed.
     */
    private fun findBlockClose(s: String, from: Int, end: Int, head: String): Pair<Int, Int>? {
        var depth = 1
        var i = from
        while (i < end) {
            val o = s.indexOf("{{", i)
            if (o < 0 || o >= end) return null
            val c = closeOf(s, o, end)
            if (c < 0) return null
            val body = s.substring(o + 2, c - 2)
            val h = headOf(body)
            if (h == "/$head") {
                depth--
                if (depth == 0) return o to c
            } else if (h == head && isScopedOpen(h, body)) {
                depth++
            }
            i = c
        }
        return null
    }

    /** Splits block content at its top-level {{else}}. */
    private fun splitElse(s: String, start: Int, end: Int): Pair<IntRange, IntRange?> {
        var depth = 0
        var i = start
        while (i < end) {
            val o = s.indexOf("{{", i)
            if (o < 0 || o >= end) break
            val c = closeOf(s, o, end)
            if (c < 0) break
            val h = headOf(s.substring(o + 2, c - 2))
            when {
                h == "if" -> depth++
                h == "/if" -> depth--
                h == "else" && depth == 0 -> return (start until o) to (c until end)
            }
            i = c
        }
        return (start until end) to null
    }

    private fun eval(s: String, start: Int, end: Int): String {
        val sb = StringBuilder(end - start)
        var i = start
        while (i < end) {
            val o = s.indexOf("{{", i)
            if (o < 0 || o >= end) { sb.append(s, i, end); break }
            sb.append(s, i, o)
            val c = closeOf(s, o, end)
            if (c < 0) { sb.append(s, o, end); break }
            val body = s.substring(o + 2, c - 2)
            if (body.startsWith("//")) { i = c; continue } // comment
            val head = headOf(body)
            if (isScopedOpen(head, body)) {
                val close = findBlockClose(s, c, end, head)
                if (close != null) {
                    sb.append(block(s, head, body, c, close.first))
                    i = close.second
                    continue
                }
            }
            // Inline macro: resolve nested macros in the body first.
            val inner = if (body.indexOf("{{") >= 0) eval(body, 0, body.length) else body
            val r = resolve(inner)
            sb.append(r ?: s.substring(o, c))
            i = c
        }
        return sb.toString()
    }

    private fun block(s: String, head: String, openBody: String, contentStart: Int, contentEnd: Int): String {
        if (head == "if") {
            val condSrc = openBody.trimStart().substring(2)
            var cond = eval(condSrc, 0, condSrc.length).trim()
            var negate = false
            while (cond.startsWith("!")) { negate = !negate; cond = cond.substring(1).trim() }
            val truthy = truthy(cond) != negate
            val (yes, no) = splitElse(s, contentStart, contentEnd)
            val branch = if (truthy) yes else no ?: return ""
            return scoped(eval(s, branch.first, branch.last + 1))
        }
        // Scoped variable macro: content becomes the value.
        val name = openBody.split("::").getOrElse(1) { "" }.let { eval(it, 0, it.length) }.trim()
        val value = scoped(eval(s, contentStart, contentEnd))
        return resolve("$head::$name::$value") ?: ""
    }

    private fun truthy(v: String): Boolean {
        val t = v.trim()
        if (t.isEmpty()) return false
        return t.lowercase(Locale.ROOT) !in FALSY
    }

    /** Scoped content is trimmed and de-indented, like SillyTavern does. */
    private fun scoped(v: String): String {
        val lines = v.trim('\n', '\r').lines()
        val indent = lines.filter { it.isNotBlank() }.minOfOrNull { l -> l.length - l.trimStart(' ', '\t').length } ?: 0
        return lines.joinToString("\n") { if (it.length >= indent) it.substring(indent) else it.trimStart() }.trim()
    }

    // ------------------------------------------------------------ resolving

    private fun resolve(bodyRaw: String): String? {
        val body = bodyRaw.trim()
        val lower = body.lowercase(Locale.ROOT)
        lowerVars[lower]?.let { return it }
        variableMacro(body, lower)?.let { return it }
        return when {
            lower == "newline" -> "\n"
            lower == "noop" -> ""
            lower == "trim" -> TRIM_MARK
            lower == "date" -> SimpleDateFormat("MMMM d, yyyy", Locale.US).format(now())
            lower == "time" -> SimpleDateFormat("h:mm a", Locale.US).format(now())
            lower == "weekday" -> SimpleDateFormat("EEEE", Locale.US).format(now())
            lower == "isodate" -> SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now())
            lower == "isotime" -> SimpleDateFormat("HH:mm", Locale.US).format(now())
            lower.startsWith("random::") || lower.startsWith("pick::") -> {
                val opts = body.substringAfter("::").split("::")
                opts[random.nextInt(opts.size)]
            }
            lower.startsWith("random:") || lower.startsWith("pick:") -> {
                val opts = body.substringAfter(":").split(',').map { it.trim() }
                opts[random.nextInt(opts.size)]
            }
            lower.startsWith("random ") -> {
                val opts = body.substringAfter(" ").split(',').map { it.trim() }
                opts[random.nextInt(opts.size)]
            }
            lower.startsWith("roll:") || lower.startsWith("roll ") || lower.startsWith("roll::") ->
                roll(lower.removePrefix("roll").trimStart(':', ' ').trim())
            else -> null
        }
    }

    private fun variableMacro(body: String, lower: String): String? {
        if (!lower.contains("::")) return null
        val cmd = lower.substringBefore("::").trim()
        val op = VAR_OPS[cmd] ?: return null
        val args = body.split("::").drop(1)
        val name = args.getOrNull(0)?.trim() ?: return null
        val value = args.drop(1).joinToString("::")
        return when (op) {
            "set" -> { variables[name] = value; "" }
            "get" -> variables[name] ?: ""
            "has" -> if (variables.containsKey(name)) "true" else "false"
            "flush" -> { variables.remove(name); "" }
            "add" -> {
                val cur = variables[name] ?: ""
                val a = cur.toDoubleOrNull()
                val b = value.trim().toDoubleOrNull()
                variables[name] = if (a != null && b != null) fmtNum(a + b) else if (cur.isEmpty() && b != null) fmtNum(b) else cur + value
                ""
            }
            "inc" -> fmtNum((variables[name]?.toDoubleOrNull() ?: 0.0) + 1).also { variables[name] = it }
            "dec" -> fmtNum((variables[name]?.toDoubleOrNull() ?: 0.0) - 1).also { variables[name] = it }
            else -> null
        }
    }

    private fun fmtNum(d: Double) = if (d == Math.rint(d)) d.toLong().toString() else d.toString()

    private fun roll(spec: String): String? {
        val m = DICE.matchEntire(spec) ?: return null
        val count = m.groupValues[1].ifEmpty { "1" }.toInt().coerceIn(1, 100)
        val sides = m.groupValues[2].toInt().coerceAtLeast(1)
        val mod = m.groupValues[3].replace(" ", "").ifEmpty { "0" }.toInt()
        var sum = mod
        repeat(count) { sum += random.nextInt(sides) + 1 }
        return sum.toString()
    }

    companion object {
        private const val TRIM_MARK = "\u0000trim\u0000"
        // {{trim}} removes the line breaks around it (SillyTavern semantics).
        private val TRIM = Regex("(?:\\r?\\n)*\u0000trim\u0000(?:\\r?\\n)*")
        private val DICE = Regex("(\\d*)d(\\d+)\\s*([+-]\\s*\\d+)?")
        private val FALSY = setOf("false", "0", "off", "no", "null", "undefined", "nan")
        private val VAR_OPS: Map<String, String> = buildMap {
            for (scope in listOf("", "chat", "global")) {
                put("set${scope}var", "set"); put("get${scope}var", "get"); put("add${scope}var", "add")
                put("inc${scope}var", "inc"); put("dec${scope}var", "dec"); put("has${scope}var", "has")
                put("flush${scope}var", "flush"); put("delete${scope}var", "flush")
            }
        }
        private val SCOPED_VAR = VAR_OPS.filterValues { it == "set" || it == "add" }.keys
    }
}

/** Fast token estimate. Good enough for context budgeting without shipping a tokenizer. */
class TokenCounter(private val charsPerToken: Double = 3.5) {
    fun count(s: String): Int = if (s.isEmpty()) 0 else (s.length / charsPerToken).toInt() + 1
    fun count(m: PromptMessage): Int = count(m.content) + 4
}
