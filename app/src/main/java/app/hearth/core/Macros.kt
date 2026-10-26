package app.hearth.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/** SillyTavern-style macro expansion: {{char}}, {{user}}, {{random::a::b}}, {{roll:2d6}}, etc. */
class Macros(
    private val vars: Map<String, String>,
    private val random: Random = Random.Default,
    private val now: () -> Date = { Date() },
) {
    fun expand(text: String): String {
        if (text.isEmpty()) return text
        var t = text
        if (t.indexOf('<') >= 0) {
            t = t.replace("<USER>", vars["user"] ?: "", ignoreCase = true)
                .replace("<BOT>", vars["char"] ?: "", ignoreCase = true)
                .replace("<CHAR>", vars["char"] ?: "", ignoreCase = true)
        }
        if (t.indexOf("{{") < 0) return t
        t = COMMENT.replace(t, "")
        // Nested macros resolve inside-out over a few passes.
        repeat(4) {
            if (t.indexOf("{{") < 0) return@repeat
            val next = MACRO.replace(t) { m -> resolve(m.groupValues[1]) ?: m.value }
            if (next == t) return@repeat
            t = next
        }
        if (t.contains("{{trim}}", true)) t = TRIM.replace(t, "")
        return t
    }

    private fun resolve(bodyRaw: String): String? {
        val body = bodyRaw.trim()
        val lower = body.lowercase(Locale.ROOT)
        vars[lower]?.let { return it }
        return when {
            lower == "newline" -> "\n"
            lower == "noop" -> ""
            lower == "trim" -> "{{trim}}"
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
            lower.startsWith("roll:") || lower.startsWith("roll ") -> roll(lower.substring(5).trim())
            else -> null
        }
    }

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
        private val MACRO = Regex("\\{\\{([^{}]*?)\\}\\}")
        private val COMMENT = Regex("\\{\\{//[\\s\\S]*?\\}\\}")
        private val TRIM = Regex("\\s*\\{\\{trim\\}\\}\\s*", RegexOption.IGNORE_CASE)
        private val DICE = Regex("(\\d*)d(\\d+)\\s*([+-]\\s*\\d+)?")
    }
}

/** Fast token estimate. Good enough for context budgeting without shipping a tokenizer. */
class TokenCounter(private val charsPerToken: Double = 3.5) {
    fun count(s: String): Int = if (s.isEmpty()) 0 else (s.length / charsPerToken).toInt() + 1
    fun count(m: PromptMessage): Int = count(m.content) + 4
}
