package app.hearth.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.hearth.MainViewModel
import app.hearth.core.ApiKind
import app.hearth.core.AppSettings
import app.hearth.core.Connection
import app.hearth.core.InstructTemplate
import app.hearth.core.Preset
import kotlinx.coroutines.launch
import java.util.Locale

private val TABS = listOf("Connection", "Preset", "Prompts", "Persona & Lore", "Display", "Data")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel) {
    val s by vm.settings
    var tab by rememberSaveable { mutableStateOf(0) }
    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    navigationIcon = { IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                    title = { Text("Settings") },
                )
                ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
                    TABS.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
                }
            }
        },
        snackbarHost = { SnackbarHost(rememberToasts(vm)) },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp).navigationBarsPadding(),
        ) {
            when (tab) {
                0 -> ConnectionTab(vm, s)
                1 -> PresetTab(vm, s)
                2 -> PromptsTab(vm, s.preset)
                3 -> PersonaTab(vm, s)
                4 -> DisplayTab(vm, s)
                else -> DataTab(vm)
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

private data class Endpoint(val label: String, val kind: ApiKind, val url: String, val extra: Boolean)

private val ENDPOINTS = listOf(
    Endpoint("OpenRouter", ApiKind.OPENAI_CHAT, "https://openrouter.ai/api/v1", true),
    Endpoint("OpenAI", ApiKind.OPENAI_CHAT, "https://api.openai.com/v1", false),
    Endpoint("Anthropic", ApiKind.ANTHROPIC, "https://api.anthropic.com/v1", false),
    Endpoint("DeepSeek", ApiKind.OPENAI_CHAT, "https://api.deepseek.com/v1", false),
    Endpoint("Mistral", ApiKind.OPENAI_CHAT, "https://api.mistral.ai/v1", false),
    Endpoint("KoboldCpp (LAN)", ApiKind.TEXT_COMPLETION, "http://192.168.1.10:5001/v1", true),
    Endpoint("Ollama (LAN)", ApiKind.OPENAI_CHAT, "http://192.168.1.10:11434/v1", true),
)

@Composable
private fun ConnectionTab(vm: MainViewModel, s: AppSettings) {
    val c = s.connection
    val scope = rememberCoroutineScope()
    var showKey by remember { mutableStateOf(false) }
    var models by remember { mutableStateOf<List<String>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }

    SectionTitle("Profile")
    DropdownField("Active connection", s.connections, c, { it.name }, { sel ->
        vm.updateSettings { it.copy(activeConnection = sel.id) }
    })
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
        OutlinedButton(onClick = {
            val id = "c" + System.currentTimeMillis().toString(36)
            vm.updateSettings { it.copy(connections = it.connections + c.copy(id = id, name = c.name + " copy"), activeConnection = id) }
        }) { Text("Duplicate") }
        OutlinedButton(onClick = { renaming = true }) { Text("Rename") }
        OutlinedButton(enabled = s.connections.size > 1, onClick = {
            vm.updateSettings { st ->
                val rest = st.connections.filter { it.id != c.id }
                st.copy(connections = rest, activeConnection = rest.first().id)
            }
        }) { Text("Delete") }
    }

    SectionTitle("API")
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ENDPOINTS.forEach { e ->
            AssistChip(onClick = {
                vm.updateConnection { it.copy(kind = e.kind, baseUrl = e.url, extraSamplers = e.extra) }
            }, label = { Text(e.label) })
        }
    }
    DropdownField("API type", ApiKind.entries, c.kind, { it.label }, { k -> vm.updateConnection { it.copy(kind = k) } })
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        c.baseUrl, { v -> vm.updateConnection { it.copy(baseUrl = v.trim()) } },
        label = { Text("Base URL") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        c.apiKey, { v -> vm.updateConnection { it.copy(apiKey = v.trim()) } },
        label = { Text("API key") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = { TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "Hide" else "Show") } },
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        c.model, { v -> vm.updateConnection { it.copy(model = v.trim()) } },
        label = { Text("Model") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
    )
    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(enabled = !loading, onClick = {
            loading = true
            scope.launch {
                vm.fetchModels()
                    .onSuccess { if (it.isEmpty()) vm.toast("Connected, but the server listed no models") else models = it }
                    .onFailure { vm.toast("Couldn't load models: ${it.message}") }
                loading = false
            }
        }) { Text(if (loading) "Connecting…" else "Browse models") }
    }
    SwitchRow(
        "Send extended samplers", c.extraSamplers, { v -> vm.updateConnection { it.copy(extraSamplers = v) } },
        hint = "top_k, min_p and repetition_penalty. Turn off for official OpenAI.",
    )
    Text(
        "Keys are stored only in this app's private storage on your device.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )

    models?.let { list -> ModelPicker(list, onDismiss = { models = null }) { m -> vm.updateConnection { it.copy(model = m) } } }
    if (renaming) TextInputDialog("Rename connection", c.name, "Name", { renaming = false }) { n ->
        vm.updateConnection { it.copy(name = n) }
    }
}

@Composable
private fun ModelPicker(models: List<String>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var q by remember { mutableStateOf("") }
    val shown = remember(q, models) { models.filter { it.contains(q.trim(), true) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Models (${models.size})") },
        text = {
            Column {
                OutlinedTextField(q, { q = it }, label = { Text("Filter") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    items(shown, key = { it }) { m ->
                        Text(
                            m, style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.fillMaxWidth().clickable { onPick(m); onDismiss() }.padding(vertical = 10.dp),
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun PresetTab(vm: MainViewModel, s: AppSettings) {
    val p = s.preset
    var naming by remember { mutableStateOf<String?>(null) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importPreset(uri)
    }
    SectionTitle("Preset")
    DropdownField("Active preset", s.presets, p, { it.name }, { sel -> vm.updateSettings { it.copy(activePreset = sel.name) } })
    Row(Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { naming = "save" }) { Text("Save as") }
        OutlinedButton(onClick = { naming = "rename" }) { Text("Rename") }
        OutlinedButton(onClick = { importer.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) }) {
            Text("Import ST preset")
        }
        OutlinedButton(enabled = s.presets.size > 1, onClick = {
            vm.updateSettings { st ->
                val rest = st.presets.filter { it.name != p.name }
                st.copy(presets = rest, activePreset = rest.first().name)
            }
        }) { Text("Delete") }
    }

    SectionTitle("Samplers")
    SliderRow("Temperature", p.temperature.toFloat(), 0f..2f, ::fmt2, { v -> vm.updatePreset { it.copy(temperature = v.toDouble()) } })
    SliderRow("Top P", p.topP.toFloat(), 0f..1f, ::fmt2, { v -> vm.updatePreset { it.copy(topP = v.toDouble()) } })
    SliderRow("Min P", p.minP.toFloat(), 0f..1f, ::fmt2, { v -> vm.updatePreset { it.copy(minP = v.toDouble()) } })
    SliderRow("Top K", p.topK.toFloat(), 0f..200f, { it.toInt().toString() }, { v -> vm.updatePreset { it.copy(topK = v.toInt()) } }, step = 1f)
    SliderRow("Repetition penalty", p.repetitionPenalty.toFloat(), 1f..1.5f, ::fmt2, { v -> vm.updatePreset { it.copy(repetitionPenalty = v.toDouble()) } })
    SliderRow("Frequency penalty", p.frequencyPenalty.toFloat(), -2f..2f, ::fmt2, { v -> vm.updatePreset { it.copy(frequencyPenalty = v.toDouble()) } })
    SliderRow("Presence penalty", p.presencePenalty.toFloat(), -2f..2f, ::fmt2, { v -> vm.updatePreset { it.copy(presencePenalty = v.toDouble()) } })

    SectionTitle("Length")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        IntField("Max response tokens", p.maxTokens, { v -> vm.updatePreset { it.copy(maxTokens = v.coerceAtLeast(1)) } }, Modifier.weight(1f))
        IntField("Context size", p.contextSize, { v -> vm.updatePreset { it.copy(contextSize = v.coerceAtLeast(512)) } }, Modifier.weight(1f))
    }
    SwitchRow("Stream responses", p.streaming, { v -> vm.updatePreset { it.copy(streaming = v) } })

    SectionTitle("Text completion")
    DropdownField("Instruct template", InstructTemplate.ALL, InstructTemplate.byName(p.instruct), { it.name }, { t ->
        vm.updatePreset { it.copy(instruct = t.name) }
    })
    SwitchRow("Include names", p.namesInText, { v -> vm.updatePreset { it.copy(namesInText = v) } }, hint = "Prefix turns with \"Name:\"")
    OutlinedTextField(
        p.stopStrings.joinToString("\n"),
        { v -> vm.updatePreset { it.copy(stopStrings = v.split('\n').filter { s -> s.isNotEmpty() }) } },
        label = { Text("Custom stop strings (one per line)") }, minLines = 2, modifier = Modifier.fillMaxWidth(),
    )

    naming?.let { mode ->
        TextInputDialog(
            if (mode == "save") "Save preset as" else "Rename preset", p.name, "Preset name", { naming = null },
        ) { name ->
            vm.updateSettings { st ->
                if (st.presets.any { it.name == name && it.name != p.name }) return@updateSettings st
                if (mode == "save") st.copy(presets = st.presets + p.copy(name = name), activePreset = name)
                else st.copy(presets = st.presets.map { if (it.name == p.name) it.copy(name = name) else it }, activePreset = name)
            }
        }
    }
}

@Composable
private fun PromptsTab(vm: MainViewModel, p: Preset) {
    PromptModulesEditor(vm, p)
    SectionTitle("Other prompts")
    PromptField("Assistant prefill", p.prefill, "Claude / text completion: the reply starts with this text.") { v ->
        vm.updatePreset { it.copy(prefill = v) }
    }
    PromptField("New chat marker", p.newChatPrompt, null) { v -> vm.updatePreset { it.copy(newChatPrompt = v) } }
    PromptField("Continue nudge", p.continueNudge, null) { v -> vm.updatePreset { it.copy(continueNudge = v) } }
    PromptField("Impersonation prompt", p.impersonatePrompt, null) { v -> vm.updatePreset { it.copy(impersonatePrompt = v) } }
    PromptField("Group nudge", p.groupNudge, "Sent last in group chats so the model answers as the right character.") { v ->
        vm.updatePreset { it.copy(groupNudge = v) }
    }
    SwitchRow(
        "Prefer character prompts", p.preferCharPrompt, { v -> vm.updatePreset { it.copy(preferCharPrompt = v) } },
        hint = "Use a card's own system prompt / post-history instructions when it has them.",
    )
    SwitchRow(
        "Squash system messages", p.squashSystem, { v -> vm.updatePreset { it.copy(squashSystem = v) } },
        hint = "Merge consecutive system messages into one (helps some models).",
    )
}

@Composable
private fun PromptField(label: String, value: String, hint: String?, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, onChange, label = { Text(label) }, minLines = 2, maxLines = 10,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
    if (hint != null) {
        Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PersonaTab(vm: MainViewModel, s: AppSettings) {
    SectionTitle("Your persona")
    OutlinedTextField(
        s.persona.name, { v -> vm.updateSettings { it.copy(persona = it.persona.copy(name = v)) } },
        label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        s.persona.description, { v -> vm.updateSettings { it.copy(persona = it.persona.copy(description = v)) } },
        label = { Text("Description") }, minLines = 3, maxLines = 10, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )

    SectionTitle("World info")
    val w = s.wi
    SliderRow("Scan depth (messages)", w.scanDepth.toFloat(), 0f..20f, { it.toInt().toString() },
        { v -> vm.updateSettings { it.copy(wi = it.wi.copy(scanDepth = v.toInt())) } }, step = 1f)
    SliderRow("Budget (% of context)", w.budgetPercent.toFloat(), 1f..100f, { "${it.toInt()}%" },
        { v -> vm.updateSettings { it.copy(wi = it.wi.copy(budgetPercent = v.toInt())) } }, step = 1f)
    SwitchRow("Recursive scanning", w.recursive, { v -> vm.updateSettings { it.copy(wi = it.wi.copy(recursive = v)) } },
        hint = "Activated entries can trigger other entries")
    SwitchRow("Case-sensitive keys", w.caseSensitive, { v -> vm.updateSettings { it.copy(wi = it.wi.copy(caseSensitive = v)) } })
    SwitchRow("Match whole words", w.matchWholeWords, { v -> vm.updateSettings { it.copy(wi = it.wi.copy(matchWholeWords = v)) } })
}

@Composable
private fun DisplayTab(vm: MainViewModel, s: AppSettings) {
    SectionTitle("Display")
    SliderRow("Text size", s.fontScale, 0.8f..1.4f, { "${(it * 100).toInt()}%" },
        { v -> vm.updateSettings { it.copy(fontScale = v) } }, step = 0.05f)
    SwitchRow("Show avatars in chat", s.showAvatars, { v -> vm.updateSettings { it.copy(showAvatars = v) } })
    SectionTitle("HTML")
    SwitchRow("Render HTML in messages", s.renderHtml, { v -> vm.updateSettings { it.copy(renderHtml = v) } },
        hint = "Status panels, styled boxes, <details>, tables and images. Plain messages stay on the fast renderer.")
    SwitchRow("Allow scripts in HTML", s.allowHtmlScripts, { v -> vm.updateSettings { it.copy(allowHtmlScripts = v) } },
        hint = "Off by default. Only turn on for cards and regex scripts you trust.")
    SectionTitle("Regex")
    SwitchRow("Run character regex scripts", s.characterRegex, { v -> vm.updateSettings { it.copy(characterRegex = v) } },
        hint = "Scripts embedded in character cards.")
    OutlinedButton(onClick = { vm.navigate(app.hearth.Screen.Regex) }) { Text("Manage regex scripts (${s.regex.size})") }
    SectionTitle("Advanced")
    SliderRow("Characters per token (estimate)", s.tokenRatio.toFloat(), 2f..5f, ::fmt2,
        { v -> vm.updateSettings { it.copy(tokenRatio = v.toDouble()) } }, step = 0.05f)
    Text(
        "Used to fit the prompt into the context window without a tokenizer. Lower it if you hit context-length errors.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun DataTab(vm: MainViewModel) {
    var includeKeys by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf<android.net.Uri?>(null) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) vm.exportBackup(uri, includeKeys)
    }
    val restorer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) confirmRestore = uri
    }
    val busy = vm.busy

    SectionTitle("Backup")
    Text(
        "Saves characters, chats, groups, lorebooks, presets, regex and settings into one zip.",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    SwitchRow("Include API keys", includeKeys, { includeKeys = it }, hint = "Leave off if you'll share the file.")
    Button(enabled = busy == null, onClick = {
        exporter.launch("hearth-backup-" + java.text.SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(java.util.Date()) + ".zip")
    }) { Text("Create backup") }

    SectionTitle("Restore / import")
    Text(
        "Pick a Hearth backup to restore it, or a zip of your SillyTavern data folder " +
            "(data/default-user) to import characters, chats, group chats, lorebooks, presets and regex.",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    OutlinedButton(enabled = busy == null, onClick = {
        restorer.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
    }) { Text("Choose zip…") }
    if (busy != null) {
        Text(busy, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 12.dp))
    }

    confirmRestore?.let { uri ->
        ConfirmDialog(
            "Import this zip?",
            "A Hearth backup replaces files with the same names (your current chats are kept otherwise). " +
                "A SillyTavern folder is added alongside what you already have.",
            confirm = "Import",
            onDismiss = { confirmRestore = null },
        ) { vm.restoreBackup(uri) }
    }
}

private fun fmt2(v: Float) = String.format(Locale.US, "%.2f", v)
