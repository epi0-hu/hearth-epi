package app.hearth.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.hearth.ChatSession
import app.hearth.MainViewModel
import app.hearth.Screen
import app.hearth.StreamState
import app.hearth.core.Character
import app.hearth.core.ChatInfo
import app.hearth.core.ChatMessage
import app.hearth.core.RegexItem
import app.hearth.core.RegexEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberUpdatedState
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
    // Who is writing right now (for the header status line).
    val speaking by remember(s) {
        derivedStateOf {
            val st = s.streaming ?: return@derivedStateOf null
            if (st.id == ChatSession.IMPERSONATE_ID) vm.settings.value.persona.name
            else s.messages.firstOrNull { it.id == st.id }?.name
        }
    }
    val ember = LocalChatPalette.current.ember

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                colors = hearthBarColors(),
                navigationIcon = {
                    IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = openDetails).padding(4.dp),
                    ) {
                        if (group != null) GroupAvatar(vm, s.members, 32.dp)
                        else Avatar(c.id, c.name, vm.storage.thumbFile(c.id), vm.avatarVersion[c.id] ?: 0L, 36.dp,
                            Modifier.glowRing(speaking != null, ember))
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(s.title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            AnimatedContent(
                                targetState = speaking,
                                transitionSpec = { (fadeIn(tween(220)) + slideInVertically { it / 2 }) togetherWith fadeOut(tween(160)) },
                                label = "status",
                            ) { who ->
                                if (who != null) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        EmberDots(dot = 4.dp)
                                        Spacer(Modifier.width(8.dp))
                                        Text("$who is writing…", style = MaterialTheme.typography.labelMedium, color = ember)
                                    }
                                } else {
                                    Text(
                                        if (group != null) s.members.joinToString(", ") { it.name } else "${s.messages.size} messages",
                                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Menu") }
                },
            )
        },
        snackbarHost = { SnackbarHost(rememberToasts(vm)) },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(top = pad.calculateTopPadding())
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)),
        ) {
            Box(Modifier.weight(1f)) {
                MessageList(vm, s, settings.showAvatars, settings.chatSerif, Modifier.fillMaxSize())
                if (s.messages.isEmpty()) EmptyChat(vm, s)
            }
            if (group != null) MemberStrip(vm, s)
            InputBar(s)
        }
    }

    if (menu) {
        ActionSheet(
            title = s.title, subtitle = s.file.name.removeSuffix(".jsonl"),
            onDismiss = { menu = false },
            actions = listOf(
                SheetAction("All chats", Icons.Default.List) { showChats = true },
                SheetAction("Start a new chat", Icons.Default.Add) { vm.openKey(s.key, forceNew = true) },
                SheetAction(if (group != null) "Edit group" else "Edit character", Icons.Default.Edit) { openDetails() },
                SheetAction("Regex scripts", HearthIcons.Regex) { vm.navigate(Screen.Regex) },
                SheetAction("Settings", Icons.Default.Settings) { vm.navigate(Screen.Settings) },
            ),
        )
    }
    if (showChats) ChatListSheet(vm, s, onDismiss = { showChats = false })
}

/** Up to three overlapping member avatars. */
@Composable
fun GroupAvatar(vm: MainViewModel, members: List<Character>, size: Dp) {
    val shown = members.take(3)
    val step = size * 0.45f
    Box(Modifier.width(size + step * (shown.size - 1).coerceAtLeast(0)).height(size)) {
        shown.forEachIndexed { i, m ->
            Avatar(
                m.id, m.name, vm.storage.thumbFile(m.id), vm.avatarVersion[m.id] ?: 0L, size,
                Modifier.offset(x = step * i).border(1.5.dp, MaterialTheme.colorScheme.background, CircleShape),
            )
        }
    }
}

@Composable
private fun EmptyChat(vm: MainViewModel, s: ChatSession) {
    val c = s.character
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Avatar(c.id, c.name, vm.storage.thumbFile(c.id), vm.avatarVersion[c.id] ?: 0L, 96.dp,
            Modifier.glowRing(true, LocalChatPalette.current.ember.copy(alpha = 0.6f), 2.dp))
        Spacer(Modifier.height(20.dp))
        Text(s.title, style = MaterialTheme.typography.headlineSmall)
        Text(
            "Nothing said yet. Write the first line below.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** Group chats: tap a member to make them reply. Muted members are dimmed. */
@Composable
private fun MemberStrip(vm: MainViewModel, s: ChatSession) {
    val g = s.group ?: return
    val ember = LocalChatPalette.current.ember
    val speakingId = s.streaming?.let { st -> s.messages.firstOrNull { it.id == st.id } }?.let { s.memberFor(it.name).id }
    LazyRow(
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        items(s.members, key = { it.id }) { m ->
            val muted = m.id !in g.active
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(enabled = !s.generating) { s.speak(m) }
                    .padding(4.dp).graphicsLayer { alpha = if (muted) 0.45f else 1f },
            ) {
                Avatar(m.id, m.name, vm.storage.thumbFile(m.id), vm.avatarVersion[m.id] ?: 0L, 36.dp,
                    Modifier.glowRing(m.id == speakingId, ember))
                Text(
                    m.name, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                    modifier = Modifier.width(56.dp).padding(top = 3.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageList(vm: MainViewModel, s: ChatSession, showAvatars: Boolean, serif: Boolean, modifier: Modifier) {
    val listState = rememberLazyListState()
    val msgs = s.messages
    val atBottom by remember { derivedStateOf { listState.firstVisibleItemIndex <= 1 } }
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<ChatMessage?>(null) }
    var actionsFor by remember { mutableStateOf<ChatMessage?>(null) }
    val palette = LocalChatPalette.current
    val settings = vm.settings.value
    val userName = settings.persona.name
    val reduced = LocalReducedMotion.current
    val prose = remember(serif) { proseStyle(serif) }
    // Display-only regex scripts, computed once per change rather than per row.
    val displayScripts = remember(settings.regex, settings.preset.regex, settings.characterRegex, s.members) {
        vm.regexFor(s.members).filter { !it.disabled && it.markdownOnly }
    }
    val needDepth = remember(displayScripts) { RegexEngine.usesDepth(displayScripts) }

    // New message: follow it if the user was already at the bottom.
    LaunchedEffect(msgs.size) { if (atBottom && msgs.isNotEmpty()) listState.scrollToItem(0) }

    Box(modifier) {
        LazyColumn(
            state = listState,
            reverseLayout = true,
            contentPadding = PaddingValues(top = 8.dp, bottom = 12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            val n = msgs.size
            items(count = n, key = { msgs[n - 1 - it].id }, contentType = { 0 }) { i ->
                val m = msgs[n - 1 - i]
                // derivedStateOf: only the row being streamed recomposes on each frame.
                val stream by remember(m.id) { derivedStateOf { s.streaming?.takeIf { it.id == m.id } } }
                val prev = if (n - 2 - i >= 0) msgs[n - 2 - i] else null
                MessageRow(
                    vm = vm, s = s, m = m, isLast = i == 0, depth = if (needDepth) i else null,
                    sameSpeakerAsPrev = prev != null && prev.isUser == m.isUser && prev.name == m.name,
                    stream = stream, palette = palette, prose = prose, showAvatar = showAvatars, userName = userName,
                    displayScripts = displayScripts, renderHtml = settings.renderHtml,
                    allowScripts = settings.allowHtmlScripts,
                    onActions = { actionsFor = m },
                    modifier = if (reduced) Modifier else Modifier.animateItem(
                        fadeInSpec = tween(360), placementSpec = spring(stiffness = Spring.StiffnessMediumLow), fadeOutSpec = tween(200),
                    ),
                )
            }
        }
        AnimatedVisibility(
            visible = !atBottom, enter = fadeIn() + scaleIn(initialScale = 0.6f), exit = fadeOut() + scaleOut(targetScale = 0.6f),
            modifier = Modifier.align(Alignment.BottomEnd).padding(14.dp),
        ) {
            SmallFloatingActionButton(
                onClick = { scope.launch { listState.animateScrollToItem(0) } },
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) { Icon(Icons.Default.KeyboardArrowDown, "Scroll to bottom") }
        }
    }

    val clipboard = LocalClipboardManager.current
    actionsFor?.let { m ->
        val isLast = msgs.lastOrNull()?.id == m.id
        val canRegen = isLast && !m.isUser
        ActionSheet(
            title = if (m.isUser) userName else m.name,
            subtitle = m.text.replace('\n', ' ').take(90),
            onDismiss = { actionsFor = null },
            actions = listOfNotNull(
                SheetAction("Copy text", HearthIcons.Copy) { clipboard.setText(AnnotatedString(m.text)) },
                SheetAction("Edit", Icons.Default.Edit, enabled = !s.generating) { editing = m },
                if (canRegen) SheetAction("Regenerate (new swipe)", Icons.Default.Refresh, enabled = !s.generating) { s.regenerate() } else null,
                if (canRegen) SheetAction("Continue writing", HearthIcons.Continue, enabled = !s.generating) { s.continueLast() } else null,
                SheetAction("Branch from here", HearthIcons.Branch, enabled = !s.generating) { vm.branch(s, m.id) },
                SheetAction("Delete message", Icons.Default.Delete, destructive = true, enabled = !s.generating) { s.delete(m.id) },
                SheetAction("Delete this and everything after", Icons.Default.Delete, destructive = true, enabled = !s.generating) {
                    s.deleteFrom(m.id)
                },
            ),
        )
    }
    editing?.let { m -> EditDialog(m.text, onDismiss = { editing = null }, onSave = { s.edit(m.id, it) }) }
}

/** Display-regex results, so scrolling back to a message is instant. */
private object DisplayCache {
    private val cache = android.util.LruCache<String, Pair<String, String>>(400)
    fun get(k: String): Pair<String, String>? = cache.get(k)
    fun put(k: String, v: Pair<String, String>) { cache.put(k, v) }
}

private fun displayKey(t: String, r: String, scripts: List<RegexScript>, depth: Int?, vars: Map<String, String>): String =
    "${t.hashCode()}:${t.length}:${r.hashCode()}:${System.identityHashCode(scripts)}:${depth}:${vars.hashCode()}"

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageRow(
    vm: MainViewModel,
    s: ChatSession,
    m: ChatMessage,
    isLast: Boolean,
    depth: Int?,
    sameSpeakerAsPrev: Boolean,
    stream: StreamState?,
    palette: ChatPalette,
    prose: androidx.compose.ui.text.TextStyle,
    showAvatar: Boolean,
    userName: String,
    displayScripts: List<RegexScript>,
    renderHtml: Boolean,
    allowScripts: Boolean,
    onActions: () -> Unit,
    modifier: Modifier,
) {
    var thinkingOpen by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val speaker = if (m.isUser) null else s.memberFor(m.name)
    val rawText = stream?.text ?: m.text
    val rawReasoning = stream?.reasoning ?: m.reasoning

    // Display regex (and markdown images) apply live, including while a reply streams in.
    val vars = s.variables
    val charName = speaker?.name ?: s.character.name
    val latest by rememberUpdatedState(Triple(rawText, rawReasoning, stream != null))
    // Display regex runs in the JavaScript engine (exact SillyTavern behavior). Results are
    // cached; while a reply streams, runs are throttled so they never queue up.
    var processed by remember(m.id, displayScripts, depth, vars) {
        mutableStateOf(if (displayScripts.isEmpty()) null else DisplayCache.get(displayKey(rawText, rawReasoning, displayScripts, depth, vars)))
    }
    if (displayScripts.isNotEmpty()) {
        LaunchedEffect(m.id, displayScripts, depth, vars, userName, charName) {
            snapshotFlow { latest }.conflate().collect { (t, r, streaming) ->
                val k = displayKey(t, r, displayScripts, depth, vars)
                val res = DisplayCache.get(k) ?: run {
                    val place = if (m.isUser) RegexPlacement.USER_INPUT else RegexPlacement.AI_OUTPUT
                    val out = vm.regexService.apply(
                        displayScripts,
                        listOf(RegexItem(t, place, depth), RegexItem(r, RegexPlacement.REASONING, depth)),
                        RegexTarget.DISPLAY, mapOf("user" to userName, "char" to charName), vars,
                    )
                    (out[0] to out[1]).also { DisplayCache.put(k, it) }
                }
                processed = res
                if (streaming) delay(90)
            }
        }
    }
    val (text, reasoning) = remember(processed, rawText, rawReasoning, displayScripts) {
        val p = processed
        when {
            displayScripts.isEmpty() -> Html.markdownImages(rawText) to rawReasoning
            p != null -> Html.markdownImages(p.first) to p.second
            stream != null -> Html.markdownImages(rawText) to rawReasoning
            else -> "" to "" // a few milliseconds until the first result arrives
        }
    }
    val hasHtml = remember(text) { Html.has(text) }
    val useHtml = renderHtml && hasHtml
    val canSwipe = isLast && !m.isUser && !m.isSystem
    val swipeThreshold = with(LocalDensity.current) { 72.dp.toPx() }
    val ember = palette.ember
    val nameColor = if (m.isUser) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary

    Column(
        modifier.fillMaxWidth()
            .combinedClickable(onClick = {}, onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onActions() })
            .then(
                if (canSwipe) Modifier.pointerInput(m.id) {
                    var dx = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { dx = 0f },
                        onDragEnd = {
                            if (dx < -swipeThreshold) { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); s.swipe(m.id, +1) }
                            else if (dx > swipeThreshold) { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); s.swipe(m.id, -1) }
                        },
                    ) { _, amount -> dx += amount }
                } else Modifier,
            )
            .padding(start = 20.dp, end = 20.dp, top = if (sameSpeakerAsPrev) 4.dp else 14.dp, bottom = 6.dp),
    ) {
        // Header: small portrait + name, omitted when the same speaker continues.
        if (!sameSpeakerAsPrev || stream != null) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
                if (showAvatar) {
                    if (m.isUser || speaker == null) Avatar("__user", userName, vm.storage.thumbFile("__user"), 0L, 24.dp)
                    else Avatar(speaker.id, speaker.name, vm.storage.thumbFile(speaker.id), vm.avatarVersion[speaker.id] ?: 0L, 24.dp,
                        Modifier.glowRing(stream != null, ember, 1.5.dp))
                    Spacer(Modifier.width(10.dp))
                }
                Eyebrow(if (m.isUser) userName else m.name.ifBlank { s.character.name }, color = nameColor)
            }
        }
        Column(
            if (m.isUser) Modifier.drawBehind {
                // A thin ember thread marks your own lines.
                drawLine(
                    Brush.verticalGradient(listOf(ember.copy(alpha = 0.7f), ember.copy(alpha = 0.1f))),
                    Offset(0f, 2f), Offset(0f, size.height - 2f), strokeWidth = 2.dp.toPx(),
                )
            }.padding(start = 14.dp) else Modifier,
        ) {
            if (reasoning.isNotBlank()) {
                Row(
                    Modifier.clip(RoundedCornerShape(10.dp)).clickable { thinkingOpen = !thinkingOpen }
                        .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.6f))
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(HearthIcons.Sparkle, null, Modifier.size(14.dp), tint = ember)
                    Spacer(Modifier.width(6.dp))
                    Text(if (thinkingOpen) "Hide thoughts" else "Thoughts", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                AnimatedVisibility(thinkingOpen, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    Text(
                        reasoning, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp, bottom = 4.dp),
                    )
                }
                Spacer(Modifier.height(6.dp))
            }
            // Swipes slide in from the side they came from; edits and streaming don't animate.
            AnimatedContent(
                targetState = m.swipeId to text,
                contentKey = { it.first },
                transitionSpec = {
                    val dir = if (targetState.first > initialState.first) 1 else -1
                    (slideInHorizontally(tween(280, easing = FastOutSlowInEasing)) { it / 3 * dir } + fadeIn(tween(220)))
                        .togetherWith(slideOutHorizontally(tween(220)) { -it / 3 * dir } + fadeOut(tween(160)))
                },
                label = "swipe",
            ) { (_, body) ->
                MessageBody(body, stream, useHtml && body == text, palette, prose, m.isUser, renderHtml, allowScripts, onActions)
            }
            if (canSwipe && (m.swipes.size > 1 || stream?.swipeLabel != null || !s.generating)) {
                SwipeBar(
                    index = m.swipeId, count = m.swipes.size, streamingLabel = stream?.swipeLabel,
                    enabled = !s.generating,
                    onLeft = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); s.swipe(m.id, -1) },
                    onRight = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); s.swipe(m.id, +1) },
                )
            }
        }
    }
}

@Composable
private fun MessageBody(
    text: String,
    stream: StreamState?,
    useHtml: Boolean,
    palette: ChatPalette,
    prose: androidx.compose.ui.text.TextStyle,
    isUser: Boolean,
    renderHtml: Boolean,
    allowScripts: Boolean,
    onActions: () -> Unit,
) {
    val color = if (isUser) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.86f) else MaterialTheme.colorScheme.onSurface
    when {
        stream != null && text.isEmpty() -> EmberDots(modifier = Modifier.padding(vertical = 8.dp))
        useHtml && renderHtml -> {
            val fg = MaterialTheme.colorScheme.onSurface
            val link = MaterialTheme.colorScheme.primary
            val fontPx = prose.fontSize.value * LocalDensity.current.fontScale
            val serif = prose.fontFamily == FontFamily.Serif
            val shell = remember(fg, palette, link, fontPx, allowScripts, serif) { Html.shell(fg, palette, link, fontPx, allowScripts, serif) }
            val streaming = stream != null
            val body = remember(text, streaming) {
                Html.formatBody(text) + if (streaming) "<span class=\"hearth-caret\"> ▍</span>" else ""
            }
            HtmlMessage(body, shell, final = !streaming, onLongPress = onActions)
        }
        else -> {
            val formatted = remember(text, palette, stream != null) {
                val base = formatMessage(text, palette)
                if (stream == null) base
                else buildAnnotatedString {
                    append(base)
                    // A glowing caret rides the end of the text while it streams in.
                    withStyle(SpanStyle(color = palette.ember)) { append(" ▍") }
                }
            }
            Text(formatted, style = prose, color = color)
        }
    }
}

@Composable
private fun SwipeBar(index: Int, count: Int, streamingLabel: String?, enabled: Boolean, onLeft: () -> Unit, onRight: () -> Unit) {
    val ember = LocalChatPalette.current.ember
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onLeft, enabled = enabled, modifier = Modifier.size(34.dp)) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous swipe")
        }
        if (streamingLabel == null && count in 2..7) {
            // Dots for a handful of swipes; the current one glows.
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                repeat(count) { i ->
                    val w by animateFloatAsState(if (i == index) 14f else 5f, tween(220), label = "dot")
                    Box(
                        Modifier.height(5.dp).width(w.dp).clip(CircleShape)
                            .background(if (i == index) ember else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                    )
                }
            }
        } else {
            Text(streamingLabel ?: "${index + 1}/$count", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onRight, enabled = enabled, modifier = Modifier.size(34.dp)) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next swipe")
        }
    }
}

@Composable
private fun InputBar(s: ChatSession) {
    var showActions by remember { mutableStateOf(false) }
    val impersonating = s.streaming?.id == ChatSession.IMPERSONATE_ID
    val ember = LocalChatPalette.current.ember
    val haptics = LocalHapticFeedback.current
    val focusSource = remember { MutableInteractionSource() }
    val focused by focusSource.collectIsFocusedAsState()
    val glow by animateFloatAsState(if (focused || s.generating) 1f else 0f, tween(300), label = "glow")
    val sparkRot by animateFloatAsState(if (showActions) 90f else 0f, tween(260), label = "spark")

    Column(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 8.dp, top = 4.dp)) {
        AnimatedVisibility(showActions, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(Modifier.padding(bottom = 8.dp)) {
                if (s.lastPromptInfo.isNotEmpty()) {
                    Text(s.lastPromptInfo, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp, bottom = 6.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuickAction("Regenerate", Icons.Default.Refresh, !s.generating) { showActions = false; s.regenerate() }
                    QuickAction("Continue", HearthIcons.Continue, !s.generating) { showActions = false; s.continueLast() }
                    QuickAction("Impersonate", HearthIcons.Mask, !s.generating) { showActions = false; s.impersonate() }
                }
            }
        }
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f),
            border = BorderStroke(1.dp, ember.copy(alpha = 0.15f + 0.45f * glow)),
            shadowElevation = 6.dp,
        ) {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.Bottom) {
                IconButton(onClick = { showActions = !showActions }) {
                    Icon(HearthIcons.Sparkle, "Actions", tint = if (showActions) ember else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.graphicsLayer { rotationZ = sparkRot })
                }
                BasicTextField(
                    value = if (impersonating) s.streaming?.text ?: "" else s.draft,
                    onValueChange = { if (!impersonating) s.draft = it },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(ember),
                    maxLines = 6,
                    interactionSource = focusSource,
                    modifier = Modifier.weight(1f).padding(vertical = 13.dp, horizontal = 4.dp),
                    decorationBox = { inner ->
                        Box {
                            if (s.draft.isEmpty() && !impersonating) {
                                Text("Say something…", style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                            }
                            inner()
                        }
                    },
                )
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    AnimatedContent(
                        targetState = s.generating,
                        transitionSpec = { (scaleIn(tween(220), 0.6f) + fadeIn()) togetherWith (scaleOut(tween(180), targetScale = 0.6f) + fadeOut()) },
                        label = "send",
                    ) { gen ->
                        if (gen) {
                            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                                SpinningEmberRing(44.dp)
                                IconButton(onClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); s.stop() }) {
                                    Icon(HearthIcons.Stop, "Stop", tint = ember)
                                }
                            }
                        } else {
                            FilledIconButton(
                                onClick = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); s.send() },
                                colors = IconButtonDefaults.filledIconButtonColors(containerColor = ember, contentColor = Color(0xFF2A1408)),
                            ) { Icon(Icons.AutoMirrored.Filled.Send, "Send", Modifier.size(20.dp)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable(enabled = enabled, onClick = onClick),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(16.dp), tint = LocalChatPalette.current.ember)
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
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
                textStyle = proseStyle(true).copy(fontSize = MaterialTheme.typography.bodyLarge.fontSize),
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
    val ember = LocalChatPalette.current.ember

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Eyebrow("Chats")
                Text(s.title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            OutlinedButton(onClick = { onDismiss(); vm.openKey(key, forceNew = true) }) { Text("New chat") }
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp), contentPadding = PaddingValues(bottom = 24.dp, top = 8.dp)) {
            items(chats.orEmpty(), key = { it.fileName }) { info ->
                val current = info.fileName == s.file.name
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (current) ember.copy(alpha = 0.10f) else Color.Transparent)
                        .clickable { onDismiss(); if (!current) vm.openKey(key, info.fileName) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (info.fileName.startsWith("Branch")) {
                        Icon(HearthIcons.Branch, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(10.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            fmt.format(Date(info.lastModified)) + " · ${info.messageCount} msgs" + if (current) " · open" else "",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (current) ember else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(info.preview.ifBlank { "(empty)" }, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Serif))
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
