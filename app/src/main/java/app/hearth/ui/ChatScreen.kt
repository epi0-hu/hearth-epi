package app.hearth.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.hearth.ChatSession
import app.hearth.MainViewModel
import app.hearth.Screen
import app.hearth.StreamState
import app.hearth.core.Character
import app.hearth.core.ChatInfo
import app.hearth.core.ChatMessage
import app.hearth.core.Macros
import app.hearth.core.RegexEngine
import app.hearth.core.RegexPlacement
import app.hearth.core.RegexScript
import app.hearth.core.RegexTarget
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: MainViewModel) {
    val s = vm.chat
    if (s == null) {
        Box(Modifier.fillMaxSize())
        return
    }
    val settings by vm.settings
    var menu by remember { mutableStateOf(false) }
    var showChats by remember { mutableStateOf(false) }
    val c = s.character
    val group = s.group
    val openDetails = {
        if (group != null) vm.navigate(Screen.EditGroup(group.id)) else vm.navigate(Screen.EditCharacter(c.id))
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable(onClick = openDetails)) {
                        if (group != null) GroupAvatar(vm, s.members, 34.dp)
                        else Avatar(c.id, c.name, vm.storage.thumbFile(c.id), vm.avatarVersion[c.id] ?: 0L, 34.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Menu") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text("Chats") }, onClick = { menu = false; showChats = true })
                            DropdownMenuItem(text = { Text("New chat") }, onClick = {
                                menu = false; vm.openKey(s.key, forceNew = true)
                            })
                            DropdownMenuItem(
                                text = { Text(if (group != null) "Edit group" else "Edit character") },
                                onClick = { menu = false; openDetails() },
                            )
                            DropdownMenuItem(text = { Text("Regex scripts") }, onClick = { menu = false; vm.navigate(Screen.Regex) })
                            DropdownMenuItem(text = { Text("Settings") }, onClick = { menu = false; vm.navigate(Screen.Settings) })
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(rememberToasts(vm)) },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(top = pad.calculateTopPadding())
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)),
        ) {
            MessageList(vm, s, settings.showAvatars, Modifier.weight(1f))
            if (group != null) MemberStrip(vm, s)
            InputBar(s)
        }
    }

    if (showChats) ChatListSheet(vm, s, onDismiss = { showChats = false })
}

/** Up to three overlapping member avatars. */
@Composable
fun GroupAvatar(vm: MainViewModel, members: List<Character>, size: androidx.compose.ui.unit.Dp) {
    val shown = members.take(3)
    val step = size * 0.45f
    Box(Modifier.width(size + step * (shown.size - 1).coerceAtLeast(0)).heightIn(min = size)) {
        shown.forEachIndexed { i, m ->
            Avatar(
                m.id, m.name, vm.storage.thumbFile(m.id), vm.avatarVersion[m.id] ?: 0L, size,
                Modifier.offset(x = step * i).border(1.5.dp, MaterialTheme.colorScheme.surface, CircleShape),
            )
        }
    }
}

/** Group chats: tap a member to make them reply. Muted members are dimmed. */
@Composable
private fun MemberStrip(vm: MainViewModel, s: ChatSession) {
    val g = s.group ?: return
    LazyRow(
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        items(s.members, key = { it.id }) { m ->
            val muted = m.id !in g.active
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable(enabled = !s.generating) { s.speak(m) }.padding(2.dp),
            ) {
                Box(Modifier.then(if (muted) Modifier.background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)) else Modifier)) {
                    Avatar(m.id, m.name, vm.storage.thumbFile(m.id), vm.avatarVersion[m.id] ?: 0L, 36.dp)
                }
                Text(
                    m.name, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (muted) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(56.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun MessageList(vm: MainViewModel, s: ChatSession, showAvatars: Boolean, modifier: Modifier) {
    val listState = rememberLazyListState()
    val msgs = s.messages
    val atBottom by remember { derivedStateOf { listState.firstVisibleItemIndex <= 1 } }
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<ChatMessage?>(null) }
    val palette = LocalChatPalette.current
    val settings = vm.settings.value
    val userName = settings.persona.name
    // Display-only regex scripts, computed once per change rather than per row.
    val displayScripts = remember(settings.regex, settings.characterRegex, s.members) {
        vm.regexFor(s.members).filter { !it.disabled && it.markdownOnly }
    }
    val needDepth = remember(displayScripts) { RegexEngine.usesDepth(displayScripts) }

    // New message: follow it if the user was already at the bottom.
    LaunchedEffect(msgs.size) { if (atBottom && msgs.isNotEmpty()) listState.scrollToItem(0) }

    Box(modifier.fillMaxWidth()) {
        LazyColumn(
            state = listState,
            reverseLayout = true,
            contentPadding = PaddingValues(vertical = 8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            val n = msgs.size
            items(count = n, key = { msgs[n - 1 - it].id }, contentType = { 0 }) { i ->
                val m = msgs[n - 1 - i]
                // derivedStateOf: only the row being streamed recomposes on each frame.
                val stream by remember(m.id) { derivedStateOf { s.streaming?.takeIf { it.id == m.id } } }
                MessageRow(
                    vm = vm, s = s, m = m, isLast = i == 0, depth = if (needDepth) i else null,
                    stream = stream, palette = palette, showAvatar = showAvatars, userName = userName,
                    displayScripts = displayScripts, renderHtml = settings.renderHtml,
                    allowScripts = settings.allowHtmlScripts, onEdit = { editing = m },
                )
            }
        }
        AnimatedVisibility(
            visible = !atBottom, enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
        ) {
            SmallFloatingActionButton(onClick = { scope.launch { listState.animateScrollToItem(0) } }) {
                Icon(Icons.Default.KeyboardArrowDown, "Scroll to bottom")
            }
        }
    }

    editing?.let { m -> EditDialog(m.text, onDismiss = { editing = null }, onSave = { s.edit(m.id, it) }) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageRow(
    vm: MainViewModel,
    s: ChatSession,
    m: ChatMessage,
    isLast: Boolean,
    depth: Int?,
    stream: StreamState?,
    palette: ChatPalette,
    showAvatar: Boolean,
    userName: String,
    displayScripts: List<RegexScript>,
    renderHtml: Boolean,
    allowScripts: Boolean,
    onEdit: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var thinkingOpen by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val speaker = if (m.isUser) null else s.memberFor(m.name)
    val rawText = stream?.text ?: m.text
    val rawReasoning = stream?.reasoning ?: m.reasoning

    // Display regex runs on finished messages only; streaming text is shown as-is.
    val (text, reasoning) = remember(rawText, rawReasoning, displayScripts, depth, stream == null) {
        if (stream != null || displayScripts.isEmpty()) rawText to rawReasoning
        else {
            val ex = Macros(mapOf("user" to userName, "char" to (speaker?.name ?: s.character.name)))::expand
            val place = if (m.isUser) RegexPlacement.USER_INPUT else RegexPlacement.AI_OUTPUT
            RegexEngine.apply(displayScripts, rawText, place, RegexTarget.DISPLAY, depth, ex) to
                RegexEngine.apply(displayScripts, rawReasoning, RegexPlacement.REASONING, RegexTarget.DISPLAY, depth, ex)
        }
    }
    val hasHtml = remember(text) { Html.has(text) }
    val useHtml = renderHtml && stream == null && hasHtml
    val formatted = remember(text, palette, useHtml) { if (useHtml) AnnotatedString("") else formatMessage(text, palette) }
    val canSwipe = isLast && !m.isUser && !m.isSystem
    val swipeThreshold = with(LocalDensity.current) { 72.dp.toPx() }

    val bg = if (m.isUser) palette.userBubble else palette.botBubble
    Box {
        Row(
            Modifier.fillMaxWidth()
                .combinedClickable(onClick = {}, onLongClick = { menu = true })
                .then(
                    if (canSwipe) Modifier.pointerInput(m.id) {
                        var dx = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { dx = 0f },
                            onDragEnd = {
                                if (dx < -swipeThreshold) s.swipe(m.id, +1)
                                else if (dx > swipeThreshold) s.swipe(m.id, -1)
                            },
                        ) { _, amount -> dx += amount }
                    } else Modifier,
                )
                .padding(horizontal = 10.dp, vertical = 4.dp)
                .background(bg, RoundedCornerShape(14.dp))
                .padding(horizontal = 8.dp, vertical = 8.dp),
        ) {
            if (showAvatar) {
                if (m.isUser || speaker == null) Avatar("__user", userName, vm.storage.thumbFile("__user"), 0L, 34.dp)
                else Avatar(speaker.id, speaker.name, vm.storage.thumbFile(speaker.id), vm.avatarVersion[speaker.id] ?: 0L, 34.dp)
                Spacer(Modifier.width(10.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    if (m.isUser) userName else m.name.ifBlank { s.character.name },
                    style = MaterialTheme.typography.labelLarge,
                    color = if (m.isUser) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                )
                if (reasoning.isNotBlank()) {
                    Text(
                        if (thinkingOpen) "▾ Thinking" else "▸ Thinking",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable { thinkingOpen = !thinkingOpen }.padding(vertical = 4.dp),
                    )
                    if (thinkingOpen) {
                        Text(
                            reasoning, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 6.dp),
                        )
                    }
                }
                when {
                    stream != null && text.isEmpty() -> TypingDots()
                    useHtml -> {
                        val fg = MaterialTheme.colorScheme.onSurface
                        val link = MaterialTheme.colorScheme.primary
                        val fontPx = MaterialTheme.typography.bodyLarge.fontSize.value * LocalDensity.current.fontScale
                        val doc = remember(text, fg, palette, fontPx, allowScripts) {
                            Html.document(text, fg, palette, link, fontPx, allowScripts)
                        }
                        HtmlMessage(doc, onLongPress = { menu = true })
                    }
                    else -> Text(formatted, style = MaterialTheme.typography.bodyLarge)
                }
                if (canSwipe && (m.swipes.size > 1 || stream?.swipeLabel != null || !s.generating)) {
                    SwipeBar(
                        label = stream?.swipeLabel ?: "${m.swipeId + 1}/${m.swipes.size}",
                        enabled = !s.generating,
                        onLeft = { s.swipe(m.id, -1) },
                        onRight = { s.swipe(m.id, +1) },
                    )
                }
            }
        }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(text = { Text("Copy") }, onClick = { menu = false; clipboard.setText(AnnotatedString(m.text)) })
            DropdownMenuItem(text = { Text("Edit") }, enabled = stream == null, onClick = { menu = false; onEdit() })
            DropdownMenuItem(text = { Text("Branch from here") }, enabled = !s.generating, onClick = {
                menu = false; vm.branch(s, m.id)
            })
            DropdownMenuItem(text = { Text("Delete") }, enabled = !s.generating, onClick = { menu = false; s.delete(m.id) })
            DropdownMenuItem(
                text = { Text("Delete this and below") }, enabled = !s.generating,
                onClick = { menu = false; s.deleteFrom(m.id) },
            )
        }
    }
}

@Composable
private fun TypingDots() {
    Text("•••", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SwipeBar(label: String, enabled: Boolean, onLeft: () -> Unit, onRight: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 2.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onLeft, enabled = enabled, modifier = Modifier.size(36.dp)) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous swipe")
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        IconButton(onClick = onRight, enabled = enabled, modifier = Modifier.size(36.dp)) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next swipe")
        }
    }
}

@Composable
private fun InputBar(s: ChatSession) {
    var menu by remember { mutableStateOf(false) }
    val impersonating = s.streaming?.id == ChatSession.IMPERSONATE_ID
    Surface(tonalElevation = 2.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Actions") }
                DropdownMenu(menu, { menu = false }) {
                    if (s.lastPromptInfo.isNotEmpty()) {
                        Text(
                            s.lastPromptInfo, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                        HorizontalDivider()
                    }
                    DropdownMenuItem(text = { Text("Regenerate") }, enabled = !s.generating, onClick = { menu = false; s.regenerate() })
                    DropdownMenuItem(text = { Text("Continue") }, enabled = !s.generating, onClick = { menu = false; s.continueLast() })
                    DropdownMenuItem(text = { Text("Impersonate") }, enabled = !s.generating, onClick = { menu = false; s.impersonate() })
                }
            }
            OutlinedTextField(
                value = if (impersonating) s.streaming?.text ?: "" else s.draft,
                onValueChange = { if (!impersonating) s.draft = it },
                placeholder = { Text("Message") },
                maxLines = 6,
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            )
            Spacer(Modifier.width(6.dp))
            if (s.generating) {
                FilledIconButton(onClick = { s.stop() }, modifier = Modifier.padding(bottom = 4.dp)) {
                    Icon(Icons.Default.Close, "Stop")
                }
            } else {
                FilledIconButton(onClick = { s.send() }, modifier = Modifier.padding(bottom = 4.dp)) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Send")
                }
            }
        }
    }
}

@Composable
private fun EditDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit message") },
        text = {
            OutlinedTextField(
                text, { text = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 420.dp),
            )
        },
        confirmButton = { TextButton(onClick = { onDismiss(); onSave(text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatListSheet(vm: MainViewModel, s: ChatSession, onDismiss: () -> Unit) {
    val key = s.key
    var refresh by remember { mutableStateOf(0) }
    val chats by produceState<List<ChatInfo>?>(null, refresh) { value = vm.listChats(key) }
    var confirm by remember { mutableStateOf<ChatInfo?>(null) }
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Chats · ${s.title}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            OutlinedButton(onClick = { onDismiss(); vm.openKey(key, forceNew = true) }) { Text("New chat") }
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(chats.orEmpty(), key = { it.fileName }) { info ->
                val current = info.fileName == s.file.name
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { onDismiss(); if (!current) vm.openKey(key, info.fileName) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        val branch = if (info.fileName.startsWith("Branch")) " · branch" else ""
                        Text(
                            fmt.format(Date(info.lastModified)) + " · ${info.messageCount} msgs" + branch + if (current) " · open" else "",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(info.preview.ifBlank { "(empty)" }, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium)
                    }
                    IconButton(onClick = { confirm = info }) { Icon(Icons.Default.Delete, "Delete chat") }
                }
            }
        }
    }
    confirm?.let { info ->
        ConfirmDialog("Delete chat?", "This chat will be permanently deleted.", onDismiss = { confirm = null }) {
            vm.deleteChat(key, info.fileName)
            refresh++
        }
    }
}
