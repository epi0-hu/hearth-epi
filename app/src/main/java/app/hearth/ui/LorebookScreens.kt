package app.hearth.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hearth.MainViewModel
import app.hearth.Screen
import app.hearth.core.LoreEntry
import app.hearth.core.Lorebook
import app.hearth.core.WiLogic
import app.hearth.core.WiPos

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LorebookListScreen(vm: MainViewModel) {
    val books by vm.lorebooks.collectAsStateWithLifecycle()
    val settings by vm.settings
    var creating by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Lorebook?>(null) }
    var exportId by remember { mutableStateOf<String?>(null) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.importLorebooks(uris)
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val id = exportId
        if (uri != null && id != null) vm.exportLorebook(id, uri)
        exportId = null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = { Text("Lorebooks") },
                actions = {
                    TextButton(onClick = {
                        importer.launch(arrayOf("application/json", "image/png", "application/octet-stream", "text/plain"))
                    }) { Text("Import") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { creating = true }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("New") })
        },
        snackbarHost = { SnackbarHost(rememberToasts(vm)) },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = pad.calculateBottomPadding() + 88.dp),
        ) {
            if (books.isEmpty()) item {
                Text(
                    "No lorebooks yet. Import a SillyTavern World Info JSON (or a card with an embedded lorebook), or create one.\n\n" +
                        "Global lorebooks apply to every chat. Others apply when linked from a character.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            }
            items(books, key = { it.id }) { b ->
                var menu by remember { mutableStateOf(false) }
                Box {
                    Row(
                        Modifier.fillMaxWidth()
                            .combinedClickable(onClick = { vm.navigate(Screen.EditLorebook(b.id)) }, onLongClick = { menu = true })
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(b.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${b.entries.size} entries", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Switch(checked = b.id in settings.globalLorebooks, onCheckedChange = { vm.toggleGlobal(b.id) })
                            Text("Global", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("Export JSON") }, onClick = {
                            menu = false; exportId = b.id; exporter.launch("${b.name}.json")
                        })
                        DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; confirm = b })
                    }
                }
            }
        }
    }
    if (creating) TextInputDialog("New lorebook", "", "Name", { creating = false }) { name ->
        val id = vm.createLorebook(name)
        vm.navigate(Screen.EditLorebook(id))
    }
    confirm?.let { b ->
        ConfirmDialog("Delete ${b.name}?", "All ${b.entries.size} entries will be removed.", onDismiss = { confirm = null }) {
            vm.deleteLorebook(b.id)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LorebookEditScreen(vm: MainViewModel, id: String) {
    val initial = remember(id) { vm.lorebooks.value.firstOrNull { it.id == id } }
    if (initial == null) {
        LaunchedEffect(id) { vm.back() }
        return
    }
    var book by remember(id) { mutableStateOf(initial) }
    var expanded by rememberSaveable { mutableStateOf<Int?>(null) }
    var filter by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf(false) }

    fun update(b: Lorebook) { book = b; vm.updateLorebook(b) }
    fun updateEntry(e: LoreEntry) = update(book.copy(entries = book.entries.map { if (it.uid == e.uid) e else it }))

    val shown = remember(book, filter) {
        val q = filter.trim()
        book.entries.filter {
            q.isEmpty() || it.comment.contains(q, true) || it.keys.any { k -> k.contains(q, true) } || it.content.contains(q, true)
        }.sortedBy { it.order }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = { Text(book.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.clickable { renaming = true }) },
                actions = { TextButton(onClick = { renaming = true }) { Text("Rename") } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    val e = LoreEntry(uid = book.nextUid(), comment = "New entry")
                    update(book.copy(entries = book.entries + e))
                    expanded = e.uid
                },
                icon = { Icon(Icons.Default.Add, null) }, text = { Text("Entry") },
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().imePadding(),
            contentPadding = PaddingValues(
                start = 12.dp, end = 12.dp, top = pad.calculateTopPadding(), bottom = pad.calculateBottomPadding() + 96.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "_filter") {
                OutlinedTextField(
                    filter, { filter = it }, label = { Text("Filter ${book.entries.size} entries") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            items(shown, key = { it.uid }) { e ->
                EntryCard(
                    e, expanded = expanded == e.uid,
                    onToggle = { expanded = if (expanded == e.uid) null else e.uid },
                    onChange = { updateEntry(it) },
                    onDelete = { update(book.copy(entries = book.entries.filter { it.uid != e.uid })) },
                )
            }
        }
    }
    if (renaming) TextInputDialog("Rename lorebook", book.name, "Name", { renaming = false }) { update(book.copy(name = it)) }
}

@Composable
private fun EntryCard(e: LoreEntry, expanded: Boolean, onToggle: () -> Unit, onChange: (LoreEntry) -> Unit, onDelete: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    e.comment.ifBlank { e.keys.firstOrNull() ?: "Untitled" },
                    style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (e.constant) "Always active" else e.keys.joinToString(", ").ifEmpty { "No keys" },
                    style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (e.constant) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = e.enabled, onCheckedChange = { onChange(e.copy(enabled = it)) })
        }
        if (expanded) {
            Column(Modifier.padding(horizontal = 14.dp).padding(bottom = 12.dp)) {
                OutlinedTextField(e.comment, { onChange(e.copy(comment = it)) }, label = { Text("Title / memo") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                ListField("Keys (comma separated, /regex/i allowed)", e.keys) { onChange(e.copy(keys = it)) }
                ListField("Secondary keys (optional)", e.secondaryKeys) { onChange(e.copy(secondaryKeys = it, selective = true)) }
                if (e.secondaryKeys.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    DropdownField("Secondary logic", WiLogic.labels.indices.toList(), e.selectiveLogic, { WiLogic.labels[it] }, {
                        onChange(e.copy(selectiveLogic = it))
                    })
                }
                OutlinedTextField(
                    e.content, { onChange(e.copy(content = it)) }, label = { Text("Content") },
                    minLines = 3, maxLines = 14, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                SwitchRow("Constant (always inserted)", e.constant, { onChange(e.copy(constant = it)) })
                DropdownField("Insertion position", WiPos.labels.indices.toList(), e.position.coerceIn(0, WiPos.labels.lastIndex),
                    { WiPos.labels[it] }, { onChange(e.copy(position = it)) })
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IntField("Order", e.order, { onChange(e.copy(order = it)) }, Modifier.weight(1f))
                    if (e.position == WiPos.AT_DEPTH) IntField("Depth", e.depth, { onChange(e.copy(depth = it)) }, Modifier.weight(1f))
                }
                SliderRow("Trigger chance", e.probability.toFloat(), 0f..100f, { "${it.toInt()}%" },
                    { onChange(e.copy(probability = it.toInt(), useProbability = true)) }, step = 1f)
                SwitchRow("Can't be triggered by other entries", e.excludeRecursion, { onChange(e.copy(excludeRecursion = it)) })
                SwitchRow("Don't trigger other entries", e.preventRecursion, { onChange(e.copy(preventRecursion = it)) })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Delete entry")
                    }
                }
            }
        }
    }
}

/** Comma-separated list editor that keeps the raw text while typing. */
@Composable
private fun ListField(label: String, values: List<String>, onChange: (List<String>) -> Unit) {
    var text by remember { mutableStateOf(values.joinToString(", ")) }
    OutlinedTextField(
        text,
        { t ->
            text = t
            onChange(t.split(',').map { it.trim() }.filter { it.isNotEmpty() })
        },
        label = { Text(label) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
}
