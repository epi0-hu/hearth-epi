package app.hearth.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

// ---------------------------------------------------------------- Characters

data class DepthPrompt(val prompt: String, val depth: Int = 4, val role: Role = Role.SYSTEM)

/**
 * A character, backed by the raw V2 card JSON so unknown fields survive an
 * edit/export round trip.
 */
data class Character(
    val id: String,
    val name: String,
    val description: String = "",
    val personality: String = "",
    val scenario: String = "",
    val firstMes: String = "",
    val mesExample: String = "",
    val alternateGreetings: List<String> = emptyList(),
    val systemPrompt: String = "",
    val postHistoryInstructions: String = "",
    val creatorNotes: String = "",
    val creator: String = "",
    val characterVersion: String = "",
    val tags: List<String> = emptyList(),
    val book: Lorebook? = null,
    val linkedWorld: String = "",
    val depthPrompt: DepthPrompt? = null,
    val raw: JObj = emptyMap(),
) {
    /** Serializes as a chara_card_v2 object, overlaying edited fields onto the raw card. */
    fun toCardJson(): MJObj {
        @Suppress("UNCHECKED_CAST")
        val root = (deepCopy(raw) as? MJObj) ?: MJObj()
        val data = (root["data"] as? MJObj) ?: MJObj()
        data["name"] = name
        data["description"] = description
        data["personality"] = personality
        data["scenario"] = scenario
        data["first_mes"] = firstMes
        data["mes_example"] = mesExample
        data["alternate_greetings"] = alternateGreetings
        data["system_prompt"] = systemPrompt
        data["post_history_instructions"] = postHistoryInstructions
        data["creator_notes"] = creatorNotes
        data["creator"] = creator
        data["character_version"] = characterVersion
        data["tags"] = tags
        @Suppress("UNCHECKED_CAST")
        val ext = (data["extensions"] as? MJObj) ?: MJObj()
        ext["world"] = linkedWorld
        if (depthPrompt != null && depthPrompt.prompt.isNotBlank()) {
            ext["depth_prompt"] = jsonOf(
                "prompt" to depthPrompt.prompt, "depth" to depthPrompt.depth,
                "role" to depthPrompt.role.wire,
            )
        } else ext.remove("depth_prompt")
        data["extensions"] = ext
        if (book != null) data["character_book"] = book.toV2Json() else data.remove("character_book")
        root["spec"] = "chara_card_v2"
        root["spec_version"] = "2.0"
        root["data"] = data
        // V1 mirror fields, which some tools still read
        root["name"] = name
        root["description"] = description
        root["personality"] = personality
        root["scenario"] = scenario
        root["first_mes"] = firstMes
        root["mes_example"] = mesExample
        return root
    }
}

/** Lightweight row for the character list; avoids loading full cards at startup. */
data class CharacterSummary(
    val id: String,
    val name: String,
    val tagline: String,
    val tags: List<String>,
    val lastActive: Long,
    val favorite: Boolean = false,
)

// ---------------------------------------------------------------- Lorebooks

enum class Role(val wire: Int, val api: String) {
    SYSTEM(0, "system"), USER(1, "user"), ASSISTANT(2, "assistant");

    companion object {
        fun fromWire(i: Int) = entries.firstOrNull { it.wire == i } ?: SYSTEM
        fun fromString(s: String) = entries.firstOrNull { it.api.equals(s, true) } ?: SYSTEM
    }
}

/** ST world-info positions. */
object WiPos {
    const val BEFORE_CHAR = 0
    const val AFTER_CHAR = 1
    const val AN_TOP = 2
    const val AN_BOTTOM = 3
    const val AT_DEPTH = 4
    const val EXAMPLES_TOP = 5
    const val EXAMPLES_BOTTOM = 6
    val labels = listOf(
        "Before character", "After character", "Author's note (top)", "Author's note (bottom)",
        "At depth", "Before examples", "After examples",
    )
}

/** ST selective logic for secondary keys. */
object WiLogic {
    const val AND_ANY = 0
    const val NOT_ALL = 1
    const val NOT_ANY = 2
    const val AND_ALL = 3
    val labels = listOf("AND any", "NOT all", "NOT any", "AND all")
}

data class LoreEntry(
    val uid: Int,
    val keys: List<String> = emptyList(),
    val secondaryKeys: List<String> = emptyList(),
    val content: String = "",
    val comment: String = "",
    val constant: Boolean = false,
    val selective: Boolean = true,
    val selectiveLogic: Int = WiLogic.AND_ANY,
    val order: Int = 100,
    val position: Int = WiPos.BEFORE_CHAR,
    val depth: Int = 4,
    val role: Role = Role.SYSTEM,
    val enabled: Boolean = true,
    val caseSensitive: Boolean? = null,
    val matchWholeWords: Boolean? = null,
    val probability: Int = 100,
    val useProbability: Boolean = true,
    val excludeRecursion: Boolean = false,
    val preventRecursion: Boolean = false,
    val scanDepth: Int? = null,
    val extra: JObj = emptyMap(),
) {
    fun toStJson(): MJObj {
        @Suppress("UNCHECKED_CAST")
        val m = (deepCopy(extra) as? MJObj) ?: MJObj()
        m["uid"] = uid
        m["key"] = keys
        m["keysecondary"] = secondaryKeys
        m["comment"] = comment
        m["content"] = content
        m["constant"] = constant
        m["selective"] = selective
        m["selectiveLogic"] = selectiveLogic
        m["order"] = order
        m["position"] = position
        m["depth"] = depth
        m["role"] = role.wire
        m["disable"] = !enabled
        m["caseSensitive"] = caseSensitive
        m["matchWholeWords"] = matchWholeWords
        m["probability"] = probability
        m["useProbability"] = useProbability
        m["excludeRecursion"] = excludeRecursion
        m["preventRecursion"] = preventRecursion
        m["scanDepth"] = scanDepth
        return m
    }

    fun toV2Json(): MJObj = jsonOf(
        "id" to uid,
        "keys" to keys,
        "secondary_keys" to secondaryKeys,
        "comment" to comment,
        "content" to content,
        "constant" to constant,
        "selective" to selective,
        "insertion_order" to order,
        "enabled" to enabled,
        "position" to if (position == WiPos.AFTER_CHAR) "after_char" else "before_char",
        "case_sensitive" to caseSensitive,
        "extensions" to jsonOf(
            "position" to position, "depth" to depth, "role" to role.wire,
            "selectiveLogic" to selectiveLogic, "probability" to probability,
            "useProbability" to useProbability, "exclude_recursion" to excludeRecursion,
            "prevent_recursion" to preventRecursion, "match_whole_words" to matchWholeWords,
            "scan_depth" to scanDepth, "case_sensitive" to caseSensitive,
        ),
    )

    companion object {
        /** Parses an entry from either ST world format or V2 character_book format. */
        fun fromJson(o: JObj, fallbackUid: Int): LoreEntry {
            val isV2 = o.containsKey("keys") || o.containsKey("insertion_order")
            if (!isV2) {
                return LoreEntry(
                    uid = o.int("uid", fallbackUid),
                    keys = o.strList("key"),
                    secondaryKeys = o.strList("keysecondary"),
                    content = o.str("content"),
                    comment = o.str("comment"),
                    constant = o.bool("constant", false),
                    selective = o.bool("selective", true),
                    selectiveLogic = o.int("selectiveLogic", 0),
                    order = o.int("order", 100),
                    position = o.int("position", 0),
                    depth = o.int("depth", 4),
                    role = Role.fromWire(o.int("role", 0)),
                    enabled = !o.bool("disable", false),
                    caseSensitive = o.boolOrNull("caseSensitive"),
                    matchWholeWords = o.boolOrNull("matchWholeWords"),
                    probability = o.int("probability", 100),
                    useProbability = o.bool("useProbability", true),
                    excludeRecursion = o.bool("excludeRecursion", false),
                    preventRecursion = o.bool("preventRecursion", false),
                    scanDepth = o.intOrNull("scanDepth"),
                    extra = o,
                )
            }
            val ext = o.obj("extensions") ?: emptyMap()
            val posStr = o.str("position")
            val pos = ext.intOrNull("position") ?: if (posStr == "after_char") 1 else 0
            return LoreEntry(
                uid = o.int("id", fallbackUid),
                keys = o.strList("keys"),
                secondaryKeys = o.strList("secondary_keys"),
                content = o.str("content"),
                comment = o.str("comment").ifEmpty { o.str("name") },
                constant = o.bool("constant", false),
                selective = o.bool("selective", true),
                selectiveLogic = ext.int("selectiveLogic", 0),
                order = o.int("insertion_order", 100),
                position = pos,
                depth = ext.int("depth", 4),
                role = Role.fromWire(ext.int("role", 0)),
                enabled = o.bool("enabled", true),
                caseSensitive = o.boolOrNull("case_sensitive") ?: ext.boolOrNull("case_sensitive"),
                matchWholeWords = ext.boolOrNull("match_whole_words"),
                probability = ext.int("probability", 100),
                useProbability = ext.bool("useProbability", true),
                excludeRecursion = ext.bool("exclude_recursion", false),
                preventRecursion = ext.bool("prevent_recursion", false),
                scanDepth = ext.intOrNull("scan_depth"),
            )
        }
    }
}

data class Lorebook(
    val id: String,
    val name: String,
    val entries: List<LoreEntry> = emptyList(),
    val description: String = "",
) {
    /** ST world-info file format: { "entries": { "0": {...}, ... } } */
    fun toStJson(): MJObj {
        val entriesObj = MJObj()
        for (e in entries) entriesObj[e.uid.toString()] = e.toStJson()
        return jsonOf("name" to name, "description" to description, "entries" to entriesObj)
    }

    fun toV2Json(): MJObj = jsonOf(
        "name" to name,
        "description" to description,
        "entries" to entries.map { it.toV2Json() },
        "extensions" to MJObj(),
    )

    fun nextUid(): Int = (entries.maxOfOrNull { it.uid } ?: -1) + 1

    companion object {
        fun fromJson(id: String, o: JObj, fallbackName: String = id): Lorebook {
            val raw = o["entries"]
            val list = when (raw) {
                is Map<*, *> -> raw.values.toList()
                is List<*> -> raw
                else -> emptyList<Any?>()
            }
            val entries = list.mapIndexedNotNull { idx, e ->
                @Suppress("UNCHECKED_CAST")
                (e as? JObj)?.let { LoreEntry.fromJson(it, idx) }
            }
            return Lorebook(
                id = id,
                name = o.str("name").ifBlank { fallbackName },
                entries = entries,
                description = o.str("description"),
            )
        }
    }
}

// ---------------------------------------------------------------- Chat

private val idGen = AtomicLong(System.nanoTime())
fun newId(): Long = idGen.incrementAndGet()

data class ChatMessage(
    val isUser: Boolean,
    val name: String,
    val text: String,
    val swipes: List<String> = listOf(text),
    val swipeId: Int = 0,
    val sendDate: String = stDate(),
    val reasoning: String = "",
    val isSystem: Boolean = false,
    val extra: JObj = emptyMap(),
    val id: Long = newId(),
) {
    fun withSwipe(index: Int): ChatMessage {
        val i = index.coerceIn(0, (swipes.size - 1).coerceAtLeast(0))
        return copy(swipeId = i, text = swipes.getOrElse(i) { text })
    }

    /** Replaces the currently shown text (and the matching swipe). */
    fun withText(t: String): ChatMessage {
        val sw = swipes.toMutableList()
        if (sw.isEmpty()) sw.add(t) else sw[swipeId.coerceIn(0, sw.size - 1)] = t
        return copy(text = t, swipes = sw)
    }

    fun addSwipe(t: String): ChatMessage {
        val sw = swipes.toMutableList().apply { add(t) }
        return copy(swipes = sw, swipeId = sw.size - 1, text = t)
    }
}

fun stDate(time: Long = System.currentTimeMillis()): String =
    SimpleDateFormat("yyyy-MM-dd'@'HH'h'mm'm'ss's'", Locale.US).format(Date(time))

data class ChatInfo(val fileName: String, val lastModified: Long, val messageCount: Int, val preview: String)

// ---------------------------------------------------------------- Settings

enum class ApiKind(val label: String) {
    OPENAI_CHAT("Chat completion (OpenAI-compatible)"),
    ANTHROPIC("Anthropic Claude"),
    TEXT_COMPLETION("Text completion (KoboldCpp, llama.cpp, vLLM…)");
}

data class Connection(
    val id: String,
    val name: String,
    val kind: ApiKind = ApiKind.OPENAI_CHAT,
    val baseUrl: String = "https://openrouter.ai/api/v1",
    val apiKey: String = "",
    val model: String = "",
    /** Send top_k / min_p / repetition_penalty. Official OpenAI rejects them. */
    val extraSamplers: Boolean = true,
) {
    fun toJson(): MJObj = jsonOf(
        "id" to id, "name" to name, "kind" to kind.name, "baseUrl" to baseUrl,
        "apiKey" to apiKey, "model" to model, "extraSamplers" to extraSamplers,
    )

    companion object {
        fun fromJson(o: JObj) = Connection(
            id = o.str("id", "default"),
            name = o.str("name", "Connection"),
            kind = runCatching { ApiKind.valueOf(o.str("kind")) }.getOrDefault(ApiKind.OPENAI_CHAT),
            baseUrl = o.str("baseUrl"),
            apiKey = o.str("apiKey"),
            model = o.str("model"),
            extraSamplers = o.bool("extraSamplers", true),
        )
    }
}

data class InstructTemplate(
    val name: String,
    val systemPrefix: String,
    val systemSuffix: String,
    val userPrefix: String,
    val userSuffix: String,
    val assistantPrefix: String,
    val assistantSuffix: String,
    val stop: List<String>,
) {
    companion object {
        val NONE = InstructTemplate("None (plain names)", "", "\n", "", "\n", "", "\n", emptyList())
        val CHATML = InstructTemplate(
            "ChatML", "<|im_start|>system\n", "<|im_end|>\n", "<|im_start|>user\n", "<|im_end|>\n",
            "<|im_start|>assistant\n", "<|im_end|>\n", listOf("<|im_end|>", "<|im_start|>"),
        )
        val LLAMA3 = InstructTemplate(
            "Llama 3", "<|start_header_id|>system<|end_header_id|>\n\n", "<|eot_id|>",
            "<|start_header_id|>user<|end_header_id|>\n\n", "<|eot_id|>",
            "<|start_header_id|>assistant<|end_header_id|>\n\n", "<|eot_id|>", listOf("<|eot_id|>"),
        )
        val MISTRAL = InstructTemplate(
            "Mistral", "[INST] ", " [/INST]", "[INST] ", " [/INST]", "", "</s>", listOf("</s>", "[INST]"),
        )
        val ALPACA = InstructTemplate(
            "Alpaca", "", "\n\n", "### Instruction:\n", "\n\n", "### Response:\n", "\n\n",
            listOf("### Instruction:"),
        )
        val GEMMA = InstructTemplate(
            "Gemma", "<start_of_turn>user\n", "<end_of_turn>\n", "<start_of_turn>user\n", "<end_of_turn>\n",
            "<start_of_turn>model\n", "<end_of_turn>\n", listOf("<end_of_turn>", "<start_of_turn>"),
        )
        val ALL = listOf(NONE, CHATML, LLAMA3, MISTRAL, ALPACA, GEMMA)
        fun byName(n: String) = ALL.firstOrNull { it.name == n } ?: CHATML
    }
}

const val DEFAULT_MAIN_PROMPT =
    "Write {{char}}'s next reply in a fictional chat between {{char}} and {{user}}. " +
        "Stay in character, be descriptive and engaging, and never write {{user}}'s actions or dialogue."
const val DEFAULT_CONTINUE_NUDGE = "[Continue your last message without repeating its original content.]"
const val DEFAULT_NEW_CHAT = "[Start a new chat]"
const val DEFAULT_IMPERSONATE =
    "[Write your next reply from the point of view of {{user}}, using the chat history so far as a guideline for " +
        "the writing style of {{user}}. Don't write as {{char}} or system. Don't describe actions of {{char}}.]"

data class Preset(
    val name: String,
    val temperature: Double = 1.0,
    val topP: Double = 1.0,
    val topK: Int = 0,
    val minP: Double = 0.0,
    val repetitionPenalty: Double = 1.0,
    val frequencyPenalty: Double = 0.0,
    val presencePenalty: Double = 0.0,
    val maxTokens: Int = 400,
    val contextSize: Int = 16384,
    val streaming: Boolean = true,
    val mainPrompt: String = DEFAULT_MAIN_PROMPT,
    val postHistory: String = "",
    val prefill: String = "",
    val newChatPrompt: String = DEFAULT_NEW_CHAT,
    val continueNudge: String = DEFAULT_CONTINUE_NUDGE,
    val impersonatePrompt: String = DEFAULT_IMPERSONATE,
    val instruct: String = InstructTemplate.CHATML.name,
    val namesInText: Boolean = true,
    val stopStrings: List<String> = emptyList(),
    val preferCharPrompt: Boolean = true,
) {
    fun toJson(): MJObj = jsonOf(
        "name" to name, "temperature" to temperature, "topP" to topP, "topK" to topK, "minP" to minP,
        "repetitionPenalty" to repetitionPenalty, "frequencyPenalty" to frequencyPenalty,
        "presencePenalty" to presencePenalty, "maxTokens" to maxTokens, "contextSize" to contextSize,
        "streaming" to streaming, "mainPrompt" to mainPrompt, "postHistory" to postHistory,
        "prefill" to prefill, "newChatPrompt" to newChatPrompt, "continueNudge" to continueNudge,
        "impersonatePrompt" to impersonatePrompt, "instruct" to instruct, "namesInText" to namesInText,
        "stopStrings" to stopStrings, "preferCharPrompt" to preferCharPrompt,
    )

    companion object {
        fun fromJson(o: JObj): Preset {
            val d = Preset(o.str("name", "Default"))
            return Preset(
                name = d.name,
                temperature = o.num("temperature", d.temperature),
                topP = o.num("topP", d.topP),
                topK = o.int("topK", d.topK),
                minP = o.num("minP", d.minP),
                repetitionPenalty = o.num("repetitionPenalty", d.repetitionPenalty),
                frequencyPenalty = o.num("frequencyPenalty", d.frequencyPenalty),
                presencePenalty = o.num("presencePenalty", d.presencePenalty),
                maxTokens = o.int("maxTokens", d.maxTokens),
                contextSize = o.int("contextSize", d.contextSize),
                streaming = o.bool("streaming", d.streaming),
                mainPrompt = o.str("mainPrompt", d.mainPrompt),
                postHistory = o.str("postHistory", d.postHistory),
                prefill = o.str("prefill", d.prefill),
                newChatPrompt = o.str("newChatPrompt", d.newChatPrompt),
                continueNudge = o.str("continueNudge", d.continueNudge),
                impersonatePrompt = o.str("impersonatePrompt", d.impersonatePrompt),
                instruct = o.str("instruct", d.instruct),
                namesInText = o.bool("namesInText", d.namesInText),
                stopStrings = o.strList("stopStrings"),
                preferCharPrompt = o.bool("preferCharPrompt", d.preferCharPrompt),
            )
        }

        /**
         * Imports a SillyTavern preset. Handles chat-completion presets
         * (prompts[] with main/jailbreak) and text-completion sampler presets.
         */
        fun fromSillyTavern(name: String, o: JObj): Preset {
            val d = Preset(name)
            val prompts = o.objList("prompts")
            fun prompt(id: String) = prompts.firstOrNull { it.str("identifier") == id }?.str("content")
            return d.copy(
                temperature = o.num("temperature", o.num("temp", d.temperature)),
                topP = o.num("top_p", d.topP),
                topK = o.int("top_k", d.topK),
                minP = o.num("min_p", d.minP),
                repetitionPenalty = o.num("repetition_penalty", o.num("rep_pen", d.repetitionPenalty)),
                frequencyPenalty = o.num("frequency_penalty", o.num("freq_pen", d.frequencyPenalty)),
                presencePenalty = o.num("presence_penalty", o.num("presence_pen", d.presencePenalty)),
                maxTokens = o.int("openai_max_tokens", o.int("genamt", d.maxTokens)),
                contextSize = o.int("openai_max_context", o.int("max_length", d.contextSize)),
                streaming = o.bool("stream_openai", d.streaming),
                mainPrompt = prompt("main") ?: d.mainPrompt,
                postHistory = prompt("jailbreak") ?: d.postHistory,
                prefill = o.str("assistant_prefill", d.prefill),
                newChatPrompt = o.str("new_chat_prompt", d.newChatPrompt),
                continueNudge = o.str("continue_nudge_prompt", d.continueNudge),
                impersonatePrompt = o.str("impersonation_prompt", d.impersonatePrompt),
            )
        }
    }
}

data class Persona(val name: String = "User", val description: String = "") {
    fun toJson(): MJObj = jsonOf("name" to name, "description" to description)

    companion object {
        fun fromJson(o: JObj) = Persona(o.str("name", "User").ifBlank { "User" }, o.str("description"))
    }
}

data class WiSettings(
    val scanDepth: Int = 2,
    val budgetPercent: Int = 25,
    val recursive: Boolean = true,
    val maxRecursionSteps: Int = 3,
    val caseSensitive: Boolean = false,
    val matchWholeWords: Boolean = false,
) {
    fun toJson(): MJObj = jsonOf(
        "scanDepth" to scanDepth, "budgetPercent" to budgetPercent, "recursive" to recursive,
        "maxRecursionSteps" to maxRecursionSteps, "caseSensitive" to caseSensitive,
        "matchWholeWords" to matchWholeWords,
    )

    companion object {
        fun fromJson(o: JObj) = WiSettings(
            o.int("scanDepth", 2), o.int("budgetPercent", 25), o.bool("recursive", true),
            o.int("maxRecursionSteps", 3), o.bool("caseSensitive", false), o.bool("matchWholeWords", false),
        )
    }
}

data class AppSettings(
    val connections: List<Connection> = listOf(Connection("default", "OpenRouter")),
    val activeConnection: String = "default",
    val presets: List<Preset> = listOf(Preset("Default")),
    val activePreset: String = "Default",
    val persona: Persona = Persona(),
    val wi: WiSettings = WiSettings(),
    val globalLorebooks: List<String> = emptyList(),
    val fontScale: Float = 1f,
    val showAvatars: Boolean = true,
    val tokenRatio: Double = 3.5,
) {
    val connection: Connection get() = connections.firstOrNull { it.id == activeConnection } ?: connections.first()
    val preset: Preset get() = presets.firstOrNull { it.name == activePreset } ?: presets.first()

    fun toJson(): MJObj = jsonOf(
        "connections" to connections.map { it.toJson() },
        "activeConnection" to activeConnection,
        "presets" to presets.map { it.toJson() },
        "activePreset" to activePreset,
        "persona" to persona.toJson(),
        "wi" to wi.toJson(),
        "globalLorebooks" to globalLorebooks,
        "fontScale" to fontScale.toDouble(),
        "showAvatars" to showAvatars,
        "tokenRatio" to tokenRatio,
    )

    companion object {
        fun fromJson(o: JObj): AppSettings {
            val d = AppSettings()
            val conns = o.objList("connections").map { Connection.fromJson(it) }.ifEmpty { d.connections }
            val presets = o.objList("presets").map { Preset.fromJson(it) }.ifEmpty { d.presets }
            return AppSettings(
                connections = conns,
                activeConnection = o.str("activeConnection", conns.first().id),
                presets = presets,
                activePreset = o.str("activePreset", presets.first().name),
                persona = o.obj("persona")?.let { Persona.fromJson(it) } ?: d.persona,
                wi = o.obj("wi")?.let { WiSettings.fromJson(it) } ?: d.wi,
                globalLorebooks = o.strList("globalLorebooks"),
                fontScale = o.num("fontScale", 1.0).toFloat(),
                showAvatars = o.bool("showAvatars", true),
                tokenRatio = o.num("tokenRatio", 3.5).coerceIn(1.0, 8.0),
            )
        }
    }
}
