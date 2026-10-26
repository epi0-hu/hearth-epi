package app.hearth.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.hearth.MainViewModel
import app.hearth.Screen
import app.hearth.core.Macros
import app.hearth.core.RegexEngine
import app.hearth.core.RegexPlacement
import app.hearth.core.RegexScript
import java.util.UUID

private fun summary(r: RegexScript): String {
    val where = r.placement.mapNotNull { RegexPlacement.labels[it] }.joinToString(", ").ifEmpty { "nowhere" }
    val mode = when {
        r.markdownOnly && r.promptOnly -> "display + prompt only"
        r.markdownOnly -> "display only"
        r.promptOnly -> "prompt only"
        else -> "edits messages"
    }
    return "$where · $mode"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegexListScreen(vm: MainViewModel) {
    val s by vm.settings
    var menu by remember { mutableStateOf(false) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.importRegex(uris)
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.exportRegex(uri)
    }
    val charScripts = vm.chat?.members.orEmpty().flatMap { m -> m.regex.map { m.name to it } }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = { Text("Regex scripts") },
                actions = {
                    TextButton(onClick = { importer.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) }) {
                        Text("Import")
                    }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("Export all") }, enabled = s.regex.isNotEmpty(), onClick = {
                            menu = false; exporter.launch("hearth-regex.json")
                        })
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { vm.navigate(Screen.EditRegex(null)) },
                icon = { Icon(Icons.Default.Add, null) }, text = { Text("New") })
        },
        snackbarHost = { SnackbarHost(rememberToasts(vm)) },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = pad.calculateBottomPadding() + 88.dp),
        ) {
            if (s.regex.isEmpty()) item {
                Text(
                    "No scripts yet. Import SillyTavern regex JSON (single scripts, exported lists, or a preset that " +
                        "contains them) or create one. Scripts run top to bottom.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            }
            items(s.regex, key = { it.id }) { r ->
                val i = s.regex.indexOf(r)
                Row(
                    Modifier.fillMaxWidth().clickable { vm.navigate(Screen.EditRegex(r.id)) }
                        .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Switch(checked = !r.disabled, onCheckedChange = { vm.saveRegex(r.copy(disabled = !it)) })
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(r.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(summary(r), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { vm.moveRegex(r.id, -1) }, enabled = i > 0, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.KeyboardArrowUp, "Move up")
                    }
                    IconButton(onClick = { vm.moveRegex(r.id, +1) }, enabled = i < s.regex.lastIndex, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.KeyboardArrowDown, "Move down")
                    }
                }
            }
            if (charScripts.isNotEmpty()) {
                item {
                    SectionTitle(
                        "From the open chat's characters" + if (!s.characterRegex) " (switched off in Settings)" else "",
                        Modifier.padding(horizontal = 16.dp),
                    )
                }
                items(charScripts, key = { "c:" + it.first + it.second.id }) { (who, r) ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                        Text("${r.name} · $who", style = MaterialTheme.typography.bodyLarge)
                        Text(summary(r) + if (r.disabled) " · off" else "", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegexEditScreen(vm: MainViewModel, id: String?) {
    val existing = remember(id) { vm.settings.value.regex.firstOrNull { it.id == id } }
    var r by remember(id) {
        mutableStateOf(existing ?: RegexScript(UUID.randomUUID().toString(), "New script", "", placement = listOf(RegexPlacement.AI_OUTPUT)))
    }
    var trimText by remember(id) { mutableStateOf(r.trimStrings.joinToString("\n")) }
    var test by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    val persona = vm.settings.value.persona.name
    val charName = vm.chat?.character?.name ?: "Character"

    fun save() {
        vm.saveRegex(r.copy(trimStrings = trimText.split('\n').filter { it.isNotEmpty() }))
        vm.back()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = { Text(if (existing == null) "New regex" else "Edit regex") },
                actions = { IconButton(onClick = { save() }) { Icon(Icons.Default.Check, "Save") } },
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp).navigationBarsPadding(),
        ) {
            OutlinedTextField(r.name, { r = r.copy(name = it) }, label = { Text("Name") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            val compiles = remember(r.find) { r.find.isBlank() || RegexEngine.compile(r.find) != null }
            OutlinedTextField(
                r.find, { r = r.copy(find = it) }, label = { Text("Find regex, e.g. /pattern/gi") },
                isError = !compiles, supportingText = { if (!compiles) Text("This pattern doesn't compile") },
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            OutlinedTextField(
                r.replace, { r = r.copy(replace = it) }, label = { Text("Replace with") }, minLines = 2, maxLines = 12,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Use \$1, \$2 or \$<name> for groups and {{match}} for the whole match. Macros like {{char}} work.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                trimText, { trimText = it }, label = { Text("Trim out (one per line)") }, minLines = 1, maxLines = 6,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )

            SectionTitle("Affects")
            RegexPlacement.labels.forEach { (place, label) ->
                Row(
                    Modifier.fillMaxWidth().clickable {
                        r = r.copy(placement = if (place in r.placement) r.placement - place else r.placement + place)
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = place in r.placement, onCheckedChange = null)
                    Text(label)
                }
            }

            SectionTitle("Behavior")
            SwitchRow("Enabled", !r.disabled, { r = r.copy(disabled = !it) })
            SwitchRow("Alter chat display only", r.markdownOnly, { r = r.copy(markdownOnly = it) },
                hint = "Changes what you see, not the saved message. Use this for HTML status panels.")
            SwitchRow("Alter outgoing prompt only", r.promptOnly, { r = r.copy(promptOnly = it) },
                hint = "Changes what's sent to the model, not what you see.")
            SwitchRow("Run on edit", r.runOnEdit, { r = r.copy(runOnEdit = it) })
            DropdownField("Macros in find regex", listOf(0, 1, 2), r.substituteMacros,
                { listOf("Don't substitute", "Substitute (raw)", "Substitute (escaped)")[it] }, { r = r.copy(substituteMacros = it) })
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OptionalIntField("Min depth", r.minDepth, { r = r.copy(minDepth = it) }, Modifier.weight(1f))
                OptionalIntField("Max depth", r.maxDepth, { r = r.copy(maxDepth = it) }, Modifier.weight(1f))
            }
            Text("Depth 0 is the newest message. Leave empty for no limit.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            SectionTitle("Test")
            OutlinedTextField(test, { test = it }, label = { Text("Sample text") }, minLines = 2,
                modifier = Modifier.fillMaxWidth())
            val result = remember(test, r, trimText) {
                val ex = Macros(mapOf("user" to persona, "char" to charName))::expand
                runCatching { RegexEngine.run(r.copy(trimStrings = trimText.split('\n').filter { it.isNotEmpty() }), test, ex) }
                    .getOrElse { "Error: ${it.message}" }
            }
            if (test.isNotEmpty()) {
                Text("Result", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                Text(result, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
            }

            if (existing != null) {
                TextButton(onClick = { confirmDelete = true }, modifier = Modifier.padding(top = 16.dp)) {
                    Text("Delete script", color = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.height(40.dp))
        }
    }
    if (confirmDelete) {
        ConfirmDialog("Delete \"${r.name}\"?", "The script will be removed.", onDismiss = { confirmDelete = false }) {
            vm.deleteRegex(r.id); vm.back()
        }
    }
}

/** Integer field that may be empty (null). */
@Composable
fun OptionalIntField(label: String, value: Int?, onChange: (Int?) -> Unit, modifier: Modifier = Modifier) {
    var text by remember { mutableStateOf(value?.toString() ?: "") }
    LaunchedEffect(value) { if (text.toIntOrNull() != value) text = value?.toString() ?: "" }
    OutlinedTextField(
        text,
        { t -> text = t.filter { it.isDigit() }.take(5); onChange(text.toIntOrNull()) },
        label = { Text(label) }, singleLine = true,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
        modifier = modifier,
    )
}
