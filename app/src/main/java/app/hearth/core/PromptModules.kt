package app.hearth.core

/**
 * One entry of a SillyTavern-style prompt manager. Markers are filled in by the app
 * (character description, chat history, world info…); other modules carry their own text.
 */
data class PromptModule(
    val identifier: String,
    val name: String,
    val role: Role = Role.SYSTEM,
    val content: String = "",
    val marker: Boolean = false,
    val enabled: Boolean = true,
    /** false = placed in order (relative); true = injected into chat history at [depth]. */
    val inChat: Boolean = false,
    val depth: Int = 4,
    val order: Int = 100,
    val extra: JObj = emptyMap(),
) {
    fun toJson(): MJObj {
        @Suppress("UNCHECKED_CAST")
        val m = (deepCopy(extra) as? MJObj) ?: MJObj()
        m["identifier"] = identifier
        m["name"] = name
        m["role"] = role.api
        m["content"] = content
        m["marker"] = marker
        m["enabled"] = enabled
        m["injection_position"] = if (inChat) 1 else 0
        m["injection_depth"] = depth
        m["injection_order"] = order
        return m
    }

    companion object {
        const val MAIN = "main"
        const val NSFW = "nsfw"
        const val JAILBREAK = "jailbreak"
        const val ENHANCE = "enhanceDefinitions"
        const val WI_BEFORE = "worldInfoBefore"
        const val WI_AFTER = "worldInfoAfter"
        const val CHAR_DESC = "charDescription"
        const val CHAR_PERSONALITY = "charPersonality"
        const val SCENARIO = "scenario"
        const val PERSONA = "personaDescription"
        const val EXAMPLES = "dialogueExamples"
        const val HISTORY = "chatHistory"

        /** Markers the app fills in, with a human description. */
        val MARKERS = linkedMapOf(
            WI_BEFORE to "World info (before)",
            PERSONA to "Persona description",
            CHAR_DESC to "Character description",
            CHAR_PERSONALITY to "Character personality",
            SCENARIO to "Scenario",
            WI_AFTER to "World info (after)",
            EXAMPLES to "Example dialogue",
            HISTORY to "Chat history",
        )

        fun isMarker(id: String) = id in MARKERS

        private val KNOWN = setOf(
            "identifier", "name", "role", "content", "marker", "enabled",
            "injection_position", "injection_depth", "injection_order",
        )

        fun defaults(main: String = DEFAULT_MAIN_PROMPT, postHistory: String = ""): List<PromptModule> = listOf(
            PromptModule(MAIN, "Main prompt", content = main),
            PromptModule(WI_BEFORE, MARKERS.getValue(WI_BEFORE), marker = true),
            PromptModule(PERSONA, MARKERS.getValue(PERSONA), marker = true),
            PromptModule(CHAR_DESC, MARKERS.getValue(CHAR_DESC), marker = true),
            PromptModule(CHAR_PERSONALITY, MARKERS.getValue(CHAR_PERSONALITY), marker = true),
            PromptModule(SCENARIO, MARKERS.getValue(SCENARIO), marker = true),
            PromptModule(ENHANCE, "Enhance definitions", enabled = false,
                content = "If you have more knowledge of {{char}}, add to the character's lore and personality to enhance them but keep the Character Sheet's definitions absolute."),
            PromptModule(NSFW, "Auxiliary prompt", content = ""),
            PromptModule(WI_AFTER, MARKERS.getValue(WI_AFTER), marker = true),
            PromptModule(EXAMPLES, MARKERS.getValue(EXAMPLES), marker = true),
            PromptModule(HISTORY, MARKERS.getValue(HISTORY), marker = true),
            PromptModule(JAILBREAK, "Post-history instructions", content = postHistory),
        )

        fun fromJson(o: JObj): PromptModule {
            val id = o.str("identifier").ifEmpty { "custom_" + o.str("name").hashCode().toUInt().toString(16) }
            return PromptModule(
                identifier = id,
                name = o.str("name").ifEmpty { MARKERS[id] ?: id },
                role = Role.fromString(o.str("role", "system")),
                content = o.str("content"),
                marker = o.bool("marker", false) || isMarker(id),
                enabled = o.bool("enabled", true),
                inChat = o.int("injection_position", 0) == 1,
                depth = o.int("injection_depth", 4),
                order = o.int("injection_order", 100),
                extra = o.filterKeys { it !in KNOWN },
            )
        }

        /**
         * Builds the module list from an ST chat-completion preset: prompts[] gives the
         * content, prompt_order gives order and on/off state. Returns null if the preset
         * has no prompts (e.g. a text-completion sampler preset).
         */
        fun fromSillyTavern(o: JObj): List<PromptModule>? {
            val prompts = o.objList("prompts")
            if (prompts.isEmpty()) return null
            val byId = LinkedHashMap<String, PromptModule>()
            for (p in prompts) {
                val m = fromJson(p)
                byId[m.identifier] = m
            }
            val orders = o.objList("prompt_order")
            val chosen = orders.firstOrNull { it.int("character_id", -1) == 100001 }
                ?: orders.firstOrNull { it.int("character_id", -1) == 100000 }
                ?: orders.firstOrNull()
            val out = ArrayList<PromptModule>()
            if (chosen != null) {
                for (entry in chosen.objList("order")) {
                    val id = entry.str("identifier")
                    val base = byId.remove(id) ?: if (isMarker(id)) PromptModule(id, MARKERS.getValue(id), marker = true) else continue
                    out.add(base.copy(enabled = entry.bool("enabled", true)))
                }
                // Prompts defined but not placed in the order: keep them, switched off.
                byId.values.filter { !it.marker }.forEach { out.add(it.copy(enabled = false)) }
            } else {
                out.addAll(byId.values)
            }
            if (out.none { it.identifier == HISTORY }) out.add(PromptModule(HISTORY, MARKERS.getValue(HISTORY), marker = true))
            return out
        }
    }
}
