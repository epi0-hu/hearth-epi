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
import app.hearth.core.LlmCall
import app.hearth.core.LlmClient
import app.hearth.core.MJObj
import app.hearth.core.PromptBuilder
import app.hearth.core.PromptInput
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
 * One open chat. Messages live in a SnapshotStateList so editing one message only
 * recomposes that row; streamed tokens go to [streaming] and are published at ~30 fps
 * instead of per token, which keeps long, fast streams smooth.
 */
class ChatSession(
    private val vm: MainViewModel,
    character: Character,
    val file: File,
    private val header: MJObj,
    initial: List<ChatMessage>,
) {
    var character by mutableStateOf(character)
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
    private var genJob: Job? = null
    private var saveJob: Job? = null

    // ------------------------------------------------------------ user actions
    fun send() {
        if (generating) return
        val text = draft.trim()
        if (text.isNotEmpty()) {
            messages.add(ChatMessage(isUser = true, name = vm.settings.value.persona.name, text = text))
            draft = ""
            scheduleSave()
            vm.onChatActivity(character.id)
        }
        if (messages.isNotEmpty()) generate(GenMode.NORMAL)
    }

    fun regenerate() {
        val last = messages.lastOrNull() ?: return
        if (last.isUser) generate(GenMode.NORMAL) else generate(GenMode.SWIPE)
    }

    fun continueLast() {
        val last = messages.lastOrNull() ?: return
        if (!last.isUser) generate(GenMode.CONTINUE) else generate(GenMode.NORMAL)
    }

    fun impersonate() = generate(GenMode.IMPERSONATE)

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
            messages.size == 1 -> messages[idx] = m.withSwipe(0)
            else -> { generate(GenMode.SWIPE); return }
        }
        scheduleSave()
    }

    fun edit(id: Long, text: String) {
        val idx = messages.indexOfFirst { it.id == id }
        if (idx < 0) return
        messages[idx] = messages[idx].withText(text)
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
        call?.cancel()
    }

    // ------------------------------------------------------------ generation
    private fun generate(mode: GenMode) {
        if (generating) return
        val settings = vm.settings.value
        val conn = settings.connection
        val preset = settings.preset
        if (conn.baseUrl.isBlank()) { vm.toast("Set up an API connection in Settings first"); return }
        if (conn.kind == ApiKind.ANTHROPIC && conn.model.isBlank()) { vm.toast("Pick a model in Settings first"); return }

        val char = character
        val last = messages.lastOrNull()
        val history: List<ChatMessage>
        val targetId: Long
        val swipeLabel: String?
        when (mode) {
            GenMode.NORMAL -> {
                history = messages.toList()
                val placeholder = ChatMessage(isUser = false, name = char.name, text = "")
                messages.add(placeholder)
                targetId = placeholder.id
                swipeLabel = null
            }
            GenMode.SWIPE -> {
                if (last == null || last.isUser) return
                history = messages.dropLast(1)
                targetId = last.id
                swipeLabel = "${last.swipes.size + 1}/${last.swipes.size + 1}"
            }
            GenMode.CONTINUE -> {
                if (last == null || last.isUser) return
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
        val books = vm.booksFor(char)
        val newCall = LlmCall()
        call = newCall
        generating = true
        streaming = StreamState(targetId, base, "", swipeLabel)
        val buf = StreamBuffer()

        genJob = scope.launch {
            // UI ticker: publish buffered text at most every 33 ms.
            val ticker = launch {
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
                            char = char, persona = settings.persona, preset = preset, kind = conn.kind,
                            history = history, books = books, wi = settings.wi, mode = mode,
                            tokens = TokenCounter(settings.tokenRatio),
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
            finish(mode, targetId, base, clean(resultText, mode, stops), resultReasoning)
            errorMsg?.let { vm.toast(it) }
        }
    }

    private fun finish(mode: GenMode, targetId: Long, base: String, text: String, reasoning: String) {
        streaming = null
        generating = false
        call = null
        val idx = messages.indexOfFirst { it.id == targetId }
        when (mode) {
            GenMode.NORMAL -> if (idx >= 0) {
                if (text.isBlank()) messages.removeAt(idx)
                else messages[idx] = messages[idx].copy(
                    text = text, swipes = listOf(text), swipeId = 0, reasoning = reasoning, sendDate = stDate(),
                )
            }
            GenMode.SWIPE -> if (idx >= 0 && text.isNotBlank()) {
                messages[idx] = messages[idx].addSwipe(text).copy(reasoning = reasoning)
            }
            GenMode.CONTINUE -> if (idx >= 0 && text.isNotEmpty()) {
                messages[idx] = messages[idx].withText(base + text)
            }
            GenMode.IMPERSONATE -> if (text.isNotBlank()) draft = text.trim()
        }
        scheduleSave()
    }

    private fun clean(raw: String, mode: GenMode, stops: List<String>): String {
        var t = raw
        for (s in stops) {
            if (s.isEmpty()) continue
            val i = t.indexOf(s)
            if (i >= 0) t = t.substring(0, i)
        }
        if (mode == GenMode.CONTINUE) return t.trimEnd()
        t = t.trimStart()
        val names = listOf(character.name, vm.settings.value.persona.name)
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
