package app.hearth.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import app.hearth.core.AppSettings
import app.hearth.core.Cards
import app.hearth.core.Character
import app.hearth.core.CharacterSummary
import app.hearth.core.ChatInfo
import app.hearth.core.ChatStore
import app.hearth.core.Json
import app.hearth.core.Lorebook
import app.hearth.core.bool
import app.hearth.core.jsonOf
import app.hearth.core.objList
import app.hearth.core.str
import app.hearth.core.strList
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Plain-file storage under the app's private directory:
 *   characters/<id>.json  card JSON (V2)       characters/<id>.png  original avatar
 *   thumbs/<id>.webp      small list avatar    chats/<id>/<name>.jsonl  ST-format chats
 *   lorebooks/<id>.json   ST world-info files  settings.json
 */
class Storage(root: File) {
    val charDir = File(root, "characters").apply { mkdirs() }
    private val thumbDir = File(root, "thumbs").apply { mkdirs() }
    private val chatRoot = File(root, "chats").apply { mkdirs() }
    private val loreDir = File(root, "lorebooks").apply { mkdirs() }
    private val settingsFile = File(root, "settings.json")
    private val indexFile = File(charDir, "_index.json")

    // ------------------------------------------------------------ settings
    fun loadSettings(): AppSettings = runCatching {
        AppSettings.fromJson(Json.parseObj(settingsFile.readText()))
    }.getOrDefault(AppSettings())

    fun saveSettings(s: AppSettings) = atomicWrite(settingsFile, Json.stringify(s.toJson(), pretty = true))

    // ------------------------------------------------------------ characters
    fun avatarFile(id: String) = File(charDir, "$id.png")
    fun thumbFile(id: String) = File(thumbDir, "$id.webp")
    private fun cardFile(id: String) = File(charDir, "$id.json")

    fun loadIndex(): List<CharacterSummary> {
        val fromIndex = runCatching {
            Json.parseObj(indexFile.readText()).objList("items").map {
                CharacterSummary(
                    it.str("id"), it.str("name"), it.str("tagline"), it.strList("tags"),
                    it.str("lastActive", "0").toLongOrNull() ?: 0L, it.bool("favorite", false),
                )
            }
        }.getOrNull()
        if (fromIndex != null) return fromIndex
        // Rebuild from card files (first run after an upgrade or a corrupt index).
        val rebuilt = charDir.listFiles { f -> f.name.endsWith(".json") && f.name != indexFile.name }
            .orEmpty().mapNotNull { f ->
                val id = f.name.removeSuffix(".json")
                loadCharacter(id)?.let { summarize(it, f.lastModified()) }
            }
        saveIndex(rebuilt)
        return rebuilt
    }

    fun saveIndex(items: List<CharacterSummary>) {
        val arr = items.map {
            jsonOf(
                "id" to it.id, "name" to it.name, "tagline" to it.tagline, "tags" to it.tags,
                "lastActive" to it.lastActive.toString(), "favorite" to it.favorite,
            )
        }
        atomicWrite(indexFile, Json.stringify(jsonOf("items" to arr)))
    }

    fun summarize(c: Character, lastActive: Long = System.currentTimeMillis(), favorite: Boolean = false) =
        CharacterSummary(
            id = c.id,
            name = c.name,
            tagline = (c.creatorNotes.ifBlank { c.description }).lineSequence()
                .map { it.trim() }.firstOrNull { it.isNotEmpty() }?.take(120) ?: "",
            tags = c.tags,
            lastActive = lastActive,
            favorite = favorite,
        )

    fun loadCharacter(id: String): Character? = runCatching {
        Cards.parse(id, Json.parseObj(cardFile(id).readText()))
    }.getOrNull()

    fun saveCharacter(c: Character) = atomicWrite(cardFile(c.id), Json.stringify(c.toCardJson()))

    fun newCharacterId(name: String): String {
        val base = name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').take(40).ifEmpty { "char" }
        var id = base
        var n = 2
        while (cardFile(id).exists()) id = "${base}_${n++}"
        return id
    }

    fun deleteCharacter(id: String) {
        cardFile(id).delete(); avatarFile(id).delete(); thumbFile(id).delete()
        File(chatRoot, id).deleteRecursively()
    }

    /** Stores the avatar as PNG (converting other formats) and writes a small WebP thumbnail. */
    fun saveAvatar(id: String, bytes: ByteArray) {
        val png = if (Cards.isPng(bytes)) bytes else {
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return
            ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        }
        avatarFile(id).writeBytes(png)
        writeThumb(id, png)
    }

    private fun writeThumb(id: String, bytes: ByteArray) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= THUMB && bounds.outHeight / (sample * 2) >= THUMB) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return
        // Square crop from the top (portrait cards usually have the face near the top).
        val side = minOf(bmp.width, bmp.height)
        val x = (bmp.width - side) / 2
        val cropped = Bitmap.createBitmap(bmp, x, 0, side, side)
        val scaled = Bitmap.createScaledBitmap(cropped, THUMB, THUMB, true)
        @Suppress("DEPRECATION")
        val fmt = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
        thumbFile(id).outputStream().use { scaled.compress(fmt, 85, it) }
    }

    /** Card PNG for export: the avatar (or a plain placeholder) with the card JSON embedded. */
    fun exportCard(c: Character): ByteArray {
        val img = avatarFile(c.id).takeIf { it.exists() }?.readBytes() ?: run {
            val bmp = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF2A2421.toInt()) }
            ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        }
        return Cards.writeToPng(img, c.toCardJson())
    }

    // ------------------------------------------------------------ chats
    fun chatDir(charId: String) = File(chatRoot, charId).apply { mkdirs() }

    fun chatFile(charId: String, name: String) = File(chatDir(charId), name)

    fun listChats(charId: String): List<ChatInfo> =
        chatDir(charId).listFiles { f -> f.name.endsWith(".jsonl") }.orEmpty()
            .sortedByDescending { it.lastModified() }
            .map { runCatching { ChatStore.info(it) }.getOrDefault(ChatInfo(it.name, it.lastModified(), 0, "")) }

    fun latestChat(charId: String): File? =
        chatDir(charId).listFiles { f -> f.name.endsWith(".jsonl") }.orEmpty().maxByOrNull { it.lastModified() }

    // ------------------------------------------------------------ lorebooks
    private fun loreFile(id: String) = File(loreDir, "$id.json")

    fun loadLorebooks(): List<Lorebook> =
        loreDir.listFiles { f -> f.name.endsWith(".json") }.orEmpty().mapNotNull { f ->
            val id = f.name.removeSuffix(".json")
            runCatching { Lorebook.fromJson(id, Json.parseObj(f.readText()), id) }.getOrNull()
        }.sortedBy { it.name.lowercase() }

    fun saveLorebook(b: Lorebook) = atomicWrite(loreFile(b.id), Json.stringify(b.toStJson()))

    fun deleteLorebook(id: String) { loreFile(id).delete() }

    fun newLorebookId(name: String): String {
        val base = name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').take(40).ifEmpty { "lorebook" }
        var id = base
        var n = 2
        while (loreFile(id).exists()) id = "${base}_${n++}"
        return id
    }

    private fun atomicWrite(f: File, text: String) {
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    companion object {
        const val THUMB = 192
    }
}
