package app.hearth.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hearth.MainViewModel
import app.hearth.core.Group
import app.hearth.core.GroupStrategy

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupEditScreen(vm: MainViewModel, groupId: String?) {
    val chars by vm.characters.collectAsStateWithLifecycle()
    val existing = remember(groupId) { vm.groups.value.firstOrNull { it.id == groupId } }
    var g by remember(groupId) { mutableStateOf(existing ?: Group(id = "", name = "", members = emptyList())) }
    var filter by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }

    fun save() {
        if (g.members.isEmpty()) { vm.toast("Add at least one character"); return }
        val named = if (g.name.isBlank()) g.copy(name = g.members.mapNotNull { id -> chars.firstOrNull { it.id == id }?.name }.take(3).joinToString(", ")) else g
        val id = vm.saveGroup(named)
        vm.back()
        if (existing == null) vm.openGroup(id)
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                colors = hearthBarColors(),
                navigationIcon = { IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = { Text(if (existing == null) "New group" else "Edit group") },
                actions = { IconButton(onClick = { save() }) { Icon(Icons.Default.Check, "Save") } },
            )
        },
        snackbarHost = { SnackbarHost(rememberToasts(vm)) },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().imePadding(),
            contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = pad.calculateBottomPadding() + 32.dp),
        ) {
            item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    OutlinedTextField(
                        g.name, { g = g.copy(name = it) }, label = { Text("Group name") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    SectionTitle("Who replies")
                    DropdownField("Reply strategy", GroupStrategy.entries.toList(), g.strategy, { it.label }, { g = g.copy(strategy = it) })
                    Text(g.strategy.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp))
                    SwitchRow("Allow self-responses", g.allowSelfResponses, { g = g.copy(allowSelfResponses = it) },
                        hint = "Let the last speaker be picked again (natural mode)")
                    SwitchRow("Join character cards", g.joinCards, { g = g.copy(joinCards = it) },
                        hint = "Put every member's description in the prompt, not just the speaker's")
                    SectionTitle("Members (${g.members.size})")
                    if (g.members.isEmpty()) {
                        Text("Pick characters below.", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            items(g.members, key = { "m:$it" }) { id ->
                val c = chars.firstOrNull { it.id == id }
                val muted = id in g.disabled
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(id, c?.name ?: id, vm.storage.thumbFile(id), vm.avatarVersion[id] ?: 0L, 40.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c?.name ?: "(missing)", style = MaterialTheme.typography.bodyLarge)
                        Text(if (muted) "Muted" else "Active", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = !muted, onCheckedChange = { on ->
                        g = g.copy(disabled = if (on) g.disabled - id else g.disabled + id)
                    })
                    IconButton(onClick = { g = g.copy(members = g.members - id, disabled = g.disabled - id) }) {
                        Icon(Icons.Default.Close, "Remove")
                    }
                }
            }
            item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    SectionTitle("Add characters")
                    OutlinedTextField(filter, { filter = it }, label = { Text("Search") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                }
            }
            val candidates = chars.filter { it.id !in g.members && (filter.isBlank() || it.name.contains(filter.trim(), true)) }
            items(candidates, key = { "c:${it.id}" }) { c ->
                Row(
                    Modifier.fillMaxWidth().clickable { g = g.copy(members = g.members + c.id) }
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = false, onCheckedChange = null)
                    Spacer(Modifier.width(8.dp))
                    Avatar(c.id, c.name, vm.storage.thumbFile(c.id), vm.avatarVersion[c.id] ?: 0L, 36.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(c.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (existing != null) item {
                TextButton(onClick = { confirmDelete = true }, modifier = Modifier.padding(16.dp)) {
                    Text("Delete group", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
    if (confirmDelete && existing != null) {
        ConfirmDialog("Delete ${existing.name}?", "The group and its chats will be deleted. The characters stay.",
            onDismiss = { confirmDelete = false }) {
            vm.deleteGroup(existing.id)
            vm.backStack.removeAll { it is app.hearth.Screen.Chat && it.key == existing.chatKey }
            vm.back()
        }
    }
}
