package app.hearth.core

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.InflaterInputStream

/** Reads and writes SillyTavern / Tavern character cards (PNG with embedded JSON, or plain JSON). */
object Cards {
    private val PNG_SIG = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)

    fun isPng(bytes: ByteArray): Boolean =
        bytes.size >= 8 && (0 until 8).all { bytes[it] == PNG_SIG[it] }

    /** Returns keyword -> text for every tEXt / iTXt / zTXt chunk. */
    fun readTextChunks(png: ByteArray): Map<String, String> {
        require(isPng(png)) { "Not a PNG file" }
        val out = LinkedHashMap<String, String>()
        var p = 8
        while (p + 8 <= png.size) {
            val len = readInt(png, p)
            val type = String(png, p + 4, 4, Charsets.ISO_8859_1)
            val dataStart = p + 8
            if (len < 0 || dataStart + len > png.size) break
            when (type) {
                "tEXt" -> {
                    val z = indexOfZero(png, dataStart, dataStart + len)
                    if (z > 0) {
                        val key = String(png, dataStart, z - dataStart, Charsets.ISO_8859_1)
                        out[key] = String(png, z + 1, dataStart + len - z - 1, Charsets.ISO_8859_1)
                    }
                }
                "zTXt" -> {
                    val z = indexOfZero(png, dataStart, dataStart + len)
                    if (z > 0 && z + 2 <= dataStart + len) {
                        val key = String(png, dataStart, z - dataStart, Charsets.ISO_8859_1)
                        val comp = png.copyOfRange(z + 2, dataStart + len)
                        runCatching { out[key] = String(inflate(comp), Charsets.ISO_8859_1) }
                    }
                }
                "iTXt" -> {
                    val end = dataStart + len
                    val z = indexOfZero(png, dataStart, end)
                    if (z > 0 && z + 3 <= end) {
                        val key = String(png, dataStart, z - dataStart, Charsets.ISO_8859_1)
                        val compressed = png[z + 1].toInt() != 0
                        val langEnd = indexOfZero(png, z + 3, end)
                        val transEnd = if (langEnd >= 0) indexOfZero(png, langEnd + 1, end) else -1
                        if (transEnd >= 0) {
                            val body = png.copyOfRange(transEnd + 1, end)
                            runCatching {
                                out[key] = String(if (compressed) inflate(body) else body, Charsets.UTF_8)
                            }
                        }
                    }
                }
                "IEND" -> break
            }
            p = dataStart + len + 4
        }
        return out
    }

    /** Extracts the card JSON from a PNG; prefers V3 (ccv3) over V2 (chara). */
    fun extractJsonFromPng(png: ByteArray): MJObj {
        val chunks = readTextChunks(png)
        val b64 = chunks.entries.firstOrNull { it.key.equals("ccv3", true) }?.value
            ?: chunks.entries.firstOrNull { it.key.equals("chara", true) }?.value
            ?: throw IllegalArgumentException("This PNG has no character data")
        val json = String(Base64.getMimeDecoder().decode(b64.trim()), Charsets.UTF_8)
        return Json.parseObj(json)
    }

    /** Parses a card (any of V1/V2/V3 or legacy Pygmalion-style JSON) into a Character. */
    fun parse(id: String, card: JObj): Character {
        val spec = card.str("spec")
        val data: JObj = if (spec.startsWith("chara_card_v") || card["data"] is Map<*, *>) {
            card.obj("data") ?: card
        } else card
        val ext = data.obj("extensions") ?: emptyMap()
        val depth = ext.obj("depth_prompt")?.let {
            DepthPrompt(it.str("prompt"), it.int("depth", 4), Role.fromString(it.str("role", "system")))
        }?.takeIf { it.prompt.isNotBlank() }
        val name = data.str("name").ifBlank { data.str("char_name") }.ifBlank { "Unnamed" }
        val book = data.obj("character_book")?.let { Lorebook.fromJson("card:$id", it, "$name's lorebook") }
            ?.takeIf { it.entries.isNotEmpty() }
        return Character(
            id = id,
            name = name,
            description = data.str("description").ifEmpty { data.str("char_persona") },
            personality = data.str("personality"),
            scenario = data.str("scenario").ifEmpty { data.str("world_scenario") },
            firstMes = data.str("first_mes").ifEmpty { data.str("char_greeting") },
            mesExample = data.str("mes_example").ifEmpty { data.str("example_dialogue") },
            alternateGreetings = data.strList("alternate_greetings").filter { it.isNotBlank() },
            systemPrompt = data.str("system_prompt"),
            postHistoryInstructions = data.str("post_history_instructions"),
            creatorNotes = data.str("creator_notes").ifEmpty { data.str("creatorcomment") },
            creator = data.str("creator"),
            characterVersion = data.str("character_version"),
            tags = data.strList("tags"),
            book = book,
            linkedWorld = ext.str("world"),
            depthPrompt = depth,
            talkativeness = ext.num("talkativeness", 0.5).toFloat().coerceIn(0f, 1f),
            regex = ext.objList("regex_scripts").map { RegexScript.fromJson(it) },
            raw = card,
        )
    }

    /** Returns a copy of [png] whose card data is replaced with [card] (written as V2 "chara"). */
    fun writeToPng(png: ByteArray, card: JObj): ByteArray {
        require(isPng(png)) { "Not a PNG file" }
        val out = ByteArrayOutputStream(png.size + 4096)
        out.write(png, 0, 8)
        var p = 8
        val b64 = Base64.getEncoder().encodeToString(Json.stringify(card).toByteArray(Charsets.UTF_8))
        while (p + 8 <= png.size) {
            val len = readInt(png, p)
            val type = String(png, p + 4, 4, Charsets.ISO_8859_1)
            val total = 12 + len
            if (len < 0 || p + total > png.size) break
            var skip = false
            if (type == "tEXt" || type == "iTXt" || type == "zTXt") {
                val z = indexOfZero(png, p + 8, p + 8 + len)
                if (z > 0) {
                    val key = String(png, p + 8, z - p - 8, Charsets.ISO_8859_1)
                    if (key.equals("chara", true) || key.equals("ccv3", true)) skip = true
                }
            }
            if (type == "IEND") {
                writeChunk(out, "tEXt", ("chara\u0000$b64").toByteArray(Charsets.ISO_8859_1))
            }
            if (!skip) out.write(png, p, total)
            p += total
            if (type == "IEND") break
        }
        return out.toByteArray()
    }

    private fun writeChunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        val t = type.toByteArray(Charsets.ISO_8859_1)
        writeInt(out, data.size)
        out.write(t)
        out.write(data)
        val crc = CRC32()
        crc.update(t)
        crc.update(data)
        writeInt(out, crc.value.toInt())
    }

    private fun inflate(b: ByteArray): ByteArray = InflaterInputStream(b.inputStream()).use { it.readBytes() }

    private fun readInt(b: ByteArray, p: Int): Int =
        ((b[p].toInt() and 0xff) shl 24) or ((b[p + 1].toInt() and 0xff) shl 16) or
            ((b[p + 2].toInt() and 0xff) shl 8) or (b[p + 3].toInt() and 0xff)

    private fun writeInt(out: ByteArrayOutputStream, v: Int) {
        out.write((v ushr 24) and 0xff); out.write((v ushr 16) and 0xff)
        out.write((v ushr 8) and 0xff); out.write(v and 0xff)
    }

    private fun indexOfZero(b: ByteArray, from: Int, to: Int): Int {
        for (i in from until minOf(to, b.size)) if (b[i].toInt() == 0) return i
        return -1
    }
}
