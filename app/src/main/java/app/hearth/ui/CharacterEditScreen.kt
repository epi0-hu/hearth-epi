package app.hearth.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hearth.MainViewModel
import app.hearth.core.Character
import app.hearth.core.DepthPrompt
import app.hearth.core.Role
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val GREETING_SEPARATOR = "\n---\n"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharacterEditScreen(vm: MainViewModel, charId: String?) {
    var loaded by remember { mutableStateOf(charId == null) }
    var c by remember { mutableStateOf(Character(id = "", name = "")) }
    var newAvatar by remember { mutableStateOf<ByteArray?>(null) }
    var greetingsText by remember { mutableStateOf("") }
    var tagsText by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val books by vm.lorebooks.collectAsStateWithLifecycle()

    LaunchedEffect(charId) {
        if (charId != null) {
            vm.loadCharacter(charId)?.let {
                c = it
                greetingsText = it.alternateGreetings.joinToString(GREETING_SEPARATOR)
                tagsText = it.tags.joinToString(", ")
            }
            loaded = true
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            newAvatar = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }
        }
    }

    fun save() {
        if (c.name.isBlank()) { vm.toast("Give the character a name"); return }
        saving = true
        scope.launch {
            val ch = c.copy(
                alternateGreetings = greetingsText.split(GREETING_SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() },
                tags = tagsText.split(',').map { it.trim() }.filter { it.isNotEmpty() },
            )
            vm.saveCharacter(ch, newAvatar)
            saving = false
            vm.back()
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                colors = hearthBarColors(),
                navigationIcon = { IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = { Text(if (charId == null) "New character" else "Edit character") },
                actions = {
                    if (saving) CircularProgressIndicator(Modifier.size(22.dp).padding(end = 4.dp))
                    else IconButton(onClick = { save() }, enabled = loaded) { Icon(Icons.Default.Check, "Save") }
                },
            )
        },
        snackbarHost = { SnackbarHost(rememberToasts(vm)) },
    ) { pad ->
        if (loaded) Column(
            Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp).navigationBarsPadding(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 12.dp)) {
                val preview = remember(newAvatar) {
                    newAvatar?.let { b ->
                        val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                        BitmapFactory.decodeByteArray(b, 0, b.size, opts)?.asImageBitmap()
                    }
                }
                Box(Modifier.clip(CircleShape).clickable { picker.launch("image/*") }) {
                    if (preview != null) {
                        Image(preview, null, contentScale = ContentScale.Crop, modifier = Modifier.size(84.dp))
                    } else {
                        Avatar(
                            c.id.ifEmpty { "new" }, c.name.ifBlank { "?" }, vm.storage.thumbFile(c.id.ifEmpty { "_" }),
                            vm.avatarVersion[c.id] ?: 0L, 84.dp,
                        )
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    TextButton(onClick = { picker.launch("image/*") }) { Text("Change avatar") }
                    Text(
                        "Tap the image to pick a new one",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Field("Name", c.name) { c = c.copy(name = it) }
            Field("Description", c.description, lines = 6) { c = c.copy(description = it) }
            Field("Personality", c.personality, lines = 3) { c = c.copy(personality = it) }
            Field("Scenario", c.scenario, lines = 3) { c = c.copy(scenario = it) }
            Field("First message", c.firstMes, lines = 5) { c = c.copy(firstMes = it) }
            Field(
                "Alternate greetings", greetingsText, lines = 4,
                hint = "Separate greetings with a line containing only ---",
            ) { greetingsText = it }
            Field("Example dialogue", c.mesExample, lines = 5, hint = "Start each example with <START>") {
                c = c.copy(mesExample = it)
            }

            SectionTitle("Prompt overrides")
            Field("System prompt", c.systemPrompt, lines = 3, hint = "Replaces the preset's main prompt. Use {{original}} to include it.") {
                c = c.copy(systemPrompt = it)
            }
            Field("Post-history instructions", c.postHistoryInstructions, lines = 3, hint = "Sent after the chat. {{original}} = preset's.") {
                c = c.copy(postHistoryInstructions = it)
            }
            Field("Character note (depth prompt)", c.depthPrompt?.prompt ?: "", lines = 2) {
                c = c.copy(depthPrompt = DepthPrompt(it, c.depthPrompt?.depth ?: 4, c.depthPrompt?.role ?: Role.SYSTEM))
            }
            IntField(
                "Note depth", c.depthPrompt?.depth ?: 4,
                { d -> c = c.copy(depthPrompt = (c.depthPrompt ?: DepthPrompt("", 4)).copy(depth = d)) },
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )

            SectionTitle("Lore")
            val noneLabel = "None"
            val options = listOf(noneLabel) + books.map { it.name }
            DropdownField(
                "Linked lorebook", options,
                selected = books.firstOrNull { it.id == c.linkedWorld || it.name == c.linkedWorld }?.name ?: noneLabel,
                optionLabel = { it },
                onSelect = { sel -> c = c.copy(linkedWorld = if (sel == noneLabel) "" else sel) },
            )
            c.book?.let {
                Text(
                    "Embedded lorebook: ${it.entries.size} entries (used automatically)",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            SectionTitle("Metadata")
            Field("Tags", tagsText, hint = "Comma separated") { tagsText = it }
            Field("Creator", c.creator) { c = c.copy(creator = it) }
            Field("Creator notes", c.creatorNotes, lines = 3) { c = c.copy(creatorNotes = it) }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun Field(label: String, value: String, lines: Int = 1, hint: String? = null, onChange: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        OutlinedTextField(
            value = value, onValueChange = onChange, label = { Text(label) },
            singleLine = lines == 1, minLines = lines.coerceAtMost(3), maxLines = if (lines == 1) 1 else 14,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        )
        if (hint != null) {
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
