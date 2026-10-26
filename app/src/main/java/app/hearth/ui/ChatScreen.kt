package app.hearth.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import app.hearth.core.ChatInfo
import app.hearth.core.ChatMessage
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

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { vm.navigate(Screen.EditCharacter(c.id)) },
                    ) {
                        Avatar(c.id, c.name, vm.storage.thumbFile(c.id), vm.avatarVersion[c.id] ?: 0L, 34.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(c.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Menu") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text("Chats") }, onClick = { menu = false; showChats = true })
                            DropdownMenuItem(text = { Text("New chat") }, onClick = {
                                menu = false; vm.openChat(c.id, forceNew = true)
                            })
                            DropdownMenuItem(text = { Text("Edit character") }, onClick = {
                                menu = false; vm.navigate(Screen.EditCharacter(c.id))
                            })
                            DropdownMenuItem(text = { Text("Settings") }, onClick = {
                                menu = false; vm.navigate(Screen.Settings)
                            })
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
            InputBar(s)
        }
    }

    if (showChats) ChatListSheet(vm, s, onDismiss = { showChats = false })
}

@Composable
private fun MessageList(vm: MainViewModel, s: ChatSession, showAvatars: Boolean, modifier: Modifier) {
    val listState = rememberLazyListState()
    val msgs = s.messages
    val atBottom by remember { derivedStateOf { listState.firstVisibleItemIndex <= 1 } }
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<ChatMessage?>(null) }
    val palette = LocalChatPalette.current
    val userName = vm.settings.value.persona.name

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
                    vm = vm, s = s, m = m, isLast = i == 0, stream = stream, palette = palette,
                    showAvatar = showAvatars, userName = userName, onEdit = { editing = m },
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
    stream: StreamState?,
    palette: ChatPalette,
    showAvatar: Boolean,
    userName: String,
    onEdit: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var thinkingOpen by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val text = stream?.text ?: m.text
    val reasoning = stream?.reasoning ?: m.reasoning
    val formatted = remember(text, palette) { formatMessage(text, palette) }
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
                if (m.isUser) Avatar("__user", userName, vm.storage.thumbFile("__user"), 0L, 34.dp)
                else Avatar(s.character.id, m.name, vm.storage.thumbFile(s.character.id), vm.avatarVersion[s.character.id] ?: 0L, 34.dp)
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
                if (stream != null && text.isEmpty()) {
                    TypingDots()
                } else {
                    Text(formatted, style = MaterialTheme.typography.bodyLarge)
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
    val charId = s.character.id
    var refresh by remember { mutableStateOf(0) }
    val chats by produceState<List<ChatInfo>?>(null, refresh) { value = vm.listChats(charId) }
    var confirm by remember { mutableStateOf<ChatInfo?>(null) }
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Chats with ${s.character.name}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { onDismiss(); vm.openChat(charId, forceNew = true) }) { Text("New chat") }
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(chats.orEmpty(), key = { it.fileName }) { info ->
                val current = info.fileName == s.file.name
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { onDismiss(); if (!current) vm.openChat(charId, info.fileName) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            fmt.format(Date(info.lastModified)) + " · ${info.messageCount} msgs" + if (current) " · open" else "",
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
            vm.deleteChat(charId, info.fileName)
            refresh++
        }
    }
}
