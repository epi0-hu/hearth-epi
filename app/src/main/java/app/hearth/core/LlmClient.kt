package app.hearth.core

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class LlmException(val code: Int, message: String) : Exception(message)

data class HttpSpec(val url: String, val headers: Map<String, String>, val body: String?)

/** One parsed piece of a streamed response. */
data class StreamDelta(val text: String = "", val reasoning: String = "", val done: Boolean = false)

data class GenOutput(val text: String, val reasoning: String, val cancelled: Boolean)

/** Handle used to abort an in-flight request from another thread. */
class LlmCall {
    @Volatile var cancelled = false
        private set
    @Volatile internal var conn: HttpURLConnection? = null

    fun cancel() {
        cancelled = true
        runCatching { conn?.disconnect() }
    }
}

/**
 * Zero-dependency HTTP client for the three supported API families. Uses
 * HttpURLConnection so it runs unchanged on Android and the JVM.
 */
object LlmClient {

    private fun base(c: Connection) = c.baseUrl.trim().trimEnd('/')

    fun buildRequest(c: Connection, preset: Preset, prompt: BuiltPrompt, stream: Boolean): HttpSpec {
        val headers = LinkedHashMap<String, String>()
        headers["Content-Type"] = "application/json"
        headers["Accept"] = if (stream) "text/event-stream" else "application/json"
        val body = MJObj()
        val url: String
        when (c.kind) {
            ApiKind.ANTHROPIC -> {
                url = base(c) + "/messages"
                if (c.apiKey.isNotBlank()) headers["x-api-key"] = c.apiKey.trim()
                headers["anthropic-version"] = "2023-06-01"
                val (system, msgs) = toAnthropic(prompt)
                body["model"] = c.model
                body["max_tokens"] = preset.maxTokens
                if (system.isNotBlank()) body["system"] = system
                body["messages"] = msgs.map { jsonOf("role" to it.role.api, "content" to it.content) }
                body["temperature"] = preset.temperature.coerceIn(0.0, 1.0)
                if (preset.topP < 1.0) body["top_p"] = preset.topP
                if (preset.topK > 0) body["top_k"] = preset.topK
                val stops = prompt.stop.filter { it.isNotBlank() }
                if (stops.isNotEmpty()) body["stop_sequences"] = stops
                body["stream"] = stream
            }
            ApiKind.OPENAI_CHAT -> {
                url = base(c) + "/chat/completions"
                auth(c, headers)
                val msgs = prompt.messages.map { jsonOf("role" to it.role.api, "content" to it.content) }.toMutableList()
                if (prompt.prefill.isNotBlank()) msgs.add(jsonOf("role" to "assistant", "content" to prompt.prefill))
                if (c.model.isNotBlank()) body["model"] = c.model
                body["messages"] = msgs
                commonSamplers(body, c, preset)
                if (prompt.stop.isNotEmpty()) body["stop"] = prompt.stop.take(4)
                body["stream"] = stream
            }
            ApiKind.TEXT_COMPLETION -> {
                url = base(c) + "/completions"
                auth(c, headers)
                if (c.model.isNotBlank()) body["model"] = c.model
                body["prompt"] = prompt.text ?: ""
                commonSamplers(body, c, preset)
                if (prompt.stop.isNotEmpty()) body["stop"] = prompt.stop
                body["stream"] = stream
            }
        }
        return HttpSpec(url, headers, Json.stringify(body))
    }

    private fun auth(c: Connection, headers: MutableMap<String, String>) {
        if (c.apiKey.isNotBlank()) headers["Authorization"] = "Bearer " + c.apiKey.trim()
        headers["X-Title"] = "Hearth"
    }

    private fun commonSamplers(body: MJObj, c: Connection, p: Preset) {
        body["max_tokens"] = p.maxTokens
        body["temperature"] = p.temperature
        body["top_p"] = p.topP
        if (p.frequencyPenalty != 0.0) body["frequency_penalty"] = p.frequencyPenalty
        if (p.presencePenalty != 0.0) body["presence_penalty"] = p.presencePenalty
        if (c.extraSamplers) {
            if (p.topK > 0) body["top_k"] = p.topK
            if (p.minP > 0.0) body["min_p"] = p.minP
            if (p.repetitionPenalty != 1.0) body["repetition_penalty"] = p.repetitionPenalty
        }
    }

    /**
     * Anthropic wants: one system string, strictly user-first, no system turns mid-chat,
     * and a trailing assistant turn (prefill) without trailing whitespace.
     */
    fun toAnthropic(prompt: BuiltPrompt): Pair<String, List<PromptMessage>> {
        val msgs = prompt.messages
        var i = 0
        val sys = StringBuilder()
        while (i < msgs.size && msgs[i].role == Role.SYSTEM) {
            if (sys.isNotEmpty()) sys.append("\n\n")
            sys.append(msgs[i].content)
            i++
        }
        val out = ArrayList<PromptMessage>()
        for (m in msgs.subList(i, msgs.size)) {
            val role = if (m.role == Role.SYSTEM) Role.USER else m.role
            val content = m.content
            if (content.isBlank()) continue
            val last = out.lastOrNull()
            if (last != null && last.role == role) out[out.lastIndex] = last.copy(content = last.content + "\n\n" + content)
            else out.add(PromptMessage(role, content))
        }
        if (out.isEmpty() || out.first().role != Role.USER) out.add(0, PromptMessage(Role.USER, DEFAULT_NEW_CHAT))
        if (prompt.prefill.isNotBlank()) {
            val last = out.last()
            if (last.role == Role.ASSISTANT) out[out.lastIndex] = last.copy(content = last.content + "\n\n" + prompt.prefill)
            else out.add(PromptMessage(Role.ASSISTANT, prompt.prefill))
        }
        return sys.toString() to out
    }

    /** Parses one SSE data payload. */
    fun parseStreamData(kind: ApiKind, data: String): StreamDelta? {
        if (data == "[DONE]") return StreamDelta(done = true)
        val o = runCatching { Json.parseObj(data) }.getOrNull() ?: return null
        o.obj("error")?.let { throw LlmException(0, it.str("message", data)) }
        return when (kind) {
            ApiKind.ANTHROPIC -> when (o.str("type")) {
                "content_block_delta" -> {
                    val d = o.obj("delta") ?: return null
                    when (d.str("type")) {
                        "text_delta" -> StreamDelta(text = d.str("text"))
                        "thinking_delta" -> StreamDelta(reasoning = d.str("thinking"))
                        else -> null
                    }
                }
                "message_stop" -> StreamDelta(done = true)
                "error" -> throw LlmException(0, o.obj("error")?.str("message") ?: data)
                else -> null
            }
            ApiKind.OPENAI_CHAT -> {
                val ch = o.objList("choices").firstOrNull() ?: return null
                val d = ch.obj("delta") ?: ch.obj("message") ?: return null
                StreamDelta(
                    text = d.str("content"),
                    reasoning = d.str("reasoning").ifEmpty { d.str("reasoning_content") },
                )
            }
            ApiKind.TEXT_COMPLETION -> {
                val ch = o.objList("choices").firstOrNull()
                StreamDelta(text = ch?.str("text") ?: o.str("content"))
            }
        }
    }

    fun parseFull(kind: ApiKind, body: String): Pair<String, String> {
        val o = Json.parseObj(body)
        o.obj("error")?.let { throw LlmException(0, it.str("message", body)) }
        return when (kind) {
            ApiKind.ANTHROPIC -> {
                val blocks = o.objList("content")
                blocks.filter { it.str("type") == "text" }.joinToString("") { it.str("text") } to
                    blocks.filter { it.str("type") == "thinking" }.joinToString("") { it.str("thinking") }
            }
            ApiKind.OPENAI_CHAT -> {
                val m = o.objList("choices").firstOrNull()?.obj("message") ?: emptyMap()
                m.str("content") to m.str("reasoning").ifEmpty { m.str("reasoning_content") }
            }
            ApiKind.TEXT_COMPLETION -> (o.objList("choices").firstOrNull()?.str("text") ?: o.str("content")) to ""
        }
    }

    fun extractError(body: String): String = runCatching {
        val o = Json.parseObj(body)
        o.obj("error")?.let { e -> e.str("message").ifEmpty { Json.stringify(e) } }
            ?: o.str("message").ifEmpty { o.str("detail") }.ifEmpty { body }
    }.getOrDefault(body).take(600)

    private fun open(spec: HttpSpec, method: String, call: LlmCall?): HttpURLConnection {
        val conn = URL(spec.url).openConnection() as HttpURLConnection
        call?.conn = conn
        conn.requestMethod = method
        conn.connectTimeout = 30_000
        conn.readTimeout = 300_000
        conn.useCaches = false
        for ((k, v) in spec.headers) conn.setRequestProperty(k, v)
        if (spec.body != null) {
            conn.doOutput = true
            val bytes = spec.body.toByteArray(Charsets.UTF_8)
            conn.outputStream.use { it.write(bytes) }
        }
        return conn
    }

    /**
     * Runs a generation. [onDelta] is called from the calling (background) thread
     * with each streamed text / reasoning fragment.
     */
    fun generate(
        c: Connection,
        preset: Preset,
        prompt: BuiltPrompt,
        call: LlmCall,
        onDelta: (text: String, reasoning: String) -> Unit,
    ): GenOutput {
        val stream = preset.streaming
        val spec = buildRequest(c, preset, prompt, stream)
        val text = StringBuilder()
        val reasoning = StringBuilder()
        var conn: HttpURLConnection? = null
        try {
            if (call.cancelled) return GenOutput("", "", true)
            conn = open(spec, "POST", call)
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                throw LlmException(code, "HTTP $code: " + extractError(err).ifBlank { conn.responseMessage ?: "" })
            }
            val ctype = conn.contentType ?: ""
            if (!stream || !ctype.contains("event-stream")) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                if (stream && body.trimStart().startsWith("data:")) {
                    // Some servers stream without the right content type.
                    for (line in body.lineSequence()) handleLine(c.kind, line, text, reasoning, onDelta)
                } else {
                    val (t, r) = parseFull(c.kind, body)
                    text.append(t); reasoning.append(r)
                    onDelta(t, r)
                }
                return GenOutput(text.toString(), reasoning.toString(), false)
            }
            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8), 8192).use { r ->
                while (!call.cancelled) {
                    val line = r.readLine() ?: break
                    if (handleLine(c.kind, line, text, reasoning, onDelta)) break
                }
            }
            return GenOutput(text.toString(), reasoning.toString(), call.cancelled)
        } catch (e: IOException) {
            if (call.cancelled) return GenOutput(text.toString(), reasoning.toString(), true)
            throw LlmException(-1, e.message ?: e.javaClass.simpleName)
        } finally {
            runCatching { conn?.disconnect() }
            call.conn = null
        }
    }

    /** @return true when the stream signalled completion. */
    private fun handleLine(
        kind: ApiKind, line: String, text: StringBuilder, reasoning: StringBuilder,
        onDelta: (String, String) -> Unit,
    ): Boolean {
        if (!line.startsWith("data:")) return false
        val data = line.substring(5).trim()
        if (data.isEmpty()) return false
        val d = parseStreamData(kind, data) ?: return false
        if (d.text.isNotEmpty() || d.reasoning.isNotEmpty()) {
            text.append(d.text)
            reasoning.append(d.reasoning)
            onDelta(d.text, d.reasoning)
        }
        return d.done
    }

    /** Fetches model ids from the provider's /models endpoint. */
    fun listModels(c: Connection): List<String> {
        val headers = LinkedHashMap<String, String>()
        headers["Accept"] = "application/json"
        if (c.kind == ApiKind.ANTHROPIC) {
            if (c.apiKey.isNotBlank()) headers["x-api-key"] = c.apiKey.trim()
            headers["anthropic-version"] = "2023-06-01"
        } else if (c.apiKey.isNotBlank()) headers["Authorization"] = "Bearer " + c.apiKey.trim()
        val conn = open(HttpSpec(base(c) + "/models?limit=1000", headers, null), "GET", null)
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                throw LlmException(code, "HTTP $code: " + extractError(err))
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val parsed = Json.parse(body)
            @Suppress("UNCHECKED_CAST")
            val list: List<Any?> = when (parsed) {
                is List<*> -> parsed as List<Any?>
                is Map<*, *> -> (parsed as JObj).list("data").ifEmpty { parsed.list("models") }
                else -> emptyList()
            }
            return list.mapNotNull { item ->
                when (item) {
                    is String -> item
                    is Map<*, *> -> {
                        @Suppress("UNCHECKED_CAST")
                        val m = item as JObj
                        m.str("id").ifEmpty { m.str("name") }.ifEmpty { null }
                    }
                    else -> null
                }
            }.distinct().sorted()
        } finally {
            conn.disconnect()
        }
    }
}
