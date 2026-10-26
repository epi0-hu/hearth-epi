package app.hearth.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hearth.MainViewModel
import app.hearth.Screen
import app.hearth.core.CharacterSummary

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CharacterListScreen(vm: MainViewModel) {
    val chars by vm.characters.collectAsStateWithLifecycle()
    val groups by vm.groups.collectAsStateWithLifecycle()
    var groupMenu by remember { mutableStateOf<String?>(null) }
    var confirmGroupDelete by remember { mutableStateOf<app.hearth.core.Group?>(null) }
    val ready by vm.ready.collectAsStateWithLifecycle()
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<CharacterSummary?>(null) }
    var exportId by remember { mutableStateOf<String?>(null) }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.importCharacters(uris)
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        val id = exportId
        if (uri != null && id != null) vm.exportCharacter(id, uri)
        exportId = null
    }
    val launchImport = { importer.launch(arrayOf("image/png", "application/json", "application/octet-stream", "text/plain")) }

    val shown = remember(chars, query) {
        val q = query.trim()
        chars.filter { q.isEmpty() || it.name.contains(q, true) || it.tags.any { t -> t.contains(q, true) } }
            .sortedWith(compareByDescending<CharacterSummary> { it.favorite }.thenByDescending { it.lastActive })
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searching) {
                        TextField(
                            value = query, onValueChange = { query = it }, singleLine = true,
                            placeholder = { Text("Search characters or tags") },
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else Text("Hearth")
                },
                actions = {
                    IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
                        Icon(if (searching) Icons.Default.Close else Icons.Default.Search, "Search")
                    }
                    IconButton(onClick = { vm.navigate(Screen.Lorebooks) }) {
                        Icon(Icons.AutoMirrored.Filled.List, "Lorebooks")
                    }
                    IconButton(onClick = { vm.navigate(Screen.Settings) }) { Icon(Icons.Default.Settings, "Settings") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = launchImport,
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("Import") },
            )
        },
        snackbarHost = { SnackbarHost(rememberToasts(vm)) },
    ) { pad ->
        if (ready && chars.isEmpty()) {
            EmptyState(Modifier.padding(pad), onImport = launchImport, onCreate = { vm.navigate(Screen.EditCharacter(null)) })
        } else LazyColumn(
            contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = pad.calculateBottomPadding() + 88.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "_new", contentType = "header") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Text(
                        "${chars.size} character${if (chars.size == 1) "" else "s"}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f).align(Alignment.CenterVertically),
                    )
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text("Create character") }, onClick = {
                                menu = false; vm.navigate(Screen.EditCharacter(null))
                            })
                            DropdownMenuItem(text = { Text("Create group chat") }, onClick = {
                                menu = false; vm.navigate(Screen.EditGroup(null))
                            })
                            DropdownMenuItem(text = { Text("Regex scripts") }, onClick = {
                                menu = false; vm.navigate(Screen.Regex)
                            })
                            DropdownMenuItem(text = { Text("Backup & import") }, onClick = {
                                menu = false; vm.navigate(Screen.Settings)
                                vm.toast("Backup and SillyTavern import are in the Data tab")
                            })
                        }
                    }
                }
            }
            val shownGroups = groups.filter { g -> query.isBlank() || g.name.contains(query.trim(), true) }
                .sortedWith(compareByDescending<app.hearth.core.Group> { it.favorite }.thenByDescending { it.lastActive })
            items(shownGroups, key = { "g:" + it.id }, contentType = { "group" }) { g ->
                val members = remember(g.members, chars) {
                    g.members.mapNotNull { id -> chars.firstOrNull { it.id == id } }
                }
                Box {
                    Row(
                        Modifier.fillMaxWidth()
                            .combinedClickable(onClick = { vm.openGroup(g.id) }, onLongClick = { groupMenu = g.id })
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.width(52.dp)) {
                            members.take(2).forEachIndexed { i, m ->
                                Avatar(m.id, m.name, vm.storage.thumbFile(m.id), vm.avatarVersion[m.id] ?: 0L, 36.dp,
                                    Modifier.offset(x = (i * 16).dp, y = (i * 14).dp))
                            }
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(g.name, style = MaterialTheme.typography.titleMedium, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                if (g.favorite) {
                                    Spacer(Modifier.width(6.dp))
                                    Icon(Icons.Default.Star, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                            Text("Group · " + members.joinToString(", ") { it.name }, style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    DropdownMenu(groupMenu == g.id, { groupMenu = null }) {
                        DropdownMenuItem(text = { Text("Edit group") }, onClick = { groupMenu = null; vm.navigate(Screen.EditGroup(g.id)) })
                        DropdownMenuItem(text = { Text(if (g.favorite) "Unfavorite" else "Favorite") }, onClick = {
                            groupMenu = null; vm.toggleGroupFavorite(g.id)
                        })
                        DropdownMenuItem(text = { Text("Delete") }, onClick = { groupMenu = null; confirmGroupDelete = g })
                    }
                }
            }
            items(shown, key = { it.id }, contentType = { "char" }) { c ->
                CharacterRow(
                    vm, c,
                    onOpen = { vm.openChat(c.id) },
                    onEdit = { vm.navigate(Screen.EditCharacter(c.id)) },
                    onFavorite = { vm.toggleFavorite(c.id) },
                    onExport = { exportId = c.id; exporter.launch("${c.name}.png") },
                    onDelete = { confirmDelete = c },
                )
            }
        }
    }

    confirmGroupDelete?.let { g ->
        ConfirmDialog(
            "Delete ${g.name}?", "The group and its chats will be deleted. The characters stay.",
            onDismiss = { confirmGroupDelete = null }, onConfirm = { vm.deleteGroup(g.id) },
        )
    }
    confirmDelete?.let { c ->
        ConfirmDialog(
            "Delete ${c.name}?", "This removes the character and all of their chats.",
            onDismiss = { confirmDelete = null }, onConfirm = { vm.deleteCharacter(c.id) },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CharacterRow(
    vm: MainViewModel,
    c: CharacterSummary,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onFavorite: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().combinedClickable(onClick = onOpen, onLongClick = { menu = true })
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(c.id, c.name, vm.storage.thumbFile(c.id), vm.avatarVersion[c.id] ?: 0L, 52.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        c.name, style = MaterialTheme.typography.titleMedium, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                    )
                    if (c.favorite) {
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Default.Star, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                }
                if (c.tagline.isNotEmpty()) {
                    Text(
                        c.tagline, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(text = { Text("Edit") }, onClick = { menu = false; onEdit() })
            DropdownMenuItem(text = { Text(if (c.favorite) "Unfavorite" else "Favorite") }, onClick = { menu = false; onFavorite() })
            DropdownMenuItem(text = { Text("Export card (PNG)") }, onClick = { menu = false; onExport() })
            DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier, onImport: () -> Unit, onCreate: () -> Unit) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("No characters yet", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "Import SillyTavern / Tavern character cards (PNG or JSON), or create one from scratch.",
            style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onImport) { Text("Import cards") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onCreate) { Text("Create character") }
    }
}
