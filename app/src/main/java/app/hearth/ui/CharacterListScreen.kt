package app.hearth.ui

import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hearth.MainViewModel
import app.hearth.Screen
import app.hearth.core.CharacterSummary
import app.hearth.core.Group
import java.util.Calendar

private enum class Sort(val label: String) { RECENT("Recent"), NAME("A–Z"), FAVORITES("Favorites") }

/** Something shown in the "Continue" row: a character or a group. */
private data class Recent(val key: String, val name: String, val lastActive: Long, val charId: String?, val group: Group?)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CharacterListScreen(vm: MainViewModel) {
    val chars by vm.characters.collectAsStateWithLifecycle()
    val groups by vm.groups.collectAsStateWithLifecycle()
    val ready by vm.ready.collectAsStateWithLifecycle()
    val persona = vm.settings.value.persona.name
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(Sort.RECENT) }
    var fabOpen by remember { mutableStateOf(false) }
    var sheetChar by remember { mutableStateOf<CharacterSummary?>(null) }
    var sheetGroup by remember { mutableStateOf<Group?>(null) }
    var confirmDelete by remember { mutableStateOf<CharacterSummary?>(null) }
    var confirmGroupDelete by remember { mutableStateOf<Group?>(null) }
    var exportId by remember { mutableStateOf<String?>(null) }
    val haptics = LocalHapticFeedback.current

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.importCharacters(uris)
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        val id = exportId
        if (uri != null && id != null) vm.exportCharacter(id, uri)
        exportId = null
    }
    val launchImport = { importer.launch(arrayOf("image/png", "application/json", "application/octet-stream", "text/plain")) }

    val q = query.trim()
    val shown = remember(chars, q, sort) {
        val list = chars.filter { q.isEmpty() || it.name.contains(q, true) || it.tags.any { t -> t.contains(q, true) } }
        when (sort) {
            Sort.RECENT -> list.sortedWith(compareByDescending<CharacterSummary> { it.favorite }.thenByDescending { it.lastActive })
            Sort.NAME -> list.sortedBy { it.name.lowercase() }
            Sort.FAVORITES -> list.filter { it.favorite }.sortedBy { it.name.lowercase() }
        }
    }
    val shownGroups = remember(groups, q) {
        groups.filter { q.isEmpty() || it.name.contains(q, true) }
            .sortedWith(compareByDescending<Group> { it.favorite }.thenByDescending { it.lastActive })
    }
    val recent = remember(chars, groups) {
        (chars.map { Recent(it.id, it.name, it.lastActive, it.id, null) } +
            groups.map { Recent(it.chatKey, it.name, it.lastActive, null, it) })
            .filter { it.lastActive > 0 }.sortedByDescending { it.lastActive }.take(8)
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(containerColor = Color.Transparent, snackbarHost = { SnackbarHost(rememberToasts(vm)) }) { pad ->
            if (ready && chars.isEmpty() && groups.isEmpty()) {
                EmptyHearth(Modifier.padding(pad), onImport = launchImport, onCreate = { vm.navigate(Screen.EditCharacter(null)) })
            } else LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                state = rememberLazyGridState(),
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp, top = pad.calculateTopPadding() + 8.dp,
                    bottom = pad.calculateBottomPadding() + 120.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "hero", span = { GridItemSpan(maxLineSpan) }) {
                    Hero(
                        persona = persona, searching = searching, query = query,
                        onSearchToggle = { searching = !searching; if (!searching) query = "" },
                        onQuery = { query = it },
                        onLore = { vm.navigate(Screen.Lorebooks) },
                        onSettings = { vm.navigate(Screen.Settings) },
                    )
                }
                if (recent.isNotEmpty() && q.isEmpty()) {
                    item(key = "continue", span = { GridItemSpan(maxLineSpan) }) {
                        Column {
                            Eyebrow("Continue")
                            Spacer(Modifier.height(8.dp))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                items(recent, key = { it.key }) { r ->
                                    RecentCard(vm, r, onOpen = {
                                        val g = r.group
                                        if (g != null) vm.openGroup(g.id) else r.charId?.let { vm.openChat(it) }
                                    })
                                }
                            }
                        }
                    }
                }
                if (shownGroups.isNotEmpty()) {
                    item(key = "groups", span = { GridItemSpan(maxLineSpan) }) {
                        Column {
                            Eyebrow("Groups")
                            Spacer(Modifier.height(8.dp))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                items(shownGroups, key = { it.id }) { g ->
                                    GroupChip(vm, g, chars, onOpen = { vm.openGroup(g.id) }, onLong = {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress); sheetGroup = g
                                    })
                                }
                            }
                        }
                    }
                }
                item(key = "charsHeader", span = { GridItemSpan(maxLineSpan) }) {
                    Column {
                        Eyebrow("Characters · ${chars.size}")
                        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Sort.entries.forEach { s ->
                                FilterChip(
                                    selected = sort == s, onClick = { sort = s }, label = { Text(s.label) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.6f),
                                    ),
                                )
                            }
                        }
                    }
                }
                items(shown, key = { it.id }, contentType = { "char" }) { c ->
                    CharacterCard(
                        vm, c,
                        modifier = Modifier.animateItem(),
                        onOpen = { vm.openChat(c.id) },
                        onLong = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); sheetChar = c },
                    )
                }
                if (shown.isEmpty() && chars.isNotEmpty()) {
                    item(key = "none", span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            if (sort == Sort.FAVORITES && q.isEmpty()) "No favorites yet — long-press a character to add one."
                            else "Nothing matches \"$q\".",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 24.dp),
                        )
                    }
                }
            }
        }

        FabMenu(
            open = fabOpen,
            onToggle = { fabOpen = !fabOpen },
            items = listOf(
                FabItem("Import cards", HearthIcons.Upload) { launchImport() },
                FabItem("New character", Icons.Default.Person) { vm.navigate(Screen.EditCharacter(null)) },
                FabItem("New group chat", HearthIcons.Group) { vm.navigate(Screen.EditGroup(null)) },
                FabItem("Regex scripts", HearthIcons.Regex) { vm.navigate(Screen.Regex) },
            ),
        )
    }

    sheetChar?.let { c ->
        ActionSheet(
            title = c.name, subtitle = c.tagline.ifBlank { null },
            leading = { Avatar(c.id, c.name, vm.storage.thumbFile(c.id), vm.avatarVersion[c.id] ?: 0L, 48.dp) },
            onDismiss = { sheetChar = null },
            actions = listOf(
                SheetAction("Open chat", HearthIcons.Flame) { vm.openChat(c.id) },
                SheetAction("Start a new chat", Icons.Default.Add) { vm.openChat(c.id, forceNew = true) },
                SheetAction("Edit character", Icons.Default.Edit) { vm.navigate(Screen.EditCharacter(c.id)) },
                SheetAction(if (c.favorite) "Remove from favorites" else "Add to favorites", Icons.Default.Star) { vm.toggleFavorite(c.id) },
                SheetAction("Export card (PNG)", Icons.Default.Share) { exportId = c.id; exporter.launch("${c.name}.png") },
                SheetAction("Delete", Icons.Default.Delete, destructive = true) { confirmDelete = c },
            ),
        )
    }
    sheetGroup?.let { g ->
        ActionSheet(
            title = g.name, subtitle = "${g.members.size} members · ${g.strategy.label}",
            onDismiss = { sheetGroup = null },
            actions = listOf(
                SheetAction("Open chat", HearthIcons.Flame) { vm.openGroup(g.id) },
                SheetAction("Start a new chat", Icons.Default.Add) { vm.openGroup(g.id, forceNew = true) },
                SheetAction("Edit group", Icons.Default.Edit) { vm.navigate(Screen.EditGroup(g.id)) },
                SheetAction(if (g.favorite) "Remove from favorites" else "Add to favorites", Icons.Default.Star) { vm.toggleGroupFavorite(g.id) },
                SheetAction("Delete group", Icons.Default.Delete, destructive = true) { confirmGroupDelete = g },
            ),
        )
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

/** The app's flame, gently flickering (still when motion is reduced). */
@Composable
fun FlickeringFlame(size: Dp) {
    val reduced = LocalReducedMotion.current
    val ember = LocalChatPalette.current.ember
    val inf = rememberInfiniteTransition(label = "flame")
    val s by inf.animateFloat(0.92f, 1.06f, infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "f")
    Icon(
        HearthIcons.Flame, null, tint = ember,
        modifier = Modifier.size(size).graphicsLayer {
            val k = if (reduced) 1f else s
            scaleX = 0.98f + (k - 1f) * 0.4f
            scaleY = k
            transformOrigin = TransformOrigin(0.5f, 1f)
        },
    )
}

private fun greeting(name: String): Pair<String, String> {
    val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    val first = when (h) {
        in 5..11 -> "Good morning"
        in 12..17 -> "Good afternoon"
        in 18..22 -> "Good evening"
        else -> "Up late"
    }
    val second = when (h) {
        in 5..11 -> "Fresh kindling, a new story."
        in 12..17 -> "The fire's steady. Who's joining you?"
        in 18..22 -> "The fire's warm. Pull up a chair."
        else -> "Just the embers and you."
    }
    return "$first, $name." to second
}

@Composable
private fun Hero(
    persona: String,
    searching: Boolean,
    query: String,
    onSearchToggle: () -> Unit,
    onQuery: (String) -> Unit,
    onLore: () -> Unit,
    onSettings: () -> Unit,
) {
    val (hello, line) = remember(persona) { greeting(persona) }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FlickeringFlame(30.dp)
            Spacer(Modifier.width(8.dp))
            Text("Hearth", style = MaterialTheme.typography.displaySmall, modifier = Modifier.weight(1f))
            IconButton(onClick = onSearchToggle) { Icon(if (searching) Icons.Default.Close else Icons.Default.Search, "Search") }
            IconButton(onClick = onLore) { Icon(HearthIcons.Book, "Lorebooks") }
            IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Settings") }
        }
        Text(hello, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
        Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        AnimatedVisibility(searching, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            OutlinedTextField(
                query, onQuery, singleLine = true, placeholder = { Text("Search characters, tags, groups") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CharacterCard(vm: MainViewModel, c: CharacterSummary, modifier: Modifier, onOpen: () -> Unit, onLong: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    Box(
        modifier.aspectRatio(0.74f).pressScale(src)
            .clip(RoundedCornerShape(22.dp))
            .combinedClickable(interactionSource = src, indication = LocalIndication.current, onClick = onOpen, onLongClick = onLong),
    ) {
        Portrait(c.id, c.name, vm.storage.thumbFile(c.id), vm.avatarVersion[c.id] ?: 0L, Modifier.fillMaxSize())
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.85f)),
            ),
        )
        Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
            Text(
                c.name, style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Serif),
                color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (c.tagline.isNotEmpty()) {
                Text(c.tagline, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.72f),
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (c.favorite) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(8.dp).size(26.dp).clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Default.Star, null, Modifier.size(16.dp), tint = LocalChatPalette.current.ember) }
        }
    }
}

@Composable
private fun RecentCard(vm: MainViewModel, r: Recent, onOpen: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val ember = LocalChatPalette.current.ember
    Column(
        Modifier.width(96.dp).pressScale(src)
            .clip(RoundedCornerShape(18.dp))
            .clickable(interactionSource = src, indication = LocalIndication.current, onClick = onOpen)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
            val g = r.group
            val charId = r.charId
            if (g != null) {
                val corners = listOf(Alignment.TopStart, Alignment.BottomEnd, Alignment.BottomStart)
                g.members.take(3).forEachIndexed { i, id ->
                    Avatar(id, r.name, vm.storage.thumbFile(id), vm.avatarVersion[id] ?: 0L, 44.dp, Modifier.align(corners[i]))
                }
            } else if (charId != null) {
                Avatar(charId, r.name, vm.storage.thumbFile(charId), vm.avatarVersion[charId] ?: 0L, 64.dp,
                    Modifier.glowRing(true, ember.copy(alpha = 0.55f), 1.5.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(r.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        Text(
            DateUtils.getRelativeTimeSpanString(r.lastActive, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
                DateUtils.FORMAT_ABBREV_RELATIVE).toString(),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupChip(vm: MainViewModel, g: Group, chars: List<CharacterSummary>, onOpen: () -> Unit, onLong: () -> Unit) {
    val members = remember(g.members, chars) { g.members.mapNotNull { id -> chars.firstOrNull { it.id == id } } }
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.75f),
        modifier = Modifier.clip(RoundedCornerShape(20.dp)).combinedClickable(onClick = onOpen, onLongClick = onLong),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            GroupAvatarIds(vm, members.map { it.id to it.name }, 30.dp)
            Spacer(Modifier.width(10.dp))
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(g.name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    if (g.favorite) {
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Default.Star, null, Modifier.size(14.dp), tint = LocalChatPalette.current.ember)
                    }
                }
                Text("${members.size} members", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Overlapping avatars from (id, name) pairs. */
@Composable
fun GroupAvatarIds(vm: MainViewModel, members: List<Pair<String, String>>, size: Dp) {
    val shown = members.take(3)
    val step = size * 0.5f
    Box(Modifier.width(size + step * (shown.size - 1).coerceAtLeast(0)).height(size)) {
        shown.forEachIndexed { i, (id, name) ->
            Avatar(id, name, vm.storage.thumbFile(id), vm.avatarVersion[id] ?: 0L, size, Modifier.padding(start = step * i))
        }
    }
}

private class FabItem(val label: String, val icon: ImageVector, val onClick: () -> Unit)

/** Speed-dial: the + turns into ×, actions rise one after another over a soft scrim. */
@Composable
private fun FabMenu(open: Boolean, onToggle: () -> Unit, items: List<FabItem>) {
    val rotation by animateFloatAsState(if (open) 135f else 0f, tween(280, easing = FastOutSlowInEasing), label = "fab")
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(open, enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onToggle),
            )
        }
        Column(
            Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 20.dp, bottom = 20.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items.forEachIndexed { i, item ->
                val delay = 35 * (items.size - 1 - i)
                AnimatedVisibility(
                    open,
                    enter = fadeIn(tween(200, delay)) + slideInVertically(tween(260, delay)) { it / 2 } + scaleIn(tween(260, delay), 0.85f),
                    exit = fadeOut(tween(120)) + slideOutVertically(tween(160)) { it / 3 },
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 4.dp) {
                            Text(item.label, style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        SmallFloatingActionButton(
                            onClick = { onToggle(); item.onClick() },
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        ) { Icon(item.icon, item.label, Modifier.size(20.dp)) }
                    }
                }
            }
            FloatingActionButton(onClick = onToggle, containerColor = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(20.dp)) {
                Icon(Icons.Default.Add, if (open) "Close" else "New", Modifier.graphicsLayer { rotationZ = rotation })
            }
        }
    }
}

@Composable
private fun EmptyHearth(modifier: Modifier, onImport: () -> Unit, onCreate: () -> Unit) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(96.dp).glowRing(true, LocalChatPalette.current.ember.copy(alpha = 0.5f), 2.dp), contentAlignment = Alignment.Center) {
            FlickeringFlame(56.dp)
        }
        Spacer(Modifier.height(24.dp))
        Text("The hearth is quiet", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Bring someone to the fire: import SillyTavern / Tavern character cards (PNG or JSON), " +
                "or create a character from scratch.",
            style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onImport) { Text("Import cards") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onCreate) { Text("Create character") }
    }
}
