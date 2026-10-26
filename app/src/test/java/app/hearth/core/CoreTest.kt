package app.hearth.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.Date
import java.util.zip.CRC32
import kotlin.random.Random

class CoreTest {

    // ------------------------------------------------------------ JSON
    @Test fun jsonRoundTrip() {
        val src = """{"a":1,"b":[true,false,null,1.5,-2e3],"c":"q\"\\\né😀","d":{}}"""
        val v = Json.parseObj(src)
        assertEquals(1L, v["a"])
        val b = v.list("b")
        assertEquals(listOf(true, false, null, 1.5, -2000.0), b)
        assertEquals("q\"\\\né😀", v.str("c"))
        // Whole doubles are written compactly ("-2000") and read back as Long; accessors treat both as numbers.
        val again = Json.parseObj(Json.stringify(v))
        assertEquals(-2000.0, (again.list("b")[4] as Number).toDouble(), 0.0)
        assertEquals(Json.stringify(v), Json.stringify(again))
        assertEquals(Json.stringify(v), Json.stringify(Json.parseObj(Json.stringify(v, pretty = true))))
    }

    @Test fun jsonRejectsGarbage() {
        val bad = listOf("{", "[1,]", "{\"a\" 1}", "tru", "\"abc", "{} x")
        for (s in bad) assertTrue("should fail: $s", runCatching { Json.parse(s) }.isFailure)
    }

    // ------------------------------------------------------------ Cards
    private fun minimalPng(text: Map<String, String> = emptyMap()): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
        fun chunk(type: String, data: ByteArray) {
            val t = type.toByteArray(Charsets.ISO_8859_1)
            fun int(v: Int) { out.write((v ushr 24) and 255); out.write((v ushr 16) and 255); out.write((v ushr 8) and 255); out.write(v and 255) }
            int(data.size); out.write(t); out.write(data)
            val crc = CRC32(); crc.update(t); crc.update(data); int(crc.value.toInt())
        }
        chunk("IHDR", byteArrayOf(0, 0, 0, 1, 0, 0, 0, 1, 8, 6, 0, 0, 0))
        for ((k, v) in text) chunk("tEXt", "$k\u0000$v".toByteArray(Charsets.ISO_8859_1))
        chunk("IDAT", byteArrayOf(120, -100, 99, 0, 1, 0, 0, 5, 0, 1))
        chunk("IEND", ByteArray(0))
        return out.toByteArray()
    }

    @Test fun cardPngRoundTrip() {
        val card = jsonOf(
            "spec" to "chara_card_v2", "spec_version" to "2.0",
            "data" to jsonOf(
                "name" to "Seraphina", "description" to "A guardian of the forest.",
                "first_mes" to "Hello {{user}}", "alternate_greetings" to listOf("Hi again"),
                "mes_example" to "<START>\n{{user}}: hi\n{{char}}: hello",
                "extensions" to jsonOf("world" to "Forest", "custom_field" to 42),
                "character_book" to jsonOf("entries" to listOf(
                    jsonOf("keys" to listOf("glade"), "content" to "The glade is sacred.", "enabled" to true,
                        "insertion_order" to 10, "id" to 0, "position" to "after_char"),
                )),
            ),
        )
        val png = Cards.writeToPng(minimalPng(), card)
        assertTrue(Cards.isPng(png))
        val back = Cards.extractJsonFromPng(png)
        val ch = Cards.parse("sera", back)
        assertEquals("Seraphina", ch.name)
        assertEquals(listOf("Hi again"), ch.alternateGreetings)
        assertEquals("Forest", ch.linkedWorld)
        assertEquals(1, ch.book!!.entries.size)
        assertEquals(WiPos.AFTER_CHAR, ch.book!!.entries[0].position)

        // Edit + re-export keeps unknown extension fields
        val edited = ch.copy(description = "Changed")
        val png2 = Cards.writeToPng(png, edited.toCardJson())
        val chunks = Cards.readTextChunks(png2)
        assertEquals(1, chunks.keys.count { it == "chara" })
        val ch2 = Cards.parse("sera", Cards.extractJsonFromPng(png2))
        assertEquals("Changed", ch2.description)
        assertEquals(42L, ch2.raw.obj("data")!!.obj("extensions")!!["custom_field"])
    }

    @Test fun cardV3Preferred() {
        val v2 = Base64.getEncoder().encodeToString("""{"spec":"chara_card_v2","data":{"name":"Old"}}""".toByteArray())
        val v3 = Base64.getEncoder().encodeToString("""{"spec":"chara_card_v3","data":{"name":"New"}}""".toByteArray())
        val png = minimalPng(linkedMapOf("chara" to v2, "ccv3" to v3))
        assertEquals("New", Cards.parse("x", Cards.extractJsonFromPng(png)).name)
        assertEquals("Old", Cards.parse("x", Cards.extractJsonFromPng(minimalPng(mapOf("chara" to v2)))).name)
        // Re-export strips both old chunks and writes a single fresh one
        val out = Cards.writeToPng(png, Json.parseObj("""{"spec":"chara_card_v2","data":{"name":"Edited"}}"""))
        assertEquals(setOf("chara"), Cards.readTextChunks(out).keys)
        assertEquals("Edited", Cards.parse("x", Cards.extractJsonFromPng(out)).name)
    }

    @Test fun legacyV1Card() {
        val ch = Cards.parse("x", Json.parseObj("""{"char_name":"Bob","char_persona":"grumpy","char_greeting":"What."}"""))
        assertEquals("Bob", ch.name)
        assertEquals("grumpy", ch.description)
        assertEquals("What.", ch.firstMes)
    }

    // ------------------------------------------------------------ Macros
    @Test fun macros() {
        val m = Macros(mapOf("user" to "Ann", "char" to "Bob"), Random(1), { Date(0) })
        assertEquals("Ann meets Bob", m.expand("{{user}} meets {{char}}"))
        assertEquals("Ann meets Bob", m.expand("<USER> meets <BOT>"))
        assertEquals("ab", m.expand("a {{trim}} b"))
        assertEquals("x", m.expand("{{// hidden}}x"))
        val r = m.expand("{{random::red::blue}}")
        assertTrue(r == "red" || r == "blue")
        val roll = m.expand("{{roll:2d6}}").toInt()
        assertTrue(roll in 2..12)
        assertEquals("{{unknown}}", m.expand("{{unknown}}"))
        assertEquals("Hi Bob", m.expand("Hi {{CHAR}}"))
    }

    // ------------------------------------------------------------ World info
    private val tc = TokenCounter()

    private fun scan(entries: List<LoreEntry>, history: List<String>, s: WiSettings = WiSettings()): WiResult =
        WorldInfoScanner(s, tc, Random(7)).scan(listOf(Lorebook("b", "b", entries)), history.reversed(), 10_000)

    @Test fun wiBasicAndSecondary() {
        val entries = listOf(
            LoreEntry(0, keys = listOf("dragon"), content = "Dragons breathe fire."),
            LoreEntry(1, keys = listOf("castle"), secondaryKeys = listOf("night"), content = "The castle is haunted at night."),
            LoreEntry(2, keys = listOf("castle"), secondaryKeys = listOf("night"), selectiveLogic = WiLogic.NOT_ANY, content = "Daytime castle."),
            LoreEntry(3, constant = true, content = "Always on."),
            LoreEntry(4, keys = listOf("/gr[ae]y wolf/i"), content = "Wolves.", position = WiPos.AT_DEPTH, depth = 2),
            LoreEntry(5, keys = listOf("cat"), content = "Cats.", matchWholeWords = true),
        )
        val r = scan(entries, listOf("We saw a DRAGON near the castle", "It was a Grey Wolf? concatenate"))
        assertTrue(r.before.contains("Dragons breathe fire."))
        assertTrue(r.before.contains("Daytime castle."))
        assertFalse(r.before.contains("haunted"))
        assertTrue(r.before.contains("Always on."))
        assertEquals(1, r.depth.size)
        assertEquals(2, r.depth[0].depth)
        assertFalse("whole-word should not match 'concatenate'", r.before.contains("Cats."))
    }

    @Test fun wiScanDepthAndRecursion() {
        val entries = listOf(
            LoreEntry(0, keys = listOf("apple"), content = "Apples grow in the orchard."),
            LoreEntry(1, keys = listOf("orchard"), content = "The orchard belongs to Mira."),
            LoreEntry(2, keys = listOf("Mira"), content = "Mira is a witch.", excludeRecursion = true),
            LoreEntry(3, keys = listOf("ancient"), content = "Old stuff."),
        )
        val r = scan(entries, listOf("ancient history", "filler", "I want an apple"), WiSettings(scanDepth = 2))
        assertTrue(r.before.contains("Apples"))
        assertTrue(r.before.contains("belongs to Mira"))
        assertFalse(r.before.contains("witch"))
        assertFalse("outside scan depth", r.before.contains("Old stuff"))
        val nr = scan(entries, listOf("I want an apple"), WiSettings(recursive = false))
        assertFalse(nr.before.contains("belongs to Mira"))
    }

    @Test fun wiBudget() {
        val big = "x".repeat(350)
        val entries = (0 until 10).map { LoreEntry(it, constant = true, content = big + it, order = it) }
        val r = WorldInfoScanner(WiSettings(), tc, Random(1)).scan(listOf(Lorebook("b", "b", entries)), emptyList(), 250)
        // each ~101 tokens -> only 2 fit; the highest-order ones win
        assertEquals(2, r.activatedUids.size)
        assertTrue(r.before.contains(big + "9"))
        assertTrue(r.before.contains(big + "8"))
    }

    @Test fun lorebookStFormatRoundTrip() {
        val book = Lorebook("w", "World", listOf(LoreEntry(3, keys = listOf("a", "b"), content = "c", position = 4, depth = 2)))
        val back = Lorebook.fromJson("w", Json.parseObj(Json.stringify(book.toStJson())))
        assertEquals(book.entries[0].copy(extra = emptyMap()), back.entries[0].copy(extra = emptyMap()))
    }

    // ------------------------------------------------------------ Prompt builder
    private val seraphina = Character(
        id = "s", name = "Seraphina", description = "{{char}} guards the forest.",
        personality = "kind", scenario = "{{user}} is lost.",
        mesExample = "<START>\n{{user}}: Who are you?\n{{char}}: I am {{char}}.",
        postHistoryInstructions = "Stay in character. {{original}}",
        depthPrompt = DepthPrompt("Remember the glade.", 1),
        book = Lorebook("card", "card", listOf(LoreEntry(0, keys = listOf("glade"), content = "The glade heals."))),
    )

    private fun history(n: Int) = (0 until n).map {
        ChatMessage(isUser = it % 2 == 0, name = if (it % 2 == 0) "Ann" else "Seraphina", text = "message $it about the glade")
    }

    @Test fun chatPromptOrder() {
        val p = PromptBuilder.build(
            PromptInput(seraphina, Persona("Ann", "A traveler."), Preset("t").withPrompts(postHistory = "Be vivid."),
                ApiKind.OPENAI_CHAT, history(4), books = listOfNotNull(seraphina.book)),
        )
        val m = p.messages
        assertEquals(Role.SYSTEM, m[0].role)
        assertTrue(m[0].content.contains("Seraphina's next reply"))
        assertTrue(m.any { it.content.contains("The glade heals.") })
        assertTrue(m.any { it.content == "Seraphina guards the forest." })
        assertTrue(m.any { it.content == "Seraphina's personality: kind" })
        assertTrue(m.any { it.content == "Scenario: Ann is lost." })
        assertTrue(m.any { it.content.startsWith("[Example dialogue]") && it.content.contains("I am Seraphina.") })
        assertEquals("Stay in character. Be vivid.", m.last().content)
        // depth 1 injection sits right before the last history message
        val idx = m.indexOfFirst { it.content == "Remember the glade." }
        assertEquals("message 3 about the glade", m[idx + 1].content)
        assertEquals(4, p.historyIncluded)
    }

    @Test fun contextTrimming() {
        val small = Preset("t", contextSize = 800, maxTokens = 300, newChatPrompt = "").withPrompts(main = "Main.")
        val p = PromptBuilder.build(
            PromptInput(seraphina.copy(mesExample = "", depthPrompt = null), Persona("Ann"), small, ApiKind.OPENAI_CHAT,
                (0 until 200).map { ChatMessage(it % 2 == 0, "x", "message number $it " + "word ".repeat(20)) }),
        )
        assertTrue(p.historyIncluded in 5..30)
        assertTrue(p.tokenEstimate <= 500)
        assertTrue(p.messages.last().content.contains("message number 199") ||
            p.messages.dropLast(1).last().content.contains("message number 199"))
    }

    @Test fun textCompletionChatML() {
        val p = PromptBuilder.build(
            PromptInput(seraphina.copy(postHistoryInstructions = "", depthPrompt = null, mesExample = ""),
                Persona("Ann"), Preset("t", instruct = "ChatML"), ApiKind.TEXT_COMPLETION, history(2)),
        )
        val t = p.text!!
        assertTrue(t.startsWith("<|im_start|>system\n"))
        assertTrue(t.contains("<|im_start|>user\nAnn: message 0 about the glade<|im_end|>\n"))
        assertTrue(t.endsWith("<|im_start|>assistant\nSeraphina:"))
        assertTrue(p.stop.contains("<|im_end|>"))
        assertTrue(p.stop.contains("\nAnn:"))
    }

    @Test fun continueModes() {
        val h = history(3) + ChatMessage(false, "Seraphina", "She smiled and")
        val text = PromptBuilder.build(PromptInput(seraphina.copy(depthPrompt = null), Persona("Ann"),
            Preset("t", instruct = "ChatML"), ApiKind.TEXT_COMPLETION, h, mode = GenMode.CONTINUE)).text!!
        assertTrue(text.endsWith("<|im_start|>assistant\nSeraphina: She smiled and"))

        val claude = PromptBuilder.build(PromptInput(seraphina.copy(depthPrompt = null), Persona("Ann"),
            Preset("t"), ApiKind.ANTHROPIC, h, mode = GenMode.CONTINUE))
        assertEquals("She smiled and", claude.prefill)
        val (sys, msgs) = LlmClient.toAnthropic(claude)
        assertTrue(sys.contains("Seraphina's next reply"))
        assertEquals(Role.USER, msgs.first().role)
        assertEquals(Role.ASSISTANT, msgs.last().role)
        assertTrue(msgs.last().content.endsWith("She smiled and"))
        for (i in 1 until msgs.size) assertTrue("roles must alternate", msgs[i].role != msgs[i - 1].role)

        val oai = PromptBuilder.build(PromptInput(seraphina.copy(depthPrompt = null), Persona("Ann"),
            Preset("t"), ApiKind.OPENAI_CHAT, h, mode = GenMode.CONTINUE))
        assertEquals(DEFAULT_CONTINUE_NUDGE, oai.messages.last().content)
    }

    // ------------------------------------------------------------ Requests & streaming
    @Test fun requestBodies() {
        val prompt = PromptBuilder.build(PromptInput(seraphina, Persona("Ann"), Preset("t", topK = 40, minP = 0.05),
            ApiKind.OPENAI_CHAT, history(2)))
        val oai = Json.parseObj(LlmClient.buildRequest(
            Connection("c", "c", ApiKind.OPENAI_CHAT, "https://x/v1/", "k", "m", extraSamplers = false),
            Preset("t", topK = 40, minP = 0.05), prompt, true).body!!)
        assertFalse(oai.containsKey("top_k"))
        val spec = LlmClient.buildRequest(Connection("c", "c", ApiKind.OPENAI_CHAT, "https://x/v1/", "k", "m"),
            Preset("t", topK = 40, minP = 0.05), prompt, true)
        assertEquals("https://x/v1/chat/completions", spec.url)
        assertEquals("Bearer k", spec.headers["Authorization"])
        val body = Json.parseObj(spec.body!!)
        assertEquals(40L, body["top_k"])
        assertEquals(true, body["stream"])

        val ant = LlmClient.buildRequest(Connection("a", "a", ApiKind.ANTHROPIC, "https://api.anthropic.com/v1", "k", "claude"),
            Preset("t", temperature = 1.4), prompt, false)
        assertEquals("https://api.anthropic.com/v1/messages", ant.url)
        val ab = Json.parseObj(ant.body!!)
        assertEquals(1L, ab["temperature"])
        assertTrue(ab.str("system").isNotEmpty())
        assertFalse(ab.containsKey("top_p"))
    }

    @Test fun streamParsing() {
        val d1 = LlmClient.parseStreamData(ApiKind.OPENAI_CHAT, """{"choices":[{"delta":{"content":"Hel"}}]}""")!!
        assertEquals("Hel", d1.text)
        val d2 = LlmClient.parseStreamData(ApiKind.OPENAI_CHAT, """{"choices":[{"delta":{"reasoning":"hmm"}}]}""")!!
        assertEquals("hmm", d2.reasoning)
        assertTrue(LlmClient.parseStreamData(ApiKind.OPENAI_CHAT, "[DONE]")!!.done)
        val a = LlmClient.parseStreamData(ApiKind.ANTHROPIC,
            """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hi"}}""")!!
        assertEquals("Hi", a.text)
        assertTrue(LlmClient.parseStreamData(ApiKind.ANTHROPIC, """{"type":"message_stop"}""")!!.done)
        val t = LlmClient.parseStreamData(ApiKind.TEXT_COMPLETION, """{"choices":[{"text":" there"}]}""")!!
        assertEquals(" there", t.text)
        assertTrue(runCatching {
            LlmClient.parseStreamData(ApiKind.OPENAI_CHAT, """{"error":{"message":"rate limited"}}""")
        }.exceptionOrNull()?.message == "rate limited")
    }

    /** Minimal HTTP/1.1 server on a raw socket (com.sun.net.httpserver isn't visible to Android unit tests). */
    private class MockServer(val handler: (path: String, body: String, out: java.io.OutputStream) -> Unit) {
        private val socket = java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        val port get() = socket.localPort
        private val thread = Thread {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                client.use { c ->
                    val input = java.io.BufferedInputStream(c.getInputStream())
                    fun line(): String {
                        val sb = StringBuilder()
                        while (true) {
                            val b = input.read()
                            if (b < 0 || b == '\n'.code) break
                            if (b != '\r'.code) sb.append(b.toChar())
                        }
                        return sb.toString()
                    }
                    val path = line().split(' ').getOrElse(1) { "/" }.substringBefore('?')
                    var len = 0
                    while (true) {
                        val h = line()
                        if (h.isEmpty()) break
                        if (h.startsWith("Content-Length:", true)) len = h.substringAfter(':').trim().toInt()
                    }
                    val body = ByteArray(len)
                    var read = 0
                    while (read < len) { val r = input.read(body, read, len - read); if (r < 0) break; read += r }
                    handler(path, String(body, Charsets.UTF_8), c.getOutputStream())
                    c.getOutputStream().flush()
                }
            }
        }.apply { isDaemon = true; start() }

        fun stop() = socket.close()
    }

    private fun respond(out: java.io.OutputStream, code: Int, type: String, body: String) {
        val b = body.toByteArray()
        out.write("HTTP/1.1 $code X\r\nContent-Type: $type\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(b)
    }

    @Test fun streamingEndToEnd() {
        var seenBody = ""
        val server = MockServer { path, body, out ->
            when (path) {
                "/v1/chat/completions" -> {
                    seenBody = body
                    out.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n".toByteArray())
                    for (piece in listOf("Hel", "lo", " wörld")) {
                        out.write("data: {\"choices\":[{\"delta\":{\"content\":\"$piece\"}}]}\n\n".toByteArray()); out.flush()
                    }
                    out.write("data: [DONE]\n\n".toByteArray())
                }
                "/v1/models" -> respond(out, 200, "application/json", """{"data":[{"id":"b-model"},{"id":"a-model"}]}""")
                else -> respond(out, 401, "application/json", """{"error":{"message":"Invalid API key"}}""")
            }
        }
        try {
            val base = "http://127.0.0.1:${server.port}"
            val conn = Connection("c", "c", ApiKind.OPENAI_CHAT, "$base/v1", "", "m")
            val prompt = PromptBuilder.build(PromptInput(seraphina, Persona("Ann"), Preset("t"), ApiKind.OPENAI_CHAT, history(2)))
            val deltas = ArrayList<String>()
            val out = LlmClient.generate(conn, Preset("t"), prompt, LlmCall()) { t, _ -> deltas.add(t) }
            assertEquals("Hello wörld", out.text)
            assertEquals(listOf("Hel", "lo", " wörld"), deltas)
            assertTrue(seenBody.contains("\"stream\":true"))
            assertEquals(listOf("a-model", "b-model"), LlmClient.listModels(conn))
            val err = runCatching {
                LlmClient.generate(conn.copy(baseUrl = "$base/bad"), Preset("t"), prompt, LlmCall()) { _, _ -> }
            }.exceptionOrNull()
            assertEquals("HTTP 401: Invalid API key", err?.message)
        } finally { server.stop() }
    }

    // ------------------------------------------------------------ Chat files
    @Test fun chatJsonlRoundTrip() {
        val f = File.createTempFile("chat", ".jsonl")
        try {
            val msgs = listOf(
                ChatMessage(false, "Sera", "Hello", swipes = listOf("Hello", "Hi"), swipeId = 0),
                ChatMessage(true, "Ann", "Line1\nLine2 \"quoted\""),
                ChatMessage(false, "Sera", "Thinking done", reasoning = "pondered",
                    extra = jsonOf("gen_started" to "x")),
            )
            ChatStore.write(f, ChatStore.newHeader("Ann", "Sera"), msgs)
            val back = ChatStore.read(f)
            assertEquals("Sera", back.header.str("character_name"))
            assertEquals(3, back.messages.size)
            assertEquals(listOf("Hello", "Hi"), back.messages[0].swipes)
            assertEquals("Line1\nLine2 \"quoted\"", back.messages[1].text)
            assertEquals("pondered", back.messages[2].reasoning)
            assertEquals("x", back.messages[2].extra.str("gen_started"))
            val info = ChatStore.info(f)
            assertEquals(3, info.messageCount)
            assertEquals("Thinking done", info.preview)
        } finally { f.delete() }
    }

    @Test fun stPresetImport() {
        val o = Json.parseObj("""{"temperature":0.8,"top_k":50,"openai_max_context":32000,"openai_max_tokens":600,
            "prompts":[{"identifier":"main","content":"MAIN"},{"identifier":"jailbreak","content":"JB"}],"assistant_prefill":"PF"}""")
        val p = Preset.fromSillyTavern("imp", o)
        assertEquals(0.8, p.temperature, 0.0)
        assertEquals(50, p.topK)
        assertEquals(32000, p.contextSize)
        assertEquals("MAIN", p.module("main")!!.content)
        assertEquals("JB", p.module("jailbreak")!!.content)
        assertEquals("PF", p.prefill)
        assertEquals(p, Preset.fromJson(p.toJson()))
    }

    @Test fun settingsRoundTrip() {
        val s = AppSettings(persona = Persona("Zed", "tall"), globalLorebooks = listOf("a"), fontScale = 1.25f)
        assertEquals(s, AppSettings.fromJson(Json.parseObj(Json.stringify(s.toJson()))))
    }

    // ------------------------------------------------------------ Prompt modules (ST prompt manager)
    @Test fun stModularPreset() {
        val o = Json.parseObj("""{
          "prompts":[
            {"identifier":"main","name":"Main","role":"system","content":"MAIN {{setvar::mood::grim}}"},
            {"identifier":"rules","name":"Rules","role":"system","content":"RULES mood={{getvar::mood}}"},
            {"identifier":"style","name":"Style","role":"user","content":"STYLE"},
            {"identifier":"deep","name":"Deep","role":"system","content":"DEEP NOTE","injection_position":1,"injection_depth":0},
            {"identifier":"unused","name":"Unused","content":"NOPE"},
            {"identifier":"jailbreak","name":"JB","role":"system","content":"JB"},
            {"identifier":"chatHistory","name":"Chat History","system_prompt":true,"marker":true}
          ],
          "prompt_order":[{"character_id":100001,"order":[
            {"identifier":"main","enabled":true},{"identifier":"rules","enabled":true},
            {"identifier":"style","enabled":false},{"identifier":"charDescription","enabled":true},
            {"identifier":"chatHistory","enabled":true},{"identifier":"deep","enabled":true},
            {"identifier":"jailbreak","enabled":true}]}]}""")
        val p = Preset.fromSillyTavern("Sola", o)
        assertEquals(listOf("main", "rules", "style", "charDescription", "chatHistory", "deep", "jailbreak", "unused"),
            p.modules.map { it.identifier })
        assertFalse(p.module("style")!!.enabled)
        assertFalse(p.module("unused")!!.enabled)
        assertTrue(p.module("deep")!!.inChat)

        val built = PromptBuilder.build(PromptInput(seraphina.copy(depthPrompt = null, postHistoryInstructions = ""),
            Persona("Ann"), p.copy(newChatPrompt = ""), ApiKind.OPENAI_CHAT, history(2)))
        val c = built.messages.map { it.content }
        assertEquals("MAIN", c[0])
        assertEquals("RULES mood=grim", c[1])
        assertFalse(c.contains("STYLE"))
        assertEquals("Seraphina guards the forest.", c[2])
        assertEquals("message 0 about the glade", c[3])
        assertEquals("DEEP NOTE", c[5])       // depth 0 = after the last message
        assertEquals("JB", c.last())
        assertFalse(c.any { it.contains("NOPE") })
        assertEquals(p, Preset.fromJson(p.toJson()))
    }

    @Test fun disabledHistoryAndSquash() {
        val p = Preset("t", newChatPrompt = "", squashSystem = true).let { pr ->
            pr.copy(modules = pr.modules.map { if (it.identifier == PromptModule.HISTORY) it.copy(enabled = false) else it })
        }
        val built = PromptBuilder.build(PromptInput(seraphina.copy(depthPrompt = null, mesExample = ""), Persona("Ann"), p,
            ApiKind.OPENAI_CHAT, history(4)))
        assertEquals(0, built.historyIncluded)
        assertEquals(1, built.messages.size)
        assertEquals(Role.SYSTEM, built.messages[0].role)
    }

    @Test fun legacyPresetMigrates() {
        val p = Preset.fromJson(Json.parseObj("""{"name":"Old","mainPrompt":"OLD MAIN","postHistory":"OLD JB"}"""))
        assertEquals("OLD MAIN", p.module("main")!!.content)
        assertEquals("OLD JB", p.module("jailbreak")!!.content)
    }

    // ------------------------------------------------------------ Regex scripts
    private fun script(find: String, replace: String, vararg place: Int, md: Boolean = false, po: Boolean = false,
                       trim: List<String> = emptyList(), min: Int? = null, max: Int? = null) =
        RegexScript("id$find", "s", find, replace, trim, place.toList(), markdownOnly = md, promptOnly = po,
            minDepth = min, maxDepth = max)

    @Test fun regexBasics() {
        val ex = Macros(mapOf("char" to "Sera", "user" to "Ann"))::expand
        val s1 = script("/{{char}}/g", "[{{char}}]", RegexPlacement.AI_OUTPUT)
        assertEquals("[Sera] and {{char}}", RegexEngine.run(s1.copy(substituteMacros = 0), "{{char}} and {{char}}".replaceFirst("{{char}}", "{{char}}"), ex)
            .let { RegexEngine.run(script("/\\{\\{char\\}\\}/", "[{{char}}]", 2), "{{char}} and {{char}}", ex) })
        assertEquals("[Sera] and [Sera]", RegexEngine.run(s1, "{{char}} and {{char}}", ex))
        assertEquals("<b>hi</b> x", RegexEngine.run(script("/\\*\\*(.+?)\\*\\*/", "<b>$1</b>", 2), "**hi** x", ex))
        assertEquals("A-a", RegexEngine.run(script("/a/", "A", 2), "a-a", ex))
        assertEquals("A-A", RegexEngine.run(script("/a/gi", "A", 2), "a-A", ex))
        assertEquals("<x>", RegexEngine.run(script("/<(?<tag>\\w+)>[^]*?<\\/\\k<tag>>/s", "<$<tag>>", 2), "<x>\nbody\n</x>", ex))
        assertEquals("[hello]", RegexEngine.run(script("/<status>([\\s\\S]*?)<\\/status>/", "[{{match}}]", 2,
            trim = listOf("<status>", "</status>")), "<status>hello</status>", ex))
        // Sera's name substituted into the pattern itself
        assertEquals("X said", RegexEngine.run(script("/{{char}}/", "X", 2).copy(substituteMacros = 1), "Sera said", ex))
        assertEquals("X said", RegexEngine.run(script("/{{char}}/", "X", 2).copy(substituteMacros = 2), "Sera said", ex))
        // invalid pattern leaves text untouched
        assertEquals("abc", RegexEngine.run(script("/(/", "X", 2), "abc", ex))
    }

    @Test fun regexTargetsAndDepth() {
        val scripts = listOf(
            script("/a/g", "STORE", RegexPlacement.AI_OUTPUT),
            script("/b/g", "DISP", RegexPlacement.AI_OUTPUT, md = true),
            script("/c/g", "PROMPT", RegexPlacement.AI_OUTPUT, po = true),
            script("/d/g", "USER", RegexPlacement.USER_INPUT),
            script("/e/g", "OLD", RegexPlacement.AI_OUTPUT, md = true, min = 2),
        )
        val t = "abcde"
        assertEquals("STOREbcde", RegexEngine.apply(scripts, t, RegexPlacement.AI_OUTPUT, RegexTarget.STORE))
        assertEquals("aDISPcde", RegexEngine.apply(scripts, t, RegexPlacement.AI_OUTPUT, RegexTarget.DISPLAY, 0))
        assertEquals("aDISPcdOLD", RegexEngine.apply(scripts, t, RegexPlacement.AI_OUTPUT, RegexTarget.DISPLAY, 3))
        assertEquals("abPROMPTde", RegexEngine.apply(scripts, t, RegexPlacement.AI_OUTPUT, RegexTarget.PROMPT, 0))
        assertEquals("abcUSERe", RegexEngine.apply(scripts, t, RegexPlacement.USER_INPUT, RegexTarget.STORE))

        // Prompt-only scripts reach the outgoing prompt but not stored text
        val built = PromptBuilder.build(PromptInput(seraphina.copy(depthPrompt = null), Persona("Ann"), Preset("t"),
            ApiKind.OPENAI_CHAT, listOf(ChatMessage(false, "Seraphina", "cat")), regex = scripts))
        assertTrue(built.messages.any { it.content == "PROMPTat" })
    }

    @Test fun regexImportFormats() {
        val one = RegexScript.parseAny(Json.parse("""{"id":"x","scriptName":"One","findRegex":"/a/","replaceString":"b","placement":[2],"substituteRegex":true}"""))
        assertEquals(1, one.size); assertEquals(1, one[0].substituteMacros)
        val arr = RegexScript.parseAny(Json.parse("""[{"scriptName":"A","findRegex":"/a/"},{"scriptName":"B","findRegex":"/b/"}]"""))
        assertEquals(listOf("A", "B"), arr.map { it.name })
        val preset = RegexScript.parseAny(Json.parse("""{"temperature":1,"extensions":{"regex_scripts":[{"scriptName":"P","findRegex":"/p/"}]}}"""))
        assertEquals("P", preset.single().name)
        val r = RegexScript.fromJson(Json.parseObj(Json.stringify(one[0].toJson())))
        assertEquals(one[0], r)
    }

    @Test fun jsPatternConversion() {
        assertEquals("\\{\\{char\\}\\}", RegexEngine.jsToJava("{{char}}"))
        assertEquals("a{2,3}", RegexEngine.jsToJava("a{2,3}"))
        assertEquals("[\\s\\S]*", RegexEngine.jsToJava("[^]*"))
        assertEquals("[{}]", RegexEngine.jsToJava("[{}]"))
    }

    // ------------------------------------------------------------ Groups
    @Test fun groupPrompt() {
        val bob = Character("bob", "Bob", description = "A blacksmith.")
        val sera = seraphina.copy(depthPrompt = null, mesExample = "")
        val h = listOf(
            ChatMessage(true, "Ann", "Hello both"),
            ChatMessage(false, "Seraphina", "Welcome."),
            ChatMessage(false, "Bob", "Hmph."),
        )
        val chat = PromptBuilder.build(PromptInput(bob, Persona("Ann"), Preset("t"), ApiKind.OPENAI_CHAT, h,
            group = GroupContext(listOf(sera, bob))))
        val c = chat.messages.map { it.content }
        assertTrue(c.contains("Ann: Hello both"))
        assertTrue(c.contains("Seraphina: Welcome."))
        assertEquals("[Write the next reply only as Bob.]", c.last())
        assertTrue(c.contains("A blacksmith."))
        assertFalse(c.any { it.contains("guards the forest") })

        val joined = PromptBuilder.build(PromptInput(bob, Persona("Ann"), Preset("t"), ApiKind.OPENAI_CHAT, h,
            group = GroupContext(listOf(sera, bob), joinCards = true)))
        assertTrue(joined.messages.any { it.content.contains("Seraphina: Seraphina guards the forest.") && it.content.contains("Bob: A blacksmith.") })

        val text = PromptBuilder.build(PromptInput(bob, Persona("Ann"), Preset("t", instruct = "ChatML"), ApiKind.TEXT_COMPLETION, h,
            group = GroupContext(listOf(sera, bob)))).let { it.text!! to it.stop }
        assertTrue(text.first.endsWith("<|im_start|>assistant\nBob:"))
        assertTrue(text.second.contains("\nSeraphina:"))
        assertTrue(text.second.contains("\nAnn:"))
        assertFalse(text.second.contains("\nBob:"))
    }
}
