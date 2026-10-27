package app.hearth

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.hearth.core.AppSettings
import app.hearth.core.Cards
import app.hearth.core.Character
import app.hearth.core.CharacterSummary
import app.hearth.core.ChatInfo
import app.hearth.core.ChatMessage
import app.hearth.core.ChatStore
import app.hearth.core.Connection
import app.hearth.core.Group
import app.hearth.core.Json
import app.hearth.core.LlmClient
import app.hearth.core.Lorebook
import app.hearth.core.MJObj
import app.hearth.core.Macros
import app.hearth.core.Preset
import app.hearth.core.RegexScript
import app.hearth.core.deepCopy
import app.hearth.core.stDate
import app.hearth.data.Backup
import app.hearth.data.Storage
import app.hearth.regex.RegexService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface Screen {
    data object Characters : Screen
    /** [key] is a character id, or a group chat key (see [Group.chatKey]). */
    data class Chat(val key: String) : Screen
    data class EditCharacter(val charId: String?) : Screen
    data class EditGroup(val groupId: String?) : Screen
    data object Settings : Screen
    data object Lorebooks : Screen
    data class EditLorebook(val id: String) : Screen
    data object Regex : Screen
    data class EditRegex(val id: String?) : Screen
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val storage = Storage(app.filesDir)
    /** Regex scripts run in real JavaScript (exact SillyTavern behavior), with a Kotlin fallback. */
    val regexService = RegexService(app)
    private val backup = Backup(storage)

    /** Snapshot state (not a Flow) so text fields bound to settings update synchronously. */
    private val _settings = mutableStateOf(AppSettings())
    val settings: State<AppSettings> get() = _settings
    val characters = MutableStateFlow<List<CharacterSummary>>(emptyList())
    val groups = MutableStateFlow<List<Group>>(emptyList())
    val lorebooks = MutableStateFlow<List<Lorebook>>(emptyList())
    val ready = MutableStateFlow(false)
    var busy by mutableStateOf<String?>(null)
        private set

    val backStack = mutableStateListOf<Screen>(Screen.Characters)
    val toasts = MutableSharedFlow<String>(extraBufferCapacity = 8)

    var chat by mutableStateOf<ChatSession?>(null)
        private set

    /** Whether the app is on screen (used to decide on "reply ready" notifications). */
    @Volatile var appVisible = true

    /** Bumped when an avatar changes so image caches reload it. */
    val avatarVersion = mutableStateMapOf<String, Long>()

    private var settingsSaveJob: Job? = null
    private val loreSaveJobs = HashMap<String, Job>()

    init {
        viewModelScope.launch { reloadAll() }
    }

    private suspend fun reloadAll() {
        val loaded = withContext(Dispatchers.IO) {
            val s = storage.loadSettings()
            characters.value = storage.loadIndex().sortedByDescending { it.lastActive }
            lorebooks.value = storage.loadLorebooks()
            groups.value = storage.loadGroups()
            s
        }
        _settings.value = loaded
        ready.value = true
    }

    fun toast(msg: String) { toasts.tryEmit(msg) }

    // ------------------------------------------------------------ navigation
    fun navigate(s: Screen) { backStack.add(s) }

    fun back(): Boolean {
        if (backStack.size <= 1) return false
        val top = backStack.removeAt(backStack.lastIndex)
        if (top is Screen.Chat && backStack.none { it is Screen.Chat }) {
            chat?.let { it.stop(); it.flush() }
        }
        return true
    }

    // ------------------------------------------------------------ settings
    fun updateSettings(f: (AppSettings) -> AppSettings) {
        _settings.value = f(_settings.value)
        settingsSaveJob?.cancel()
        settingsSaveJob = viewModelScope.launch {
            delay(500)
            val s = _settings.value
            withContext(Dispatchers.IO) { storage.saveSettings(s) }
        }
    }

    fun updateConnection(f: (Connection) -> Connection) = updateSettings { s ->
        val cur = s.connection
        s.copy(connections = s.connections.map { if (it.id == cur.id) f(it) else it })
    }

    fun updatePreset(f: (Preset) -> Preset) = updateSettings { s ->
        val cur = s.preset
        val next = f(cur)
        s.copy(presets = s.presets.map { if (it.name == cur.name) next else it }, activePreset = next.name)
    }

    suspend fun fetchModels(): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching { LlmClient.listModels(settings.value.connection) }
    }

    private fun addPresets(list: List<Preset>, activate: Boolean) = updateSettings { s ->
        var presets = s.presets
        var last = s.activePreset
        for (p in list) {
            var name = p.name
            var n = 2
            while (presets.any { it.name == name }) name = "${p.name} (${n++})"
            presets = presets + p.copy(name = name)
            last = name
        }
        s.copy(presets = presets, activePreset = if (activate) last else s.activePreset)
    }

    private fun addRegexScripts(list: List<RegexScript>) = updateSettings { s ->
        val known = s.regex.map { it.id }.toSet()
        s.copy(regex = s.regex + list.filter { it.id !in known })
    }

    fun importPreset(uri: Uri) = viewModelScope.launch {
        val r = withContext(Dispatchers.IO) {
            runCatching {
                val text = readUri(uri).toString(Charsets.UTF_8)
                val name = displayName(uri).substringBeforeLast('.').ifBlank { "Imported" }
                val o = Json.parseObj(text)
                Preset.fromSillyTavern(name, o) to RegexScript.parseAny(o)
            }
        }
        r.onSuccess { (p, regex) ->
            addPresets(listOf(p), activate = true)
            // Preset scripts live in the preset; drop copies an older version put in the global list.
            if (regex.isNotEmpty()) {
                val ids = regex.map { it.id }.toSet()
                updateSettings { s -> s.copy(regex = s.regex.filter { it.id !in ids }) }
            }
            val on = p.modules.count { it.enabled && !it.marker }
            toast("Imported \"${p.name}\" · ${p.modules.size} prompts ($on on)" +
                if (regex.isNotEmpty()) " · ${regex.size} regex" else "")
        }.onFailure { toast("Couldn't import preset: ${it.message}") }
    }

    // ------------------------------------------------------------ regex
    /** Scripts in the order SillyTavern runs them: global, then the active preset's, then the characters'. */
    fun regexFor(members: List<Character>): List<RegexScript> {
        val s = settings.value
        val chars = if (s.characterRegex) members.flatMap { it.regex } else emptyList()
        val key = Triple(s.regex, s.preset.regex, chars)
        regexCache?.let { if (it.first == key) return it.second }
        val list = (s.regex + s.preset.regex + chars).distinctBy { it.id }
        regexCache = key to list
        return list
    }

    // Same list instance for unchanged inputs, so the JS engine and display cache can reuse work.
    private var regexCache: Pair<Triple<List<RegexScript>, List<RegexScript>, List<RegexScript>>, List<RegexScript>>? = null

    fun togglePresetRegex(id: String) = updatePreset { p ->
        p.copy(regex = p.regex.map { if (it.id == id) it.copy(disabled = !it.disabled) else it })
    }

    fun saveRegex(r: RegexScript) = updateSettings { s ->
        if (s.regex.any { it.id == r.id }) s.copy(regex = s.regex.map { if (it.id == r.id) r else it })
        else s.copy(regex = s.regex + r)
    }

    fun deleteRegex(id: String) = updateSettings { s -> s.copy(regex = s.regex.filter { it.id != id }) }

    fun moveRegex(id: String, delta: Int) = updateSettings { s ->
        val i = s.regex.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j !in s.regex.indices) s
        else s.copy(regex = s.regex.toMutableList().apply { add(j, removeAt(i)) })
    }

    fun importRegex(uris: List<Uri>) = viewModelScope.launch {
        val found = withContext(Dispatchers.IO) {
            uris.flatMap { uri -> runCatching { RegexScript.parseAny(Json.parse(readUri(uri).toString(Charsets.UTF_8))) }.getOrDefault(emptyList()) }
        }
        if (found.isEmpty()) toast("No regex scripts found in that file")
        else { addRegexScripts(found); toast("Imported ${found.size} regex script${if (found.size > 1) "s" else ""}") }
    }

    fun exportRegex(uri: Uri) = viewModelScope.launch {
        val list = settings.value.regex.map { it.toJson() }
        val r = withContext(Dispatchers.IO) {
            runCatching { writeUri(uri, Json.stringify(list, pretty = true).toByteArray()) }
        }
        toast(r.fold({ "Exported ${list.size} scripts" }, { "Export failed: ${it.message}" }))
    }

    // ------------------------------------------------------------ characters
    fun importCharacters(uris: List<Uri>) = viewModelScope.launch {
        var lastError: String? = null
        val added = ArrayList<CharacterSummary>()
        withContext(Dispatchers.IO) {
            for (uri in uris) {
                runCatching { added.add(storage.summarize(storage.importCard(readUri(uri)))) }
                    .onFailure { lastError = it.message }
            }
            if (added.isNotEmpty()) {
                val all = added + characters.value
                storage.saveIndex(all)
                characters.value = all
            }
        }
        added.forEach { avatarVersion[it.id] = System.currentTimeMillis() }
        val ok = added.size
        toast(
            when {
                ok > 0 && lastError == null -> if (ok == 1) "Imported ${added[0].name}" else "Imported $ok characters"
                ok > 0 -> "Imported $ok, some failed: $lastError"
                else -> "Import failed: $lastError"
            },
        )
    }

    suspend fun loadCharacter(id: String): Character? = withContext(Dispatchers.IO) { storage.loadCharacter(id) }

    /** Saves an edited or new character. Returns its id. */
    suspend fun saveCharacter(c: Character, avatar: ByteArray?): String = withContext(Dispatchers.IO) {
        val id = c.id.ifBlank { storage.newCharacterId(c.name) }
        val ch = c.copy(id = id)
        storage.saveCharacter(ch)
        if (avatar != null) storage.saveAvatar(id, avatar)
        val old = characters.value.firstOrNull { it.id == id }
        val sum = storage.summarize(ch, old?.lastActive ?: System.currentTimeMillis(), old?.favorite ?: false)
        val list = if (old == null) listOf(sum) + characters.value else characters.value.map { if (it.id == id) sum else it }
        storage.saveIndex(list)
        characters.value = list
        withContext(Dispatchers.Main) {
            if (avatar != null) avatarVersion[id] = System.currentTimeMillis()
            chat?.let { s -> if (s.members.any { it.id == id }) s.members = s.members.map { if (it.id == id) ch else it } }
        }
        id
    }

    fun deleteCharacter(id: String) = viewModelScope.launch {
        withContext(Dispatchers.IO) {
            storage.deleteCharacter(id)
            val list = characters.value.filter { it.id != id }
            storage.saveIndex(list)
            characters.value = list
            // Drop the character from any group it was in.
            groups.value = groups.value.map { g ->
                if (id in g.members) g.copy(members = g.members - id, disabled = g.disabled - id).also { storage.saveGroup(it) } else g
            }
        }
        if (chat?.members?.any { it.id == id } == true) chat = null
    }

    fun toggleFavorite(id: String) = viewModelScope.launch(Dispatchers.IO) {
        val list = characters.value.map { if (it.id == id) it.copy(favorite = !it.favorite) else it }
        characters.value = list
        storage.saveIndex(list)
    }

    fun exportCharacter(id: String, uri: Uri) = viewModelScope.launch {
        val r = withContext(Dispatchers.IO) {
            runCatching {
                val c = storage.loadCharacter(id) ?: error("Character not found")
                writeUri(uri, storage.exportCard(c))
                c.name
            }
        }
        toast(r.fold({ "Exported $it" }, { "Export failed: ${it.message}" }))
    }

    private fun touch(key: String) {
        val now = System.currentTimeMillis()
        if (Group.isGroupKey(key)) {
            val id = Group.idFromKey(key)
            val list = groups.value.map { if (it.id == id) it.copy(lastActive = now) else it }
            groups.value = list
            list.firstOrNull { it.id == id }?.let { g -> viewModelScope.launch(Dispatchers.IO) { storage.saveGroup(g) } }
            return
        }
        val list = characters.value.map { if (it.id == key) it.copy(lastActive = now) else it }
            .sortedByDescending { it.lastActive }
        characters.value = list
        viewModelScope.launch(Dispatchers.IO) { storage.saveIndex(list) }
    }

    // ------------------------------------------------------------ groups
    fun saveGroup(g: Group): String {
        val id = g.id.ifBlank { storage.newGroupId(g.name) }
        val group = g.copy(id = id, lastActive = if (g.lastActive == 0L) System.currentTimeMillis() else g.lastActive)
        groups.value = if (groups.value.any { it.id == id }) groups.value.map { if (it.id == id) group else it } else groups.value + group
        viewModelScope.launch(Dispatchers.IO) { storage.saveGroup(group) }
        chat?.let { s ->
            if (s.group?.id == id) {
                s.group = group
                viewModelScope.launch {
                    s.members = withContext(Dispatchers.IO) { group.members.mapNotNull { storage.loadCharacter(it) } }
                }
            }
        }
        return id
    }

    fun deleteGroup(id: String) {
        groups.value = groups.value.filter { it.id != id }
        if (chat?.group?.id == id) chat = null
        viewModelScope.launch(Dispatchers.IO) { storage.deleteGroup(id) }
    }

    fun toggleGroupFavorite(id: String) {
        groups.value.firstOrNull { it.id == id }?.let { saveGroup(it.copy(favorite = !it.favorite)) }
    }

    // ------------------------------------------------------------ chats
    fun openChat(charId: String, fileName: String? = null, forceNew: Boolean = false) =
        openKey(charId, fileName, forceNew)

    fun openGroup(groupId: String, fileName: String? = null, forceNew: Boolean = false) =
        openKey(Group.chatKeyFor(groupId), fileName, forceNew)

    /** Opens a chat for a character id or a group chat key. */
    fun openKey(key: String, fileName: String? = null, forceNew: Boolean = false) = viewModelScope.launch {
        chat?.let { it.stop(); it.flush() }
        val session = withContext(Dispatchers.IO) {
            val group = if (Group.isGroupKey(key)) groups.value.firstOrNull { it.id == Group.idFromKey(key) } else null
            val members = if (group != null) group.members.mapNotNull { storage.loadCharacter(it) }
            else listOfNotNull(storage.loadCharacter(key))
            if (members.isEmpty()) return@withContext null
            val file = when {
                forceNew -> null
                fileName != null -> storage.chatFile(key, fileName).takeIf { it.exists() }
                else -> storage.latestChat(key)
            }
            if (file != null) {
                val data = runCatching { ChatStore.read(file) }.getOrNull()
                if (data != null) return@withContext ChatSession(this@MainViewModel, key, members, group, file, data.header, data.messages)
            }
            newChat(key, members, group)
        }
        if (session == null) { toast("Couldn't open chat — no characters found"); return@launch }
        chat = session
        touch(key)
        if (backStack.lastOrNull() !is Screen.Chat) navigate(Screen.Chat(key))
    }

    private fun newChatFile(key: String, title: String): File {
        val name = "$title - ${stDate()}".replace(Regex("[\\\\/:*?\"<>|]"), "_") + ".jsonl"
        return storage.chatFile(key, name)
    }

    private fun newChat(key: String, members: List<Character>, group: Group?): ChatSession {
        val persona = settings.value.persona
        val title = group?.name ?: members.first().name
        val file = newChatFile(key, title)
        val header = ChatStore.newHeader(persona.name, title)
        // Greetings from every (active) member; in a group each gets their own first message.
        val greeters = if (group != null) members.filter { it.id in group.active } else members
        val msgs = greeters.mapNotNull { c ->
            val macros = Macros(mapOf("user" to persona.name, "char" to c.name))
            val greetings = (listOf(c.firstMes) + c.alternateGreetings).filter { it.isNotBlank() }.map { macros.expand(it) }
            if (greetings.isEmpty()) null
            else ChatMessage(isUser = false, name = c.name, text = greetings[0], swipes = greetings)
        }
        ChatStore.write(file, header, msgs)
        return ChatSession(this, key, members, group, file, header, msgs)
    }

    /** Starts a new chat containing everything up to and including [messageId]. */
    fun branch(s: ChatSession, messageId: Long) = viewModelScope.launch {
        val idx = s.messages.indexOfFirst { it.id == messageId }
        if (idx < 0) return@launch
        val copy = s.messages.subList(0, idx + 1).toList()
        val title = s.title
        val file = withContext(Dispatchers.IO) {
            @Suppress("UNCHECKED_CAST")
            val header = (deepCopy(s.headerSnapshot()) as? MJObj) ?: ChatStore.newHeader(settings.value.persona.name, title)
            val meta = (header["chat_metadata"] as? MJObj) ?: MJObj()
            meta["main_chat"] = s.file.name.removeSuffix(".jsonl")
            header["chat_metadata"] = meta
            val f = newChatFile(s.key, "Branch #${idx + 1} - $title")
            ChatStore.write(f, header, copy)
            f
        }
        s.stop(); s.flush()
        openKey(s.key, file.name)
        toast("Branched at message ${idx + 1}")
    }

    suspend fun listChats(key: String): List<ChatInfo> = withContext(Dispatchers.IO) { storage.listChats(key) }

    fun deleteChat(key: String, fileName: String) = viewModelScope.launch {
        val current = chat
        val isOpen = current != null && current.key == key && current.file.name == fileName
        if (isOpen) current!!.stop()
        withContext(Dispatchers.IO) { storage.chatFile(key, fileName).delete() }
        if (isOpen) openKey(key)
    }

    // ------------------------------------------------------------ lorebooks
    fun booksFor(c: Character): List<Lorebook> = booksFor(listOf(c))

    fun booksFor(members: List<Character>): List<Lorebook> {
        val all = lorebooks.value
        val out = ArrayList<Lorebook>()
        for (c in members) {
            c.book?.let { out.add(it) }
            if (c.linkedWorld.isNotBlank()) {
                all.firstOrNull { it.id == c.linkedWorld || it.name == c.linkedWorld }
                    ?.let { b -> if (out.none { it.id == b.id }) out.add(b) }
            }
        }
        for (id in settings.value.globalLorebooks) {
            all.firstOrNull { it.id == id }?.let { b -> if (out.none { it.id == b.id }) out.add(b) }
        }
        return out
    }

    fun createLorebook(name: String): String {
        val id = storage.newLorebookId(name)
        val b = Lorebook(id, name.ifBlank { "New lorebook" })
        lorebooks.value = (lorebooks.value + b).sortedBy { it.name.lowercase() }
        viewModelScope.launch(Dispatchers.IO) { storage.saveLorebook(b) }
        return id
    }

    fun updateLorebook(b: Lorebook) {
        lorebooks.value = lorebooks.value.map { if (it.id == b.id) b else it }
        loreSaveJobs[b.id]?.cancel()
        loreSaveJobs[b.id] = viewModelScope.launch {
            delay(400)
            withContext(Dispatchers.IO) { storage.saveLorebook(b) }
        }
    }

    fun deleteLorebook(id: String) {
        lorebooks.value = lorebooks.value.filter { it.id != id }
        updateSettings { it.copy(globalLorebooks = it.globalLorebooks - id) }
        viewModelScope.launch(Dispatchers.IO) { storage.deleteLorebook(id) }
    }

    fun toggleGlobal(id: String) = updateSettings {
        it.copy(globalLorebooks = if (id in it.globalLorebooks) it.globalLorebooks - id else it.globalLorebooks + id)
    }

    fun importLorebooks(uris: List<Uri>) = viewModelScope.launch {
        val added = ArrayList<Lorebook>()
        var err: String? = null
        withContext(Dispatchers.IO) {
            for (uri in uris) runCatching {
                val bytes = readUri(uri)
                val name = displayName(uri).substringBeforeLast('.').ifBlank { "Lorebook" }
                val o = if (Cards.isPng(bytes)) {
                    val card = Cards.parse("tmp", Cards.extractJsonFromPng(bytes))
                    card.book?.toStJson() ?: error("That card has no embedded lorebook")
                } else Json.parseObj(bytes.toString(Charsets.UTF_8))
                if (o["entries"] == null) error("Not a lorebook file")
                val id = storage.newLorebookId(name)
                val b = Lorebook.fromJson(id, o, name).let { if (it.name.isBlank()) it.copy(name = name) else it }
                storage.saveLorebook(b)
                added.add(b)
            }.onFailure { err = it.message }
        }
        if (added.isNotEmpty()) lorebooks.value = (lorebooks.value + added).sortedBy { it.name.lowercase() }
        toast(
            if (added.isNotEmpty()) "Imported ${added.size} lorebook${if (added.size > 1) "s" else ""}" +
                (err?.let { " (some failed: $it)" } ?: "")
            else "Import failed: $err",
        )
    }

    fun exportLorebook(id: String, uri: Uri) = viewModelScope.launch {
        val b = lorebooks.value.firstOrNull { it.id == id } ?: return@launch
        val r = withContext(Dispatchers.IO) {
            runCatching { writeUri(uri, Json.stringify(b.toStJson(), pretty = true).toByteArray()) }
        }
        toast(r.fold({ "Exported ${b.name}" }, { "Export failed: ${it.message}" }))
    }

    // ------------------------------------------------------------ backup
    fun exportBackup(uri: Uri, includeKeys: Boolean) = viewModelScope.launch {
        chat?.flush()
        busy = "Creating backup…"
        val s = settings.value
        val r = withContext(Dispatchers.IO) {
            runCatching {
                getApplication<Application>().contentResolver.openOutputStream(uri)!!.use { backup.export(it, s, includeKeys) }
            }
        }
        busy = null
        toast(r.fold({ "Backup saved" }, { "Backup failed: ${it.message}" }))
    }

    fun restoreBackup(uri: Uri) = viewModelScope.launch {
        chat?.let { it.stop(); it.flushBlocking() }
        chat = null
        busy = "Importing…"
        val current = settings.value
        val r = withContext(Dispatchers.IO) {
            runCatching {
                val tmp = File(getApplication<Application>().cacheDir, "restore.zip")
                getApplication<Application>().contentResolver.openInputStream(uri)!!.use { input ->
                    tmp.outputStream().use { input.copyTo(it) }
                }
                try { backup.restore(tmp, current) } finally { tmp.delete() }
            }
        }
        r.onSuccess { result ->
            settingsSaveJob?.cancel()
            reloadAll()
            AvatarVersionBump.bump(avatarVersion, characters.value.map { it.id })
            when (result) {
                is Backup.Result.Restored -> toast("Restored ${result.files} files")
                is Backup.Result.SillyTavern -> {
                    val d = result.data
                    if (d.presets.isNotEmpty()) addPresets(d.presets, activate = false)
                    if (d.regex.isNotEmpty()) addRegexScripts(d.regex)
                    toast("SillyTavern import: ${d.characters} characters, ${d.chats} chats, ${d.groups} groups, " +
                        "${d.lorebooks} lorebooks, ${d.presets.size} presets, ${d.regex.size} regex")
                }
            }
        }.onFailure { toast("Import failed: ${it.message}") }
        busy = null
    }

    // ------------------------------------------------------------ helpers
    private fun readUri(uri: Uri): ByteArray =
        getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("Couldn't read file")

    private fun writeUri(uri: Uri, bytes: ByteArray) {
        getApplication<Application>().contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
    }

    private fun displayName(uri: Uri): String = runCatching {
        getApplication<Application>().contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"

    internal fun onChatActivity(key: String) = touch(key)

    override fun onCleared() {
        chat?.let { it.stop(); it.flushBlocking() }
        super.onCleared()
    }
}

private object AvatarVersionBump {
    fun bump(map: MutableMap<String, Long>, ids: List<String>) {
        val now = System.currentTimeMillis()
        ids.forEach { map[it] = now }
    }
}
