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
import app.hearth.core.Json
import app.hearth.core.LlmClient
import app.hearth.core.Lorebook
import app.hearth.core.Macros
import app.hearth.core.Preset
import app.hearth.core.stDate
import app.hearth.data.Storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Screen {
    data object Characters : Screen
    data class Chat(val charId: String) : Screen
    data class EditCharacter(val charId: String?) : Screen
    data object Settings : Screen
    data object Lorebooks : Screen
    data class EditLorebook(val id: String) : Screen
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val storage = Storage(app.filesDir)

    /** Snapshot state (not a Flow) so text fields bound to settings update synchronously. */
    private val _settings = mutableStateOf(AppSettings())
    val settings: State<AppSettings> get() = _settings
    val characters = MutableStateFlow<List<CharacterSummary>>(emptyList())
    val lorebooks = MutableStateFlow<List<Lorebook>>(emptyList())
    val ready = MutableStateFlow(false)

    val backStack = mutableStateListOf<Screen>(Screen.Characters)
    val toasts = MutableSharedFlow<String>(extraBufferCapacity = 8)

    var chat by mutableStateOf<ChatSession?>(null)
        private set

    /** Bumped when an avatar changes so image caches reload it. */
    val avatarVersion = mutableStateMapOf<String, Long>()

    private var settingsSaveJob: Job? = null
    private val loreSaveJobs = HashMap<String, Job>()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val loaded = storage.loadSettings()
            withContext(Dispatchers.Main) { _settings.value = loaded }
            characters.value = storage.loadIndex().sortedByDescending { it.lastActive }
            lorebooks.value = storage.loadLorebooks()
            ready.value = true
        }
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
        s.copy(
            presets = s.presets.map { if (it.name == cur.name) next else it },
            activePreset = next.name,
        )
    }

    suspend fun fetchModels(): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching { LlmClient.listModels(settings.value.connection) }
    }

    fun importPreset(uri: Uri) = viewModelScope.launch {
        val r = withContext(Dispatchers.IO) {
            runCatching {
                val text = readUri(uri).toString(Charsets.UTF_8)
                val name = displayName(uri).substringBeforeLast('.').ifBlank { "Imported" }
                Preset.fromSillyTavern(name, Json.parseObj(text))
            }
        }
        r.onSuccess { p ->
            updateSettings { s ->
                var name = p.name
                var n = 2
                while (s.presets.any { it.name == name }) name = "${p.name} ($n)".also { n++ }
                s.copy(presets = s.presets + p.copy(name = name), activePreset = name)
            }
            toast("Imported preset \"${p.name}\"")
        }.onFailure { toast("Couldn't import preset: ${it.message}") }
    }

    // ------------------------------------------------------------ characters
    fun importCharacters(uris: List<Uri>) = viewModelScope.launch {
        var ok = 0
        var lastError: String? = null
        val added = ArrayList<CharacterSummary>()
        withContext(Dispatchers.IO) {
            for (uri in uris) {
                runCatching {
                    val bytes = readUri(uri)
                    val json = if (Cards.isPng(bytes)) Cards.extractJsonFromPng(bytes)
                    else Json.parseObj(bytes.toString(Charsets.UTF_8))
                    val name = Cards.parse("tmp", json).name
                    val id = storage.newCharacterId(name)
                    val ch = Cards.parse(id, json)
                    storage.saveCharacter(ch)
                    if (Cards.isPng(bytes)) storage.saveAvatar(id, bytes)
                    added.add(storage.summarize(ch))
                    ok++
                }.onFailure { lastError = it.message }
            }
            if (added.isNotEmpty()) {
                val all = added + characters.value
                storage.saveIndex(all)
                characters.value = all
            }
        }
        added.forEach { avatarVersion[it.id] = System.currentTimeMillis() }
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
            chat?.let { if (it.character.id == id) it.character = ch }
        }
        id
    }

    fun deleteCharacter(id: String) = viewModelScope.launch {
        withContext(Dispatchers.IO) {
            storage.deleteCharacter(id)
            val list = characters.value.filter { it.id != id }
            storage.saveIndex(list)
            characters.value = list
        }
        if (chat?.character?.id == id) chat = null
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
                getApplication<Application>().contentResolver.openOutputStream(uri)!!.use { it.write(storage.exportCard(c)) }
                c.name
            }
        }
        toast(r.fold({ "Exported $it" }, { "Export failed: ${it.message}" }))
    }

    private fun touch(id: String) {
        val now = System.currentTimeMillis()
        val list = characters.value.map { if (it.id == id) it.copy(lastActive = now) else it }
            .sortedByDescending { it.lastActive }
        characters.value = list
        viewModelScope.launch(Dispatchers.IO) { storage.saveIndex(list) }
    }

    // ------------------------------------------------------------ chats
    fun openChat(charId: String, fileName: String? = null, forceNew: Boolean = false) = viewModelScope.launch {
        chat?.let { it.stop(); it.flush() }
        val session = withContext(Dispatchers.IO) {
            val c = storage.loadCharacter(charId) ?: return@withContext null
            val file = when {
                forceNew -> null
                fileName != null -> storage.chatFile(charId, fileName).takeIf { it.exists() }
                else -> storage.latestChat(charId)
            }
            if (file != null) {
                val data = runCatching { ChatStore.read(file) }.getOrNull()
                if (data != null) return@withContext ChatSession(this@MainViewModel, c, file, data.header, data.messages)
            }
            newChat(c)
        }
        if (session == null) { toast("Couldn't open character"); return@launch }
        chat = session
        touch(charId)
        if (backStack.lastOrNull() !is Screen.Chat) navigate(Screen.Chat(charId))
    }

    private fun newChat(c: Character): ChatSession {
        val persona = settings.value.persona
        val macros = Macros(mapOf("user" to persona.name, "char" to c.name))
        val name = "${c.name} - ${stDate()}".replace(Regex("[\\\\/:*?\"<>|]"), "_") + ".jsonl"
        val file = storage.chatFile(c.id, name)
        val header = ChatStore.newHeader(persona.name, c.name)
        val greetings = (listOf(c.firstMes) + c.alternateGreetings).filter { it.isNotBlank() }.map { macros.expand(it) }
        val msgs = if (greetings.isEmpty()) emptyList()
        else listOf(ChatMessage(isUser = false, name = c.name, text = greetings[0], swipes = greetings))
        ChatStore.write(file, header, msgs)
        return ChatSession(this, c, file, header, msgs)
    }

    suspend fun listChats(charId: String): List<ChatInfo> = withContext(Dispatchers.IO) { storage.listChats(charId) }

    fun deleteChat(charId: String, fileName: String) = viewModelScope.launch {
        val current = chat
        val isOpen = current != null && current.character.id == charId && current.file.name == fileName
        if (isOpen) current!!.stop()
        withContext(Dispatchers.IO) { storage.chatFile(charId, fileName).delete() }
        if (isOpen) openChat(charId)
    }

    // ------------------------------------------------------------ lorebooks
    fun booksFor(c: Character): List<Lorebook> {
        val all = lorebooks.value
        val out = ArrayList<Lorebook>()
        c.book?.let { out.add(it) }
        if (c.linkedWorld.isNotBlank()) {
            all.firstOrNull { it.id == c.linkedWorld || it.name == c.linkedWorld }?.let { out.add(it) }
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
                    // A character card: pull out its embedded lorebook.
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
            runCatching {
                getApplication<Application>().contentResolver.openOutputStream(uri)!!.use {
                    it.write(Json.stringify(b.toStJson(), pretty = true).toByteArray())
                }
            }
        }
        toast(r.fold({ "Exported ${b.name}" }, { "Export failed: ${it.message}" }))
    }

    // ------------------------------------------------------------ helpers
    private fun readUri(uri: Uri): ByteArray =
        getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("Couldn't read file")

    private fun displayName(uri: Uri): String = runCatching {
        getApplication<Application>().contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"

    internal fun onChatActivity(charId: String) = touch(charId)

    override fun onCleared() {
        chat?.let { it.stop(); it.flushBlocking() }
        super.onCleared()
    }
}
