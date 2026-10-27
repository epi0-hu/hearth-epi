package app.hearth.core

import java.util.Date
import kotlin.random.Random

data class PromptMessage(val role: Role, val content: String)

enum class GenMode { NORMAL, SWIPE, CONTINUE, IMPERSONATE }

/** Extra context when the chat is a group: everyone in it, and whether to merge their cards. */
data class GroupContext(val members: List<Character>, val joinCards: Boolean = false)

data class PromptInput(
    /** The character who will speak next. */
    val char: Character,
    val persona: Persona,
    val preset: Preset,
    val kind: ApiKind,
    /** Chat history to draw from, oldest first. For CONTINUE the last item is the partial reply. */
    val history: List<ChatMessage>,
    val books: List<Lorebook> = emptyList(),
    val wi: WiSettings = WiSettings(),
    val mode: GenMode = GenMode.NORMAL,
    val tokens: TokenCounter = TokenCounter(),
    val random: Random = Random.Default,
    val now: () -> Date = { Date() },
    val regex: List<RegexScript> = emptyList(),
    val group: GroupContext? = null,
    val model: String = "",
    /** Chat variables ({{setvar}}/{{getvar}}), carried between generations like SillyTavern's chat metadata. */
    val variables: Map<String, String> = emptyMap(),
    /** Engine that runs [regex]; defaults to the built-in Kotlin one. */
    val regexBatch: RegexBatch? = null,
)

data class BuiltPrompt(
    /** Chat-style messages (chat completion / Anthropic). */
    val messages: List<PromptMessage>,
    /** Fully rendered prompt (text completion only). */
    val text: String?,
    val stop: List<String>,
    /** Assistant prefill (Anthropic, or partial text being continued). */
    val prefill: String,
    val tokenEstimate: Int,
    val historyIncluded: Int,
    val activatedLore: List<String>,
    /** Chat variables after this prompt's macros ran. */
    val variables: Map<String, String> = emptyMap(),
)

/**
 * Assembles the prompt from the preset's ordered prompt modules (SillyTavern prompt
 * manager semantics). Markers are filled from the character, persona, world info and
 * chat; modules set to "in-chat" are injected into history at their depth. Oldest
 * history is trimmed first to fit the context budget.
 */
object PromptBuilder {

    private class Part(val role: Role, val content: String, val isExamples: Boolean = false)

    fun build(inp: PromptInput): BuiltPrompt {
        val c = inp.char
        val p = inp.preset
        val tc = inp.tokens
        val userName = inp.persona.name
        val members = inp.group?.members ?: listOf(c)
        val memberNames = members.joinToString(", ") { it.name }
        val lastUser = inp.history.lastOrNull { it.isUser }?.text ?: ""
        val lastChar = inp.history.lastOrNull { !it.isUser && !it.isSystem }?.text ?: ""

        // Card fields may themselves contain {{char}}/{{user}}; resolve those first, like SillyTavern.
        val simple = Macros(mapOf("user" to userName, "char" to c.name), inp.random, inp.now)
        val examplesRaw = simple.expand(c.mesExample)
        val baseVars = mapOf(
            "user" to userName,
            "char" to c.name,
            "description" to simple.expand(c.description),
            "personality" to simple.expand(c.personality),
            "scenario" to simple.expand(c.scenario),
            "persona" to simple.expand(inp.persona.description),
            "charprompt" to simple.expand(c.systemPrompt),
            "charjailbreak" to simple.expand(c.postHistoryInstructions),
            "mesexamples" to examplesRaw.split(START_RX).map { it.trim() }.filter { it.isNotEmpty() }
                .joinToString("\n") { "<START>\n$it" },
            "mesexamplesraw" to examplesRaw,
            "model" to inp.model,
            "group" to memberNames,
            "charifnotgroup" to memberNames,
            "groupnotmuted" to memberNames,
            "notchar" to (members.filter { it.id != c.id }.map { it.name } + userName).joinToString(", "),
            "lastmessage" to (inp.history.lastOrNull()?.text ?: ""),
            "lastusermessage" to lastUser,
            "lastcharmessage" to lastChar,
            "input" to "",
            "idle_duration" to "just now",
            "maxprompt" to p.contextSize.toString(),
        )
        val variables = HashMap(inp.variables)
        val macros = Macros(baseVars, inp.random, inp.now, variables)
        val ex: (String) -> String = { macros.expand(it) }
        fun exFor(m: Character): (String) -> String {
            if (m.id == c.id) return ex
            val mm = Macros(baseVars + mapOf("char" to m.name, "description" to m.description,
                "personality" to m.personality, "scenario" to m.scenario), inp.random, inp.now, variables)
            return { mm.expand(it) }
        }
        val batch = inp.regexBatch ?: KotlinRegexBatch(inp.regex)
        fun rxAll(items: List<RegexItem>): List<String> =
            if (inp.regex.isEmpty() || items.isEmpty()) items.map { it.text }
            else batch.run(items, RegexTarget.PROMPT, baseVars, variables.toMap())

        // --- Character definitions (one card, or all members when joining cards)
        val cardsInPrompt = if (inp.group?.joinCards == true) members else listOf(c)
        val multi = cardsInPrompt.size > 1
        fun joined(sel: (Character) -> String, fmt: (Character, String) -> String): String =
            cardsInPrompt.mapNotNull { m ->
                val v = exFor(m)(sel(m)).trim()
                if (v.isEmpty()) null else fmt(m, v)
            }.joinToString("\n\n")
        val charDesc = joined({ it.description }) { m, v -> if (multi) "${m.name}: $v" else v }
        val charPers = joined({ it.personality }) { m, v -> "${m.name}'s personality: $v" }
        val charScen = joined({ it.scenario }) { m, v -> if (multi) "${m.name}'s scenario: $v" else "Scenario: $v" }
        val persona = ex(inp.persona.description).trim()

        // --- World info (scans recent history, newest first)
        val newestFirst = inp.history.asReversed().map { it.text }
        val wiBudget = (p.contextSize * inp.wi.budgetPercent / 100).coerceAtLeast(64)
        val wiRaw = WorldInfoScanner(inp.wi, tc, inp.random).scan(inp.books, newestFirst, wiBudget, ex)
        val wiFixed = rxAll(
            (listOf(wiRaw.before, wiRaw.after, wiRaw.examplesBefore, wiRaw.examplesAfter) + wiRaw.depth.map { it.content })
                .map { RegexItem(it, RegexPlacement.WORLD_INFO, null) },
        )
        val wi = wiRaw.copy(
            before = wiFixed[0], after = wiFixed[1], examplesBefore = wiFixed[2], examplesAfter = wiFixed[3],
            depth = wiRaw.depth.mapIndexed { i, d -> d.copy(content = wiFixed[4 + i]) },
        )

        val examplesText = buildString {
            val blocks = cardsInPrompt.flatMap { m ->
                exFor(m)(m.mesExample).split(START_RX).map { it.trim() }.filter { it.isNotEmpty() }
            }
            val parts = listOf(wi.examplesBefore.trim()) + blocks + listOf(wi.examplesAfter.trim())
            val nonEmpty = parts.filter { it.isNotEmpty() }
            if (nonEmpty.isNotEmpty()) {
                append("[Example dialogue]\n")
                append(nonEmpty.joinToString("\n\n"))
            }
        }

        fun override(cardText: String, original: String) =
            if (p.preferCharPrompt && cardText.isNotBlank()) cardText.replace("{{original}}", original, ignoreCase = true)
            else original

        // --- Walk the prompt modules
        val before = ArrayList<Part>()
        val after = ArrayList<Part>()
        val injections = ArrayList<DepthInjection>()
        var historySeen = false
        var historyEnabled = p.modules.none { it.identifier == PromptModule.HISTORY }
        for (m in p.modules) {
            if (!m.enabled) continue
            if (m.identifier == PromptModule.HISTORY) { historySeen = true; historyEnabled = true; continue }
            if (!m.marker && m.inChat) {
                val t = ex(m.content).trim()
                if (t.isNotEmpty()) injections.add(DepthInjection(m.depth.coerceAtLeast(0), m.role, t, m.order))
                continue
            }
            val content = when (m.identifier) {
                PromptModule.MAIN -> ex(override(c.systemPrompt, m.content))
                PromptModule.JAILBREAK -> ex(override(c.postHistoryInstructions, m.content))
                PromptModule.WI_BEFORE -> wi.before
                PromptModule.WI_AFTER -> wi.after
                PromptModule.PERSONA -> persona
                PromptModule.CHAR_DESC -> charDesc
                PromptModule.CHAR_PERSONALITY -> charPers
                PromptModule.SCENARIO -> charScen
                PromptModule.EXAMPLES -> examplesText
                else -> if (m.marker) "" else ex(m.content)
            }.trim()
            if (content.isEmpty()) continue
            val part = Part(if (m.marker) Role.SYSTEM else m.role, content, m.identifier == PromptModule.EXAMPLES)
            if (historySeen) after.add(part) else before.add(part)
        }

        val newChat = if (historyEnabled) ex(p.newChatPrompt).trim() else ""
        c.depthPrompt?.let { dp ->
            val t = ex(dp.prompt).trim()
            if (t.isNotEmpty()) injections.add(DepthInjection(dp.depth, dp.role, t))
        }

        // Trailing instructions depending on mode
        val trailing = ArrayList<PromptMessage>()
        val history = if (historyEnabled) inp.history.filter { it.text.isNotEmpty() || it === inp.history.lastOrNull() }
        else emptyList()
        var continuePartial = ""
        if (inp.mode == GenMode.CONTINUE) {
            continuePartial = inp.history.lastOrNull()?.takeIf { !it.isUser }?.text ?: ""
            if (inp.kind == ApiKind.OPENAI_CHAT) trailing.add(PromptMessage(Role.SYSTEM, ex(p.continueNudge)))
        }
        if (inp.mode == GenMode.IMPERSONATE) trailing.add(PromptMessage(Role.SYSTEM, ex(p.impersonatePrompt)))
        else if (inp.group != null && inp.mode != GenMode.CONTINUE && p.groupNudge.isNotBlank()) {
            trailing.add(PromptMessage(Role.SYSTEM, ex(p.groupNudge)))
        }

        // --- Budgeting
        val budget = (p.contextSize - p.maxTokens).coerceAtLeast(256)
        var used = (before + after).filter { !it.isExamples }.sumOf { tc.count(it.content) + 4 } +
            trailing.sumOf { tc.count(it) } +
            injections.sumOf { tc.count(it.content) + 4 } +
            tc.count(newChat) + tc.count(p.prefill)
        val exampleCost = (before + after).filter { it.isExamples }.sumOf { tc.count(it.content) + 4 }

        val inGroup = inp.group != null
        fun displayName(m: ChatMessage) = when {
            m.isUser -> userName
            m.isSystem -> "System"
            else -> m.name.ifBlank { c.name }
        }
        // History text after prompt-only regex; in groups every line is prefixed with its speaker.
        val historyRx = rxAll(history.mapIndexed { i, m ->
            RegexItem(m.text, if (m.isUser) RegexPlacement.USER_INPUT else RegexPlacement.AI_OUTPUT, history.size - 1 - i)
        })
        fun historyText(m: ChatMessage, depth: Int): String {
            val t = historyRx[history.size - 1 - depth]
            return if (inGroup && !m.isSystem && !(inp.mode == GenMode.CONTINUE && depth == 0)) "${displayName(m)}: $t" else t
        }

        val kept = ArrayList<Pair<ChatMessage, String>>()
        for (i in history.indices.reversed()) {
            val m = history[i]
            val text = historyText(m, history.size - 1 - i)
            val cost = tc.count(text) + 4 + if (inp.kind == ApiKind.TEXT_COMPLETION) tc.count(m.name) else 0
            if (used + cost > budget && kept.isNotEmpty()) break
            used += cost
            kept.add(m to text)
        }
        kept.reverse()
        val includeExamples = exampleCost > 0 && used + exampleCost <= budget
        if (includeExamples) used += exampleCost
        val beforeParts = before.filter { !it.isExamples || includeExamples }
        val afterParts = after.filter { !it.isExamples || includeExamples }

        // --- History with depth injections
        val histMsgs = ArrayList<PromptMessage>(kept.size + injections.size)
        val names = ArrayList<String>(kept.size + injections.size)
        for ((m, text) in kept) {
            histMsgs.add(PromptMessage(if (m.isSystem) Role.SYSTEM else if (m.isUser) Role.USER else Role.ASSISTANT, text))
            names.add(displayName(m))
        }
        if (historyEnabled) {
            for (inj in injections.sortedWith(compareByDescending<DepthInjection> { it.depth }.thenBy { it.order })) {
                val idx = (histMsgs.size - inj.depth).coerceIn(0, histMsgs.size)
                histMsgs.add(idx, PromptMessage(inj.role, inj.content))
                names.add(idx, if (inj.role == Role.USER) userName else if (inj.role == Role.ASSISTANT) c.name else "System")
            }
        }

        // --- Text-completion rendering
        if (inp.kind == ApiKind.TEXT_COMPLETION) {
            val t = InstructTemplate.byName(p.instruct)
            val sb = StringBuilder()
            fun renderParts(parts: List<Part>) {
                var sys = ArrayList<String>()
                fun flushSys() {
                    if (sys.isNotEmpty()) sb.append(t.systemPrefix).append(sys.joinToString("\n\n")).append(t.systemSuffix)
                    sys = ArrayList()
                }
                for (part in parts) when (part.role) {
                    Role.SYSTEM -> sys.add(part.content)
                    Role.USER -> { flushSys(); sb.append(t.userPrefix).append(part.content).append(t.userSuffix) }
                    Role.ASSISTANT -> { flushSys(); sb.append(t.assistantPrefix).append(part.content).append(t.assistantSuffix) }
                }
                flushSys()
            }
            renderParts(beforeParts + listOfNotNull(newChat.takeIf { it.isNotEmpty() }?.let { Part(Role.SYSTEM, it) }))

            val lastIsPartial = inp.mode == GenMode.CONTINUE && continuePartial.isNotEmpty()
            for ((i, m) in histMsgs.withIndex()) {
                val name = names[i]
                val isLastPartial = lastIsPartial && i == histMsgs.lastIndex && m.role == Role.ASSISTANT
                // In groups the speaker name is already part of the text.
                val prefix = if (p.namesInText && m.role != Role.SYSTEM && !inGroup) "$name: " else ""
                when (m.role) {
                    Role.SYSTEM -> sb.append(t.systemPrefix).append(m.content).append(t.systemSuffix)
                    Role.USER -> sb.append(t.userPrefix).append(prefix).append(m.content).append(t.userSuffix)
                    Role.ASSISTANT -> {
                        sb.append(t.assistantPrefix)
                        if (isLastPartial && (p.namesInText || inGroup)) sb.append("${c.name}: ") else sb.append(prefix)
                        sb.append(m.content)
                        if (!isLastPartial) sb.append(t.assistantSuffix)
                    }
                }
            }
            if (!lastIsPartial) {
                renderParts(afterParts + trailing.map { Part(it.role, it.content) })
                val withNames = p.namesInText || inGroup
                if (inp.mode == GenMode.IMPERSONATE) {
                    sb.append(t.userPrefix)
                    if (withNames) sb.append("$userName:")
                } else {
                    sb.append(t.assistantPrefix)
                    if (withNames) sb.append("${c.name}:")
                    if (p.prefill.isNotBlank()) sb.append(if (withNames) " " else "").append(ex(p.prefill))
                }
            }
            val stop = LinkedHashSet<String>()
            stop.addAll(t.stop)
            if (p.namesInText || inGroup) {
                val speaker = if (inp.mode == GenMode.IMPERSONATE) userName else c.name
                (members.map { it.name } + userName).filter { it != speaker }.forEach { stop.add("\n$it:") }
            }
            p.stopStrings.filter { it.isNotEmpty() }.forEach { stop.add(ex(it)) }
            return BuiltPrompt(
                messages = emptyList(), text = sb.toString(), stop = stop.toList(),
                prefill = "", tokenEstimate = used, historyIncluded = kept.size,
                activatedLore = wi.activatedUids, variables = variables,
            )
        }

        // --- Chat rendering
        val msgs = ArrayList<PromptMessage>()
        beforeParts.forEach { msgs.add(PromptMessage(it.role, it.content)) }
        if (newChat.isNotEmpty()) msgs.add(PromptMessage(Role.SYSTEM, newChat))

        var prefill = ""
        if (inp.kind == ApiKind.ANTHROPIC && inp.mode == GenMode.CONTINUE && continuePartial.isNotEmpty() &&
            histMsgs.lastOrNull()?.role == Role.ASSISTANT
        ) {
            // Anthropic continues natively from a trailing assistant turn.
            histMsgs.removeAt(histMsgs.lastIndex)
            msgs.addAll(histMsgs)
            afterParts.forEach { msgs.add(PromptMessage(it.role, it.content)) }
            msgs.addAll(trailing)
            prefill = continuePartial.trimEnd()
        } else {
            msgs.addAll(histMsgs)
            afterParts.forEach { msgs.add(PromptMessage(it.role, it.content)) }
            msgs.addAll(trailing)
            if (inp.kind == ApiKind.ANTHROPIC && inp.mode != GenMode.IMPERSONATE && p.prefill.isNotBlank()) {
                prefill = ex(p.prefill).trimEnd()
            }
        }
        val finalMsgs = if (p.squashSystem) squash(msgs) else msgs
        val stop = p.stopStrings.filter { it.isNotEmpty() }.map(ex)
        return BuiltPrompt(
            messages = finalMsgs, text = null, stop = stop, prefill = prefill,
            tokenEstimate = used, historyIncluded = kept.size, activatedLore = wi.activatedUids,
            variables = variables,
        )
    }

    private fun squash(msgs: List<PromptMessage>): List<PromptMessage> {
        val out = ArrayList<PromptMessage>(msgs.size)
        for (m in msgs) {
            val last = out.lastOrNull()
            if (last != null && last.role == Role.SYSTEM && m.role == Role.SYSTEM) {
                out[out.lastIndex] = last.copy(content = last.content + "\n" + m.content)
            } else out.add(m)
        }
        return out
    }

    private val START_RX = Regex("<START>", RegexOption.IGNORE_CASE)
}
