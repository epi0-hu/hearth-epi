package app.hearth.core

import java.io.File

/**
 * Chats are stored as SillyTavern-compatible JSONL: a header line followed by one
 * message per line. Files can be copied to/from a SillyTavern install as-is.
 */
object ChatStore {

    data class Chat(val header: MJObj, val messages: List<ChatMessage>)

    fun messageToJson(m: ChatMessage): MJObj {
        @Suppress("UNCHECKED_CAST")
        val o = (deepCopy(m.extra) as? MJObj)?.let { e -> MJObj().also { it.putAll(e) } } ?: MJObj()
        val ex = (o["extra"] as? MJObj) ?: MJObj()
        if (m.reasoning.isNotEmpty()) ex["reasoning"] = m.reasoning else ex.remove("reasoning")
        o["name"] = m.name
        o["is_user"] = m.isUser
        o["is_system"] = m.isSystem
        o["send_date"] = m.sendDate
        o["mes"] = m.text
        o["swipes"] = m.swipes
        o["swipe_id"] = m.swipeId
        o["extra"] = ex
        return o
    }

    fun messageFromJson(o: JObj): ChatMessage {
        val text = o.str("mes")
        val swipes = o.strList("swipes").ifEmpty { listOf(text) }
        val swipeId = o.int("swipe_id", 0).coerceIn(0, swipes.size - 1)
        val known = setOf("name", "is_user", "is_system", "send_date", "mes", "swipes", "swipe_id")
        return ChatMessage(
            isUser = o.bool("is_user", false),
            name = o.str("name"),
            text = text,
            swipes = swipes,
            swipeId = swipeId,
            sendDate = o.str("send_date"),
            reasoning = o.obj("extra")?.str("reasoning") ?: "",
            isSystem = o.bool("is_system", false),
            extra = o.filterKeys { it !in known },
        )
    }

    fun newHeader(userName: String, charName: String): MJObj = jsonOf(
        "user_name" to userName,
        "character_name" to charName,
        "create_date" to stDate(),
        "chat_metadata" to MJObj(),
    )

    fun read(file: File): Chat {
        var header: MJObj? = null
        val msgs = ArrayList<ChatMessage>()
        file.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                if (line.isBlank()) continue
                val o = runCatching { Json.parseObj(line) }.getOrNull() ?: continue
                if (header == null && !o.containsKey("mes")) { header = o; continue }
                msgs.add(messageFromJson(o))
            }
        }
        return Chat(header ?: MJObj(), msgs)
    }

    /** Atomic write: temp file then rename, so a crash never leaves a half-written chat. */
    fun write(file: File, header: JObj, messages: List<ChatMessage>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.bufferedWriter(Charsets.UTF_8).use { w ->
            w.write(Json.stringify(header)); w.write("\n")
            for (m in messages) { w.write(Json.stringify(messageToJson(m))); w.write("\n") }
        }
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    /** Cheap scan for the chat list: counts lines and grabs the last message preview. */
    fun info(file: File): ChatInfo {
        var count = 0
        var last = ""
        file.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                if (line.isBlank()) continue
                count++
                last = line
            }
        }
        val preview = runCatching { Json.parseObj(last).str("mes") }.getOrDefault("")
        return ChatInfo(file.name, file.lastModified(), (count - 1).coerceAtLeast(0), preview.take(140))
    }
}
