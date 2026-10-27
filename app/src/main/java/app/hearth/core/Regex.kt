package app.hearth.core

/** Where a regex script runs (SillyTavern numbering). */
object RegexPlacement {
    const val USER_INPUT = 1
    const val AI_OUTPUT = 2
    const val SLASH_COMMAND = 3
    const val WORLD_INFO = 5
    const val REASONING = 6
    val labels = linkedMapOf(
        USER_INPUT to "User input", AI_OUTPUT to "AI output", WORLD_INFO to "World info", REASONING to "Reasoning",
    )
}

/** What a regex pass is being applied for. */
enum class RegexTarget {
    /** Rewrites the saved message (scripts that are neither display- nor prompt-only). */
    STORE,
    /** Only changes what is shown ("Alter chat display" / markdownOnly). */
    DISPLAY,
    /** Only changes what is sent ("Alter outgoing prompt" / promptOnly). */
    PROMPT,
}

/** A SillyTavern regex script, field-compatible with ST's export format. */
data class RegexScript(
    val id: String,
    val name: String,
    val find: String,
    val replace: String = "",
    val trimStrings: List<String> = emptyList(),
    val placement: List<Int> = listOf(RegexPlacement.AI_OUTPUT),
    val disabled: Boolean = false,
    val markdownOnly: Boolean = false,
    val promptOnly: Boolean = false,
    val runOnEdit: Boolean = true,
    /** 0 = don't, 1 = substitute macros raw, 2 = substitute escaped. */
    val substituteMacros: Int = 0,
    val minDepth: Int? = null,
    val maxDepth: Int? = null,
) {
    fun toJson(): MJObj = jsonOf(
        "id" to id, "scriptName" to name, "findRegex" to find, "replaceString" to replace,
        "trimStrings" to trimStrings, "placement" to placement, "disabled" to disabled,
        "markdownOnly" to markdownOnly, "promptOnly" to promptOnly, "runOnEdit" to runOnEdit,
        "substituteRegex" to substituteMacros, "minDepth" to minDepth, "maxDepth" to maxDepth,
    )

    fun appliesTo(target: RegexTarget): Boolean = when (target) {
        RegexTarget.STORE -> !markdownOnly && !promptOnly
        RegexTarget.DISPLAY -> markdownOnly
        RegexTarget.PROMPT -> promptOnly
    }

    companion object {
        fun fromJson(o: JObj): RegexScript {
            val sub = when (val v = o["substituteRegex"]) {
                is Boolean -> if (v) 1 else 0
                is Number -> v.toInt()
                else -> 0
            }
            return RegexScript(
                id = o.str("id").ifEmpty { java.util.UUID.randomUUID().toString() },
                name = o.str("scriptName", "Regex"),
                find = o.str("findRegex"),
                replace = o.str("replaceString"),
                trimStrings = o.strList("trimStrings"),
                placement = o.list("placement").mapNotNull { (it as? Number)?.toInt() },
                disabled = o.bool("disabled", false),
                markdownOnly = o.bool("markdownOnly", false),
                promptOnly = o.bool("promptOnly", false),
                runOnEdit = o.bool("runOnEdit", true),
                substituteMacros = sub,
                minDepth = o.intOrNull("minDepth")?.takeIf { it >= 0 },
                maxDepth = o.intOrNull("maxDepth")?.takeIf { it >= 0 },
            )
        }

        /** Accepts one script, an array of scripts, or a preset/settings object that carries them. */
        fun parseAny(v: Any?): List<RegexScript> {
            @Suppress("UNCHECKED_CAST")
            return when (v) {
                is List<*> -> v.mapNotNull { (it as? JObj)?.let(::fromJson) }
                is Map<*, *> -> {
                    val o = v as JObj
                    when {
                        o.containsKey("findRegex") -> listOf(fromJson(o))
                        o.obj("extensions")?.list("regex_scripts")?.isNotEmpty() == true ->
                            parseAny(o.obj("extensions")!!["regex_scripts"])
                        o.obj("extension_settings")?.list("regex")?.isNotEmpty() == true ->
                            parseAny(o.obj("extension_settings")!!["regex"])
                        o["regex_scripts"] is List<*> -> parseAny(o["regex_scripts"])
                        else -> emptyList()
                    }
                }
                else -> emptyList()
            }
        }
    }
}

/** One text to run regex scripts on. [depth] is 0 for the newest message, null when not applicable. */
data class RegexItem(val text: String, val placement: Int, val depth: Int?)

/**
 * Runs a batch of texts through a fixed list of scripts. The app plugs in a JavaScript
 * implementation (exact SillyTavern semantics); [KotlinRegexBatch] is the fallback.
 */
fun interface RegexBatch {
    fun run(items: List<RegexItem>, target: RegexTarget, vars: Map<String, String>, variables: Map<String, String>): List<String>
}

class KotlinRegexBatch(private val scripts: List<RegexScript>) : RegexBatch {
    override fun run(items: List<RegexItem>, target: RegexTarget, vars: Map<String, String>, variables: Map<String, String>): List<String> {
        if (scripts.isEmpty()) return items.map { it.text }
        val ex = Macros(vars, variables = HashMap(variables))::expand
        return items.map { RegexEngine.apply(scripts, it.text, it.placement, target, it.depth, ex) }
    }
}

/** Runs regex scripts with JavaScript-style patterns and ST replacement rules (fallback engine). */
object RegexEngine {
    private class Compiled(val regex: Regex, val global: Boolean, val names: Map<String, String>)

    /** Last compile error per pattern, for showing in the regex editor. */
    private val errors = HashMap<String, String>()

    private val cache = object : LinkedHashMap<String, Compiled?>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Compiled?>?) = size > 256
    }

    /**
     * Applies every matching script, in list order.
     * @param depth message depth (0 = newest), or null when not applicable
     */
    fun apply(
        scripts: List<RegexScript>,
        text: String,
        placement: Int,
        target: RegexTarget,
        depth: Int? = null,
        expand: (String) -> String = { it },
        onlyRunOnEdit: Boolean = false,
    ): String {
        if (scripts.isEmpty() || text.isEmpty()) return text
        var t = text
        for (s in scripts) {
            if (s.disabled || placement !in s.placement || !s.appliesTo(target)) continue
            if (onlyRunOnEdit && !s.runOnEdit) continue
            if (depth != null) {
                if (s.minDepth != null && depth < s.minDepth) continue
                if (s.maxDepth != null && depth > s.maxDepth) continue
            }
            t = run(s, t, expand)
        }
        return t
    }

    /** True when any script needs the message depth (lets callers skip recomputation). */
    fun usesDepth(scripts: List<RegexScript>) = scripts.any { !it.disabled && (it.minDepth != null || it.maxDepth != null) }

    fun run(s: RegexScript, text: String, expand: (String) -> String = { it }): String {
        if (s.find.isBlank()) return text
        val findSrc = when (s.substituteMacros) {
            1 -> expand(s.find)
            2 -> escapeMacros(s.find, expand)
            else -> s.find
        }
        val c = compileInternal(findSrc) ?: return text
        val replacement = MATCH_MACRO.replace(s.replace) { "\$0" }
        val transform: (MatchResult) -> String = { m ->
            val withGroups = GROUP_REF.replace(replacement) { ref ->
                val num = ref.groupValues[1]
                val named = ref.groupValues[2]
                val raw = when {
                    num.isNotEmpty() -> m.groups[num.toInt()]?.value
                    named.isNotEmpty() -> runCatching { m.groups[c.names[named] ?: named]?.value }.getOrNull()
                    else -> null
                } ?: ""
                trim(raw, s.trimStrings, expand)
            }
            expand(withGroups)
        }
        return if (c.global) c.regex.replace(text, transform)
        else {
            val m = c.regex.find(text) ?: return text
            text.substring(0, m.range.first) + transform(m) + text.substring(m.range.last + 1)
        }
    }

    private fun trim(s: String, trims: List<String>, expand: (String) -> String): String {
        var out = s
        for (t in trims) { val e = expand(t); if (e.isNotEmpty()) out = out.replace(e, "") }
        return out
    }

    private fun escapeMacros(find: String, expand: (String) -> String): String =
        Regex("\\{\\{[^{}]*\\}\\}").replace(find) { m ->
            val v = expand(m.value)
            if (v == m.value) m.value else REGEX_SPECIAL.replace(v) { "\\" + it.value }
        }

    /** Parses "/pattern/flags" (or a bare pattern) into a Kotlin regex. */
    fun compile(src: String): Regex? = compileInternal(src)?.regex

    /** Why a pattern doesn't compile on this device, or null if it does. */
    fun compileError(src: String): String? {
        if (src.isBlank()) return null
        compileInternal(src)
        return synchronized(cache) { errors[src] }
    }

    private fun compileInternal(src: String): Compiled? = synchronized(cache) {
        if (cache.containsKey(src)) return cache[src]
        val c = runCatching {
            val m = SLASH_FORM.matchEntire(src)
            val (pattern, flags) = if (m != null) m.groupValues[1] to m.groupValues[2] else src to ""
            val opts = buildSet {
                if ('i' in flags) add(RegexOption.IGNORE_CASE)
                if ('m' in flags) add(RegexOption.MULTILINE)
                if ('s' in flags) add(RegexOption.DOT_MATCHES_ALL)
            }
            val conv = convert(pattern)
            Compiled(Regex(conv.pattern, opts), 'g' in flags, conv.names)
        }.onFailure { errors[src] = (it.message ?: it.javaClass.simpleName).lineSequence().first() }.getOrNull()
        cache[src] = c
        c
    }

    fun jsToJava(p: String): String = convert(p).pattern

    internal class Converted(val pattern: String, val names: Map<String, String>)

    /**
     * Translates JavaScript regex syntax into what Java / Android's ICU engine accepts:
     *  - literal braces that aren't quantifiers (e.g. /{{char}}/), JS's [^] and []
     *  - group names with characters ICU rejects (e.g. (?<char_name>…)), plus \k<…> and $<…>
     *  - unbounded quantifiers inside look-behind ((?<=\s*) → bounded)
     *  - \u{1F600} code point escapes, and ICU set operators (&&, --) inside classes
     */
    internal fun convert(p: String): Converted {
        val sb = StringBuilder(p.length + 16)
        val names = LinkedHashMap<String, String>()
        fun safe(n: String): String = names.getOrPut(n) {
            if (VALID_NAME.matches(n)) n else "hg" + names.size + n.filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }
        }
        val stack = ArrayList<Boolean>() // true = look-behind group
        var lookbehind = 0
        var inClass = false
        var i = 0
        while (i < p.length) {
            val c = p[i]
            if (c == '\\' && i + 1 < p.length) {
                val n = p[i + 1]
                if (n == 'k' && !inClass && i + 2 < p.length && p[i + 2] == '<') {
                    val end = p.indexOf('>', i + 3)
                    if (end > 0) { sb.append("\\k<").append(safe(p.substring(i + 3, end))).append('>'); i = end + 1; continue }
                }
                if (n == 'u' && i + 2 < p.length && p[i + 2] == '{') {
                    val end = p.indexOf('}', i + 3)
                    if (end > 0) { sb.append("\\x{").append(p, i + 3, end).append('}'); i = end + 1; continue }
                }
                sb.append(c).append(n); i += 2; continue
            }
            if (inClass) {
                when {
                    c == ']' -> { inClass = false; sb.append(c) }
                    c == '[' -> sb.append("\\[")
                    c == '&' && p.startsWith("&&", i) -> { sb.append("&\\&"); i += 2; continue }
                    c == '-' && p.startsWith("--", i) -> { sb.append("-\\-"); i += 2; continue }
                    else -> sb.append(c)
                }
                i++; continue
            }
            when {
                p.startsWith("[^]", i) -> { sb.append("[\\s\\S]"); i += 3; continue }
                p.startsWith("[]", i) -> { sb.append("(?!)"); i += 2; continue }
                c == '[' -> {
                    inClass = true; sb.append(c)
                    if (i + 1 < p.length && p[i + 1] == '^') { sb.append('^'); i++ }
                }
                c == '(' -> {
                    if (p.startsWith("(?<=", i) || p.startsWith("(?<!", i)) {
                        stack.add(true); lookbehind++; sb.append(p, i, i + 4); i += 4; continue
                    }
                    if (p.startsWith("(?<", i)) {
                        val end = p.indexOf('>', i + 3)
                        if (end > 0) { stack.add(false); sb.append("(?<").append(safe(p.substring(i + 3, end))).append('>'); i = end + 1; continue }
                    }
                    stack.add(false); sb.append(c)
                }
                c == ')' -> {
                    if (stack.isNotEmpty() && stack.removeAt(stack.lastIndex)) lookbehind--
                    sb.append(c)
                }
                lookbehind > 0 && c == '*' -> sb.append("{0,$LB_MAX}")
                lookbehind > 0 && c == '+' -> sb.append("{1,$LB_MAX}")
                c == '{' -> {
                    val close = p.indexOf('}', i)
                    val body = if (close > i) p.substring(i + 1, close) else ""
                    if (close > i && QUANT.matches(body) && i > 0) {
                        if (lookbehind > 0 && body.endsWith(",")) sb.append('{').append(body).append(LB_MAX).append('}')
                        else sb.append(p, i, close + 1)
                        i = close
                    } else sb.append("\\{")
                }
                c == '}' -> sb.append("\\}")
                else -> sb.append(c)
            }
            i++
        }
        return Converted(sb.toString(), names)
    }

    private const val LB_MAX = 255
    private val VALID_NAME = Regex("[A-Za-z][A-Za-z0-9]*")
    private val REGEX_SPECIAL = Regex("[\\\\^$.|?*+()\\[\\]{}/-]")
    private val SLASH_FORM = Regex("^/([\\s\\S]*)/([a-z]*)$")
    private val QUANT = Regex("\\d+(,\\d*)?")
    private val MATCH_MACRO = Regex("\\{\\{match\\}\\}", RegexOption.IGNORE_CASE)
    private val GROUP_REF = Regex("\\$(\\d+)|\\$<([^>]+)>")
}
