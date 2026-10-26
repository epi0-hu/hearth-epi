package app.hearth

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import app.hearth.core.ApiKind
import app.hearth.core.Character
import app.hearth.core.ChatMessage
import app.hearth.core.ChatStore
import app.hearth.core.GenMode
import app.hearth.core.Group
import app.hearth.core.GroupContext
import app.hearth.core.GroupStrategy
import app.hearth.core.JObj
import app.hearth.core.LlmCall
import app.hearth.core.LlmClient
import app.hearth.core.MJObj
import app.hearth.core.Macros
import app.hearth.core.PromptBuilder
import app.hearth.core.PromptInput
import app.hearth.core.RegexEngine
import app.hearth.core.RegexPlacement
import app.hearth.core.RegexTarget
import app.hearth.core.TokenCounter
import app.hearth.core.stDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.random.Random

/** What the UI shows while a reply is streaming. */
data class StreamState(val id: Long, val text: String, val reasoning: String, val swipeLabel: String? = null)

/** Thread-safe accumulator written by the network thread, read by the UI ticker. */
private class StreamBuffer {
    private val text = StringBuilder()
    private val reasoning = StringBuilder()
    @Volatile var dirty = false

    @Synchronized fun add(t: String, r: String) { text.append(t); reasoning.append(r); dirty = true }
    @Synchronized fun snapshot(): Pair<String, String> { dirty = false; return text.toString() to reasoning.toString() }
}

/**
 * One open chat, either with a single character or a group. Messages live in a
 * SnapshotStateList so editing one message only recomposes that row; streamed tokens go
 * to [streaming] and are published at ~30 fps instead of per token.
 */
class ChatSession(
    private val vm: MainViewModel,
    /** Character id, or group chat key. */
    val key: String,
    members: List<Character>,
    group: Group?,
    val file: File,
    private val header: MJObj,
    initial: List<ChatMessage>,
) {
    var members by mutableStateOf(members)
    var group by mutableStateOf(group)
    val character: Character get() = members.first()
    val title: String get() = group?.name ?: character.name
    val isGroup: Boolean get() = group != null

    val messages = mutableStateListOf<ChatMessage>().apply { addAll(initial) }
    var streaming by mutableStateOf<StreamState?>(null)
        private set
    var generating by mutableStateOf(false)
        private set
    var draft by mutableStateOf("")
    var lastPromptInfo by mutableStateOf("")
        private set

    private val scope get() = vm.viewModelScope
    private var call: LlmCall? = null
    private var queueJob: Job? = null
    private var saveJob: Job? = null
    @Volatile private var stopRequested = false

    fun headerSnapshot(): JObj = header

    /** The member who wrote a message (by name), falling back to the first member. */
    fun memberFor(name: String): Character = members.firstOrNull { it.name == name } ?: character

    private fun regex() = vm.regexFor(members)
    private fun macrosFor(c: Character) = Macros(mapOf("user" to vm.settings.value.persona.name, "char" to c.name))

    // ------------------------------------------------------------ user actions
    fun send() {
        if (generating) return
        val raw = draft.trim()
        if (raw.isNotEmpty()) {
            val text = RegexEngine.apply(regex(), raw, RegexPlacement.USER_INPUT, RegexTarget.STORE, 0, macrosFor(character)::expand)
            messages.add(ChatMessage(isUser = true, name = vm.settings.value.persona.name, text = text))
            draft = ""
            scheduleSave()
            vm.onChatActivity(key)
        }
        triggerReplies(raw)
    }

    /** Lets the group's strategy (or the single character) answer. */
    private fun triggerReplies(userText: String) {
        val g = group
        if (g == null) { runQueue(GenMode.NORMAL, listOf(character)); return }
        val speakers = pickSpeakers(g, userText)
        if (speakers.isEmpty()) {
            if (g.strategy == GroupStrategy.MANUAL) vm.toast("Tap a character above the message box to have them reply")
            return
        }
        runQueue(GenMode.NORMAL, speakers)
    }

    private fun pickSpeakers(g: Group, userText: String): List<Character> {
        val active = members.filter { it.id in g.active }
        if (active.isEmpty()) return emptyList()
        return when (g.strategy) {
            GroupStrategy.LIST -> active
            GroupStrategy.MANUAL -> emptyList()
            GroupStrategy.NATURAL -> {
                val last = messages.lastOrNull { !it.isUser && !it.isSystem }?.name
                fun allowed(c: Character) = g.allowSelfResponses || active.size == 1 || c.name != last
                val chosen = LinkedHashSet<Character>()
                // Characters mentioned by name reply first, in the order they were mentioned.
                active.mapNotNull { c ->
                    val i = Regex("(?<![\\p{L}\\d])" + Regex.escape(c.name) + "(?![\\p{L}\\d])", RegexOption.IGNORE_CASE)
                        .find(userText)?.range?.first
                    i?.let { c to it }
                }.sortedBy { it.second }.forEach { chosen.add(it.first) }
                if (chosen.isEmpty()) {
                    for (c in active.shuffled()) if (allowed(c) && Random.nextFloat() < c.talkativeness) chosen.add(c)
                }
                if (chosen.isEmpty()) active.filter(::allowed).ifEmpty { active }.random().let { chosen.add(it) }
                chosen.toList()
            }
        }
    }

    /** Group chats: make a specific member reply now. */
    fun speak(member: Character) {
        if (generating) return
        runQueue(GenMode.NORMAL, listOf(member))
    }

    fun regenerate() {
        val last = messages.lastOrNull() ?: return
        if (last.isUser) triggerReplies(last.text) else runQueue(GenMode.SWIPE, listOf(memberFor(last.name)))
    }

    fun continueLast() {
        val last = messages.lastOrNull() ?: return
        if (!last.isUser) runQueue(GenMode.CONTINUE, listOf(memberFor(last.name))) else triggerReplies(last.text)
    }

    fun impersonate() {
        val speaker = group?.let { g -> members.firstOrNull { it.id in g.active } } ?: character
        runQueue(GenMode.IMPERSONATE, listOf(speaker))
    }

    fun swipe(id: Long, dir: Int) {
        if (generating) return
        val idx = messages.indexOfFirst { it.id == id }
        if (idx != messages.lastIndex || idx < 0) return
        val m = messages[idx]
        if (m.isUser) return
        when {
            dir < 0 && m.swipeId > 0 -> messages[idx] = m.withSwipe(m.swipeId - 1)
            dir < 0 -> messages[idx] = m.withSwipe(m.swipes.size - 1)
            m.swipeId < m.swipes.size - 1 -> messages[idx] = m.withSwipe(m.swipeId + 1)
            // Greeting-only chat: cycle through alternate greetings instead of generating.
            messages.none { it.isUser } -> messages[idx] = m.withSwipe(0)
            else -> { runQueue(GenMode.SWIPE, listOf(memberFor(m.name))); return }
        }
        scheduleSave()
    }

    fun edit(id: Long, text: String) {
        val idx = messages.indexOfFirst { it.id == id }
        if (idx < 0) return
        val m = messages[idx]
        val place = if (m.isUser) RegexPlacement.USER_INPUT else RegexPlacement.AI_OUTPUT
        val fixed = RegexEngine.apply(regex(), text, place, RegexTarget.STORE, messages.lastIndex - idx,
            macrosFor(memberFor(m.name))::expand, onlyRunOnEdit = true)
        messages[idx] = m.withText(fixed)
        scheduleSave()
    }

    fun delete(id: Long) {
        if (generating) return
        val idx = messages.indexOfFirst { it.id == id }
        if (idx < 0) return
        messages.removeAt(idx)
        scheduleSave()
    }

    /** Deletes this message and everything after it. */
    fun deleteFrom(id: Long) {
        if (generating) return
        val idx = messages.indexOfFirst { it.id == id }
        if (idx < 0) return
        while (messages.size > idx) messages.removeAt(messages.lastIndex)
        scheduleSave()
    }

    fun stop() {
        stopRequested = true
        call?.cancel()
    }

    // ------------------------------------------------------------ generation
    private fun runQueue(mode: GenMode, speakers: List<Character>) {
        if (generating || speakers.isEmpty()) return
        val conn = vm.settings.value.connection
        if (conn.baseUrl.isBlank()) { vm.toast("Set up an API connection in Settings first"); return }
        if (conn.kind == ApiKind.ANTHROPIC && conn.model.isBlank()) { vm.toast("Pick a model in Settings first"); return }
        generating = true
        stopRequested = false
        queueJob = scope.launch {
            try {
                for (sp in speakers) {
                    if (stopRequested) break
                    if (!generateOne(mode, sp)) break
                }
            } finally {
                generating = false
                streaming = null
            }
        }
    }

    /** Generates one reply. Returns false when it failed or was stopped. */
    private suspend fun generateOne(mode: GenMode, speaker: Character): Boolean {
        val settings = vm.settings.value
        val conn = settings.connection
        val preset = settings.preset
        val last = messages.lastOrNull()
        val history: List<ChatMessage>
        val targetId: Long
        val swipeLabel: String?
        when (mode) {
            GenMode.NORMAL -> {
                history = messages.toList()
                val placeholder = ChatMessage(isUser = false, name = speaker.name, text = "")
                messages.add(placeholder)
                targetId = placeholder.id
                swipeLabel = null
            }
            GenMode.SWIPE -> {
                if (last == null || last.isUser) return false
                history = messages.dropLast(1)
                targetId = last.id
                swipeLabel = "${last.swipes.size + 1}/${last.swipes.size + 1}"
            }
            GenMode.CONTINUE -> {
                if (last == null || last.isUser) return false
                history = messages.toList()
                targetId = last.id
                swipeLabel = null
            }
            GenMode.IMPERSONATE -> {
                history = messages.toList()
                targetId = IMPERSONATE_ID
                swipeLabel = null
            }
        }

        val base = if (mode == GenMode.CONTINUE) last!!.text else ""
        val allMembers = members
        val g = group
        val books = vm.booksFor(if (g != null && g.joinCards) allMembers else listOf(speaker))
        val regex = regex()
        val newCall = LlmCall()
        call = newCall
        streaming = StreamState(targetId, base, "", swipeLabel)
        val buf = StreamBuffer()

        // UI ticker: publish buffered text at most every 33 ms.
        val ticker = scope.launch {
            while (isActive) {
                delay(33)
                if (buf.dirty) {
                    val (t, r) = buf.snapshot()
                    streaming = StreamState(targetId, base + t, r, swipeLabel)
                }
            }
        }
        var errorMsg: String? = null
        var resultText = ""
        var resultReasoning = ""
        var stops: List<String> = emptyList()
        try {
            val out = withContext(Dispatchers.IO) {
                val prompt = PromptBuilder.build(
                    PromptInput(
                        char = speaker, persona = settings.persona, preset = preset, kind = conn.kind,
                        history = history, books = books, wi = settings.wi, mode = mode,
                        tokens = TokenCounter(settings.tokenRatio), regex = regex, model = conn.model,
                        group = g?.let { GroupContext(allMembers, it.joinCards) },
                    ),
                )
                stops = prompt.stop
                withContext(Dispatchers.Main) {
                    lastPromptInfo = "~${prompt.tokenEstimate} tokens · ${prompt.historyIncluded} messages" +
                        if (prompt.activatedLore.isNotEmpty()) " · ${prompt.activatedLore.size} lore" else ""
                }
                LlmClient.generate(conn, preset, prompt, newCall) { t, r -> buf.add(t, r) }
            }
            resultText = out.text
            resultReasoning = out.reasoning
        } catch (e: Exception) {
            errorMsg = e.message ?: e.javaClass.simpleName
            val (t, r) = buf.snapshot()
            resultText = t
            resultReasoning = r
        } finally {
            ticker.cancel()
        }
        val ok = finish(mode, speaker, targetId, base, clean(resultText, mode, stops, speaker), resultReasoning)
        streaming = null
        call = null
        errorMsg?.let { vm.toast(it) }
        return ok && errorMsg == null && !newCall.cancelled
    }

    private fun finish(mode: GenMode, speaker: Character, targetId: Long, base: String, raw: String, rawReasoning: String): Boolean {
        val ex = macrosFor(speaker)::expand
        val scripts = regex()
        val text = if (mode == GenMode.IMPERSONATE) raw
        else RegexEngine.apply(scripts, if (mode == GenMode.CONTINUE) base + raw else raw,
            RegexPlacement.AI_OUTPUT, RegexTarget.STORE, 0, ex)
        val reasoning = RegexEngine.apply(scripts, rawReasoning, RegexPlacement.REASONING, RegexTarget.STORE, 0, ex)
        val idx = messages.indexOfFirst { it.id == targetId }
        var ok = true
        when (mode) {
            GenMode.NORMAL -> if (idx >= 0) {
                if (text.isBlank()) { messages.removeAt(idx); ok = false }
                else messages[idx] = messages[idx].copy(
                    text = text, swipes = listOf(text), swipeId = 0, reasoning = reasoning, sendDate = stDate(),
                )
            }
            GenMode.SWIPE -> if (idx >= 0 && text.isNotBlank()) {
                messages[idx] = messages[idx].addSwipe(text).copy(reasoning = reasoning)
            }
            GenMode.CONTINUE -> if (idx >= 0 && raw.isNotEmpty()) {
                messages[idx] = messages[idx].withText(text)
            }
            GenMode.IMPERSONATE -> if (text.isNotBlank()) {
                draft = RegexEngine.apply(scripts, text.trim(), RegexPlacement.USER_INPUT, RegexTarget.STORE, 0, ex)
            }
        }
        scheduleSave()
        return ok
    }

    private fun clean(raw: String, mode: GenMode, stops: List<String>, speaker: Character): String {
        var t = raw
        for (s in stops) {
            if (s.isEmpty()) continue
            val i = t.indexOf(s)
            if (i >= 0) t = t.substring(0, i)
        }
        if (mode == GenMode.CONTINUE) return t.trimEnd()
        t = t.trimStart()
        val names = listOf(speaker.name, vm.settings.value.persona.name)
        for (n in names) if (t.startsWith("$n:")) t = t.substring(n.length + 1).trimStart()
        return t.trimEnd()
    }

    // ------------------------------------------------------------ persistence
    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(400)
            val snap = messages.toList()
            withContext(Dispatchers.IO) { ChatStore.write(file, header, snap) }
        }
    }

    fun flush() {
        saveJob?.cancel()
        val snap = messages.filter { it.text.isNotEmpty() || it.isUser }
        scope.launch(Dispatchers.IO) { ChatStore.write(file, header, snap) }
    }

    fun flushBlocking() {
        saveJob?.cancel()
        val snap = messages.filter { it.text.isNotEmpty() || it.isUser }
        runBlocking(Dispatchers.IO) { runCatching { ChatStore.write(file, header, snap) } }
    }

    companion object {
        const val IMPERSONATE_ID = -1L
    }
}
