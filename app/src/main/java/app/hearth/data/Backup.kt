package app.hearth.data

import app.hearth.core.AppSettings
import app.hearth.core.Group
import app.hearth.core.GroupStrategy
import app.hearth.core.Json
import app.hearth.core.Lorebook
import app.hearth.core.Preset
import app.hearth.core.RegexScript
import app.hearth.core.bool
import app.hearth.core.int
import app.hearth.core.jsonOf
import app.hearth.core.obj
import app.hearth.core.str
import app.hearth.core.strList
import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Full backups of Hearth's data, plus import from a zipped SillyTavern data folder
 * (characters, chats, lorebooks, groups and group chats, presets, regex scripts).
 */
class Backup(private val storage: Storage) {

    data class StImport(
        val characters: Int,
        val chats: Int,
        val lorebooks: Int,
        val groups: Int,
        val presets: List<Preset>,
        val regex: List<RegexScript>,
    )

    sealed interface Result {
        data class Restored(val files: Int) : Result
        data class SillyTavern(val data: StImport) : Result
    }

    private val includedDirs = listOf("characters", "thumbs", "chats", "lorebooks", "groups")

    fun export(out: OutputStream, settings: AppSettings, includeKeys: Boolean) {
        val root = storage.root
        ZipOutputStream(BufferedOutputStream(out)).use { zip ->
            zip.putNextEntry(ZipEntry(MARKER))
            zip.write(Json.stringify(jsonOf("app" to "Hearth", "version" to 1, "created" to System.currentTimeMillis())).toByteArray())
            zip.closeEntry()
            val s = if (includeKeys) settings else settings.copy(connections = settings.connections.map { it.copy(apiKey = "") })
            zip.putNextEntry(ZipEntry("settings.json"))
            zip.write(Json.stringify(s.toJson(), pretty = true).toByteArray())
            zip.closeEntry()
            for (dir in includedDirs) {
                val d = File(root, dir)
                if (!d.exists()) continue
                d.walkTopDown().filter { it.isFile && !it.name.endsWith(".tmp") }.forEach { f ->
                    zip.putNextEntry(ZipEntry(f.relativeTo(root).invariantSeparatorsPath))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
    }

    /** Restores a Hearth backup or imports a SillyTavern data zip, whichever [file] is. */
    fun restore(file: File, current: AppSettings): Result {
        ZipFile(file).use { zip ->
            if (zip.getEntry(MARKER) != null) return restoreHearth(zip, current)
            return Result.SillyTavern(importSillyTavern(zip))
        }
    }

    private fun safeTarget(name: String): File? {
        if (name.contains("..") || name.startsWith("/") || name.contains('\\')) return null
        val top = name.substringBefore('/')
        if (name != "settings.json" && top !in includedDirs) return null
        return File(storage.root, name)
    }

    private fun restoreHearth(zip: ZipFile, current: AppSettings): Result {
        var n = 0
        for (e in zip.entries().toList()) {
            if (e.isDirectory || e.name == MARKER) continue
            val target = safeTarget(e.name) ?: continue
            if (e.name == "settings.json") {
                // Keep API keys already on this device when the backup was made without them.
                val restored = runCatching {
                    AppSettings.fromJson(Json.parseObj(zip.getInputStream(e).bufferedReader().readText()))
                }.getOrNull() ?: continue
                val merged = restored.copy(connections = restored.connections.map { c ->
                    if (c.apiKey.isBlank()) c.copy(apiKey = current.connections.firstOrNull { it.id == c.id }?.apiKey ?: "") else c
                })
                storage.saveSettings(merged)
                n++
                continue
            }
            target.parentFile?.mkdirs()
            zip.getInputStream(e).use { input -> target.outputStream().use { input.copyTo(it) } }
            n++
        }
        return Result.Restored(n)
    }

    private fun importSillyTavern(zip: ZipFile): StImport {
        val names = zip.entries().toList().filter { !it.isDirectory }.map { it.name }
        val prefix = names.firstNotNullOfOrNull { PREFIX_RX.find(it)?.groupValues?.get(1) }
            ?: throw IllegalArgumentException("This zip isn't a Hearth backup or a SillyTavern data folder")
        fun under(dir: String) = names.filter { it.startsWith(prefix + dir) }
        fun bytes(name: String) = zip.getInputStream(zip.getEntry(name)).use { it.readBytes() }
        fun base(path: String) = path.substringAfterLast('/').substringBeforeLast('.')

        // Characters: remember ST avatar name -> our id for chats and groups.
        val idByAvatar = HashMap<String, String>()
        val index = storage.loadIndex().toMutableList()
        for (path in under("characters/").filter { it.count { ch -> ch == '/' } == prefix.count { ch -> ch == '/' } + 1 && it.endsWith(".png", true) }) {
            runCatching {
                val ch = storage.importCard(bytes(path))
                idByAvatar[base(path)] = ch.id
                index.add(0, storage.summarize(ch))
            }
        }
        storage.saveIndex(index)

        var chats = 0
        for (path in under("chats/").filter { it.endsWith(".jsonl", true) }) {
            val folder = path.removePrefix(prefix + "chats/").substringBefore('/')
            val id = idByAvatar[folder] ?: continue
            runCatching { storage.importChat(id, path.substringAfterLast('/'), bytes(path)); chats++ }
        }

        var books = 0
        for (path in under("worlds/").filter { it.endsWith(".json", true) }) {
            runCatching {
                val o = Json.parseObj(bytes(path).toString(Charsets.UTF_8))
                if (o["entries"] == null) return@runCatching
                val name = base(path)
                storage.saveLorebook(Lorebook.fromJson(storage.newLorebookId(name), o, name).let {
                    if (it.name.isBlank()) it.copy(name = name) else it
                })
                books++
            }
        }

        var groups = 0
        for (path in under("groups/").filter { it.endsWith(".json", true) }) {
            runCatching {
                val o = Json.parseObj(bytes(path).toString(Charsets.UTF_8))
                val members = o.strList("members").mapNotNull { idByAvatar[it.substringBeforeLast('.')] }
                if (members.isEmpty()) return@runCatching
                val name = o.str("name", "Group")
                val g = Group(
                    id = storage.newGroupId(name), name = name, members = members,
                    disabled = o.strList("disabled_members").mapNotNull { idByAvatar[it.substringBeforeLast('.')] },
                    strategy = GroupStrategy.fromWire(o.int("activation_strategy", 0)),
                    allowSelfResponses = o.bool("allow_self_responses", false),
                    joinCards = o.int("generation_mode", 0) != 0,
                    favorite = o.bool("fav", false),
                    lastActive = System.currentTimeMillis(),
                )
                storage.saveGroup(g)
                val chatIds = (o.strList("chats") + o.str("chat_id")).filter { it.isNotBlank() }.distinct()
                for (cid in chatIds) {
                    val entry = prefix + "group chats/" + cid + ".jsonl"
                    if (zip.getEntry(entry) != null) storage.importChat(g.chatKey, "$cid.jsonl", bytes(entry))
                }
                groups++
            }
        }

        val presets = (under("OpenAI Settings/") + under("TextGen Settings/")).filter { it.endsWith(".json", true) }
            .mapNotNull { path ->
                runCatching { Preset.fromSillyTavern(base(path), Json.parseObj(bytes(path).toString(Charsets.UTF_8))) }.getOrNull()
            }

        val regex = names.firstOrNull { it == prefix + "settings.json" }?.let { path ->
            runCatching {
                val o = Json.parseObj(bytes(path).toString(Charsets.UTF_8))
                RegexScript.parseAny(o.obj("extension_settings")?.get("regex"))
            }.getOrNull()
        }.orEmpty()

        return StImport(idByAvatar.size, chats, books, groups, presets, regex)
    }

    companion object {
        const val MARKER = "hearth-backup.json"
        private val PREFIX_RX = Regex("^(.*?)(characters|worlds|chats|groups|OpenAI Settings)/[^/]")
    }
}
