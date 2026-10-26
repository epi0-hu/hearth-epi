package app.hearth.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.hearth.MainViewModel
import app.hearth.core.Preset
import app.hearth.core.PromptModule
import app.hearth.core.Role

/**
 * The preset's prompt order, like SillyTavern's prompt manager: switch modules on/off,
 * reorder them, edit their text, role and placement, or add your own.
 */
@Composable
fun PromptModulesEditor(vm: MainViewModel, p: Preset) {
    var editing by remember { mutableStateOf<String?>(null) }
    var filter by remember { mutableStateOf("") }
    var onlyEnabled by remember { mutableStateOf(false) }

    fun update(f: (MutableList<PromptModule>) -> Unit) =
        vm.updatePreset { pr -> pr.copy(modules = pr.modules.toMutableList().also(f)) }

    fun move(id: String, delta: Int) = update { list ->
        val i = list.indexOfFirst { it.identifier == id }
        val j = i + delta
        if (i >= 0 && j in list.indices) list.add(j, list.removeAt(i))
    }

    SectionTitle("Prompt order · ${p.name}")
    val on = p.modules.count { it.enabled }
    Text(
        "$on of ${p.modules.size} on. Tap a prompt to edit it; markers are filled in automatically.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (p.modules.size > 12) {
        OutlinedTextField(
            filter, { filter = it }, label = { Text("Find prompt") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        SwitchRow("Show only enabled", onlyEnabled, { onlyEnabled = it })
    }
    Spacer(Modifier.height(4.dp))

    val q = filter.trim()
    val shown = p.modules.filter {
        (!onlyEnabled || it.enabled) && (q.isEmpty() || it.name.contains(q, true) || it.content.contains(q, true))
    }
    shown.forEach { m ->
        val index = p.modules.indexOf(m)
        Row(
            Modifier.fillMaxWidth().clickable { editing = m.identifier }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Switch(checked = m.enabled, onCheckedChange = { v ->
                update { list -> list.indexOfFirst { it.identifier == m.identifier }.takeIf { it >= 0 }?.let { list[it] = m.copy(enabled = v) } }
            })
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    m.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (m.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                )
                Text(
                    describe(m), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (m.marker) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { move(m.identifier, -1) }, enabled = index > 0 && q.isEmpty() && !onlyEnabled, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.KeyboardArrowUp, "Move up")
            }
            IconButton(onClick = { move(m.identifier, +1) }, enabled = index < p.modules.lastIndex && q.isEmpty() && !onlyEnabled,
                modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.KeyboardArrowDown, "Move down")
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    }
    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = {
            val id = "custom_" + System.currentTimeMillis().toString(36)
            update { list ->
                val at = list.indexOfFirst { it.identifier == PromptModule.HISTORY }.let { if (it < 0) list.size else it }
                list.add(at, PromptModule(id, "New prompt"))
            }
            editing = id
        }) {
            Icon(Icons.Default.Add, null)
            Spacer(Modifier.width(6.dp))
            Text("Add prompt")
        }
    }

    editing?.let { id ->
        val m = p.modules.firstOrNull { it.identifier == id }
        if (m == null) editing = null
        else ModuleDialog(
            m,
            onDismiss = { editing = null },
            onSave = { updated -> update { list -> list.indexOfFirst { it.identifier == id }.takeIf { it >= 0 }?.let { list[it] = updated } } },
            onDelete = { update { list -> list.removeAll { it.identifier == id } }; editing = null },
        )
    }
}

private fun describe(m: PromptModule): String = when {
    m.marker -> "Marker · " + (PromptModule.MARKERS[m.identifier] ?: "filled automatically")
    m.inChat -> "${m.role.api} · in chat @ depth ${m.depth} · ${m.content.length} chars"
    m.content.isBlank() -> "${m.role.api} · empty"
    else -> "${m.role.api} · " + m.content.replace('\n', ' ').take(80)
}

@Composable
private fun ModuleDialog(m: PromptModule, onDismiss: () -> Unit, onSave: (PromptModule) -> Unit, onDelete: () -> Unit) {
    var name by remember { mutableStateOf(m.name) }
    var content by remember { mutableStateOf(m.content) }
    var role by remember { mutableStateOf(m.role) }
    var inChat by remember { mutableStateOf(m.inChat) }
    var depth by remember { mutableStateOf(m.depth) }
    var order by remember { mutableStateOf(m.order) }
    var confirmDelete by remember { mutableStateOf(false) }
    val builtIn = m.identifier in setOf(PromptModule.MAIN, PromptModule.JAILBREAK, PromptModule.NSFW, PromptModule.ENHANCE)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (m.marker) m.name else "Edit prompt") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (m.marker) {
                    Text(
                        "This marker is filled with: ${PromptModule.MARKERS[m.identifier] ?: m.identifier}. " +
                            "Move it in the list or switch it off to change where (or whether) it appears.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    DropdownField("Role", Role.entries.toList(), role, { it.api.replaceFirstChar(Char::uppercase) }, { role = it })
                    Spacer(Modifier.height(8.dp))
                    DropdownField(
                        "Position", listOf(false, true), inChat,
                        { if (it) "In chat (at depth)" else "Relative (in list order)" }, { inChat = it },
                    )
                    if (inChat) {
                        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            IntField("Depth", depth, { depth = it }, Modifier.weight(1f))
                            IntField("Order", order, { order = it }, Modifier.weight(1f))
                        }
                    }
                    OutlinedTextField(
                        content, { content = it }, label = { Text("Prompt") }, minLines = 5, maxLines = 16,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp).padding(top = 8.dp),
                    )
                    if (m.identifier == PromptModule.MAIN || m.identifier == PromptModule.JAILBREAK) {
                        Text(
                            "A character's own system prompt / post-history instructions can replace this (see \"Prefer character prompts\").",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (!builtIn) {
                        TextButton(onClick = { confirmDelete = true }) { Text("Delete prompt", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (!m.marker) onSave(m.copy(name = name.ifBlank { m.name }, content = content, role = role,
                    inChat = inChat, depth = depth, order = order))
                onDismiss()
            }) { Text(if (m.marker) "Close" else "Save") }
        },
        dismissButton = { if (!m.marker) TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    if (confirmDelete) {
        ConfirmDialog("Delete \"${m.name}\"?", "This prompt will be removed from the preset.", onDismiss = { confirmDelete = false }) {
            onDelete()
        }
    }
}
