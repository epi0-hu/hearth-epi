package app.hearth.core

import java.util.Date
import kotlin.random.Random

data class PromptMessage(val role: Role, val content: String)

enum class GenMode { NORMAL, SWIPE, CONTINUE, IMPERSONATE }

data class PromptInput(
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
)

/**
 * Assembles the prompt in SillyTavern's default order:
 * main → WI before → character defs → persona → WI after → examples → [new chat]
 * → history (with depth injections) → post-history instructions.
 * Oldest history is trimmed first to fit the context budget.
 */
object PromptBuilder {

    fun build(inp: PromptInput): BuiltPrompt {
        val c = inp.char
        val p = inp.preset
        val tc = inp.tokens
        val userName = inp.persona.name
        val macros = Macros(
            mapOf(
                "user" to userName,
                "char" to c.name,
                "description" to c.description,
                "personality" to c.personality,
                "scenario" to c.scenario,
                "persona" to inp.persona.description,
                "charprompt" to c.systemPrompt,
                "charjailbreak" to c.postHistoryInstructions,
                "model" to "",
            ),
            inp.random, inp.now,
        )
        val ex: (String) -> String = { macros.expand(it) }

        // --- Fixed system parts
        val main = ex(
            if (p.preferCharPrompt && c.systemPrompt.isNotBlank()) {
                c.systemPrompt.replace("{{original}}", p.mainPrompt, ignoreCase = true)
            } else p.mainPrompt,
        )
        val postHistory = ex(
            if (p.preferCharPrompt && c.postHistoryInstructions.isNotBlank()) {
                c.postHistoryInstructions.replace("{{original}}", p.postHistory, ignoreCase = true)
            } else p.postHistory,
        )

        val budget = (p.contextSize - p.maxTokens).coerceAtLeast(256)

        // --- World info (scans recent history, newest first)
        val newestFirst = inp.history.asReversed().map { it.text }
        val wiBudget = (p.contextSize * inp.wi.budgetPercent / 100).coerceAtLeast(64)
        val wi = WorldInfoScanner(inp.wi, tc, inp.random).scan(inp.books, newestFirst, wiBudget, ex)

        val charDefs = buildString {
            val d = ex(c.description).trim()
            if (d.isNotEmpty()) append(d)
            val pers = ex(c.personality).trim()
            if (pers.isNotEmpty()) { if (isNotEmpty()) append("\n"); append("${c.name}'s personality: $pers") }
            val sc = ex(c.scenario).trim()
            if (sc.isNotEmpty()) { if (isNotEmpty()) append("\n"); append("Scenario: $sc") }
        }
        val persona = ex(inp.persona.description).trim()

        val systemParts = listOf(main, wi.before, charDefs, persona, wi.after)
            .map { it.trim() }.filter { it.isNotEmpty() }

        val examplesText = buildString {
            val blocks = ex(c.mesExample).split(START_RX).map { it.trim() }.filter { it.isNotEmpty() }
            val parts = listOf(wi.examplesBefore.trim()) + blocks + listOf(wi.examplesAfter.trim())
            val nonEmpty = parts.filter { it.isNotEmpty() }
            if (nonEmpty.isNotEmpty()) {
                append("[Example dialogue]\n")
                append(nonEmpty.joinToString("\n\n"))
            }
        }
        val newChat = ex(p.newChatPrompt).trim()

        // Depth injections: WI at-depth entries + character's depth prompt
        val injections = ArrayList<DepthInjection>(wi.depth)
        c.depthPrompt?.let { injections.add(DepthInjection(it.depth, it.role, ex(it.prompt))) }

        // Trailing instructions depending on mode
        val trailing = ArrayList<PromptMessage>()
        if (postHistory.isNotBlank()) trailing.add(PromptMessage(Role.SYSTEM, postHistory))
        val history = inp.history.filter { it.text.isNotEmpty() || it === inp.history.lastOrNull() }
        var continuePartial = ""
        if (inp.mode == GenMode.CONTINUE) {
            continuePartial = history.lastOrNull()?.takeIf { !it.isUser }?.text ?: ""
            if (inp.kind == ApiKind.OPENAI_CHAT) trailing.add(PromptMessage(Role.SYSTEM, ex(p.continueNudge)))
        }
        if (inp.mode == GenMode.IMPERSONATE) trailing.add(PromptMessage(Role.SYSTEM, ex(p.impersonatePrompt)))

        // --- Budgeting
        var used = systemParts.sumOf { tc.count(it) + 4 } +
            trailing.sumOf { tc.count(it) } +
            injections.sumOf { tc.count(it.content) + 4 } +
            tc.count(newChat) + tc.count(p.prefill)
        val exampleCost = if (examplesText.isEmpty()) 0 else tc.count(examplesText) + 4

        // Walk history newest → oldest
        val kept = ArrayList<ChatMessage>()
        for (i in history.indices.reversed()) {
            val m = history[i]
            val cost = tc.count(m.text) + 4 + if (inp.kind == ApiKind.TEXT_COMPLETION) tc.count(m.name) else 0
            if (used + cost > budget && kept.isNotEmpty()) break
            used += cost
            kept.add(m)
        }
        kept.reverse()
        val includeExamples = examplesText.isNotEmpty() && used + exampleCost <= budget
        if (includeExamples) used += exampleCost

        // --- History with depth injections
        val histMsgs = ArrayList<PromptMessage>(kept.size + injections.size)
        for (m in kept) {
            val role = when {
                m.isSystem -> Role.SYSTEM
                m.isUser -> Role.USER
                else -> Role.ASSISTANT
            }
            histMsgs.add(PromptMessage(role, m.text))
        }
        val names = kept.map { if (it.isUser) userName else if (it.isSystem) "System" else it.name.ifBlank { c.name } }
            .toMutableList()
        // Insert deepest first so smaller depths keep their positions relative to the end.
        for (inj in injections.filter { it.content.isNotBlank() }.sortedByDescending { it.depth }) {
            val idx = (histMsgs.size - inj.depth).coerceIn(0, histMsgs.size)
            histMsgs.add(idx, PromptMessage(inj.role, inj.content))
            names.add(idx, if (inj.role == Role.USER) userName else if (inj.role == Role.ASSISTANT) c.name else "System")
        }

        // --- Text-completion rendering
        if (inp.kind == ApiKind.TEXT_COMPLETION) {
            val t = InstructTemplate.byName(p.instruct)
            val sb = StringBuilder()
            val sys = ArrayList(systemParts)
            if (includeExamples) sys.add(examplesText)
            if (newChat.isNotEmpty()) sys.add(newChat)
            if (sys.isNotEmpty()) sb.append(t.systemPrefix).append(sys.joinToString("\n\n")).append(t.systemSuffix)

            val lastIsPartial = inp.mode == GenMode.CONTINUE && continuePartial.isNotEmpty()
            for ((i, m) in histMsgs.withIndex()) {
                val name = names[i]
                val isLastPartial = lastIsPartial && i == histMsgs.lastIndex && m.role == Role.ASSISTANT
                val prefix = if (p.namesInText && m.role != Role.SYSTEM) "$name: " else ""
                when (m.role) {
                    Role.SYSTEM -> sb.append(t.systemPrefix).append(m.content).append(t.systemSuffix)
                    Role.USER -> sb.append(t.userPrefix).append(prefix).append(m.content).append(t.userSuffix)
                    Role.ASSISTANT -> {
                        sb.append(t.assistantPrefix).append(prefix).append(m.content)
                        if (!isLastPartial) sb.append(t.assistantSuffix)
                    }
                }
            }
            if (!lastIsPartial) {
                for (tr in trailing) sb.append(t.systemPrefix).append(tr.content).append(t.systemSuffix)
                if (inp.mode == GenMode.IMPERSONATE) {
                    sb.append(t.userPrefix)
                    if (p.namesInText) sb.append("$userName:")
                } else {
                    sb.append(t.assistantPrefix)
                    if (p.namesInText) sb.append("${c.name}:")
                    if (p.prefill.isNotBlank()) sb.append(if (p.namesInText) " " else "").append(ex(p.prefill))
                }
            }
            val stop = LinkedHashSet<String>()
            stop.addAll(t.stop)
            if (p.namesInText) {
                stop.add("\n$userName:")
                if (inp.mode == GenMode.IMPERSONATE) { stop.remove("\n$userName:"); stop.add("\n${c.name}:") }
            }
            p.stopStrings.filter { it.isNotEmpty() }.forEach { stop.add(ex(it)) }
            return BuiltPrompt(
                messages = emptyList(), text = sb.toString(), stop = stop.toList(),
                prefill = "", tokenEstimate = used, historyIncluded = kept.size,
                activatedLore = wi.activatedUids,
            )
        }

        // --- Chat rendering
        val msgs = ArrayList<PromptMessage>()
        systemParts.forEach { msgs.add(PromptMessage(Role.SYSTEM, it)) }
        if (includeExamples) msgs.add(PromptMessage(Role.SYSTEM, examplesText))
        if (newChat.isNotEmpty()) msgs.add(PromptMessage(Role.SYSTEM, newChat))

        var prefill = ""
        if (inp.kind == ApiKind.ANTHROPIC && inp.mode == GenMode.CONTINUE && continuePartial.isNotEmpty() &&
            histMsgs.lastOrNull()?.role == Role.ASSISTANT
        ) {
            // Anthropic continues natively from a trailing assistant turn.
            histMsgs.removeAt(histMsgs.lastIndex)
            msgs.addAll(histMsgs)
            msgs.addAll(trailing)
            prefill = continuePartial.trimEnd()
        } else {
            msgs.addAll(histMsgs)
            msgs.addAll(trailing)
            if (inp.kind == ApiKind.ANTHROPIC && inp.mode != GenMode.IMPERSONATE && p.prefill.isNotBlank()) {
                prefill = ex(p.prefill).trimEnd()
            }
        }
        val stop = p.stopStrings.filter { it.isNotEmpty() }.map(ex)
        return BuiltPrompt(
            messages = msgs, text = null, stop = stop, prefill = prefill,
            tokenEstimate = used, historyIncluded = kept.size, activatedLore = wi.activatedUids,
        )
    }

    private val START_RX = Regex("<START>", RegexOption.IGNORE_CASE)
}
