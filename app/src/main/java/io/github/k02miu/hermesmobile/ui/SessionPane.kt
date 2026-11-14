package io.github.k02miu.hermesmobile.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import io.github.k02miu.hermesmobile.ui.theme.HermesColors
import io.github.k02miu.hermesmobile.ui.theme.MonoFamily
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider

@Composable
fun SessionPane(
    vm: SessionViewModel, profileLabel: String, avatar: ImageBitmap?, onBack: (() -> Unit)?, modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    /** A narrow pane (three side by side on a Fold): the header drops the avatar and shortens its badge. */
    compact: Boolean = false,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var preview by rememberSaveable { mutableStateOf<String?>(null) }
    var toolKey by rememberSaveable { mutableStateOf<String?>(null) }
    var showProcesses by rememberSaveable { mutableStateOf(false) }
    var showModels by rememberSaveable { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current
    LaunchedEffect(vm) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { vm.watchProcesses() } }
    LifecycleResumeEffect(vm) {
        vm.refresh()
        onPauseOrDispose {}
    }
    Box(modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().background(HermesColors.Ground).imePadding()) {
        Header(state, profileLabel, avatar, onBack, leading, actions, compact)
        Box(Modifier.weight(1f)) {
            Transcript(state, onAnswer = vm::answer, onOpenMedia = { preview = it }, onOpenTool = { toolKey = it.key })
            if (state.loading) CircularProgressIndicator(Modifier.align(Alignment.Center), color = HermesColors.Working)
            else if (state.items.isEmpty() && state.prompts.isEmpty()) {
                Text("$profileLabel に話しかけて始めましょう", color = HermesColors.Muted, fontSize = 14.sp,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp))
            }
        }
        state.error?.let { Text(it, color = HermesColors.Danger, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
        ProcessStrip(state.processes, onOpen = { showProcesses = true })
        Composer(
            running = state.running, model = state.model, onPickModel = { showModels = true },
            reasoning = state.reasoning, onReasoning = vm::setReasoning, onSend = vm::send, onStop = vm::interrupt,
        )
    }
    // Looked up by key so a call still running fills in as its result arrives.
    toolKey?.let { key ->
        val tool = state.items.firstOrNull { it.key == key } as? ChatItem.Tool
        if (tool == null) toolKey = null else ToolSheet(tool, onDismiss = { toolKey = null })
    }
    if (showModels) ModelSheet(
        current = state.provider to state.model, load = vm::modelOptions,
        onPick = { provider, model -> showModels = false; vm.setModel(provider, model) }, onDismiss = { showModels = false },
    )
    state.modelConfirm?.let { ask ->
        AlertDialog(
            onDismissRequest = vm::dismissModelConfirm,
            title = { Text("${ask.model} に切り替えますか") },
            text = { Text(ask.message.ifBlank { "このモデルは料金が高めです。" }) },
            confirmButton = { TextButton(onClick = { vm.setModel(ask.provider, ask.model, confirm = ask.token) }) { Text("切り替える", color = HermesColors.Accent) } },
            dismissButton = { TextButton(onClick = vm::dismissModelConfirm) { Text("やめる") } },
        )
    }
    if (showProcesses) ProcessSheet(state.processes, onKill = { vm.killProcess(it.id) }, onDismiss = { showProcesses = false })
    state.prompts.filterIsInstance<PendingPrompt.Approval>().firstOrNull()?.let { approval ->
        ApprovalSheet(approval, state.title, onChoose = { vm.answer(approval, it) })
    }
    // Over the chat rather than in a dialog: a WebView in a dialog window draws over the dialog's own top bar.
    preview?.let { MediaPreview(it, load = vm::readFile, onClose = { preview = null }) }
    }
}

@Composable
private fun Header(
    state: SessionState, profileLabel: String, avatar: ImageBitmap?, onBack: (() -> Unit)?,
    leading: (@Composable () -> Unit)?, actions: @Composable RowScope.() -> Unit, compact: Boolean,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = if (onBack != null || leading != null) 4.dp else 20.dp, end = 8.dp, top = 12.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "戻る") }
        leading?.invoke()
        if (!compact) {
            BotAvatar(32, avatar)
            Spacer(Modifier.size(10.dp))
        }
        Column(Modifier.weight(1f)) {
            // The bot is who you are talking to; which of its conversations this is comes second.
            Text(profileLabel, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(state.title, fontSize = 11.sp, color = HermesColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        // Where there is no room for the word, a dot of the same colour says it.
        if (compact) {
            if (state.running || state.waiting) Box(Modifier.size(10.dp).background(if (state.running) HermesColors.Working else HermesColors.Accent, RoundedCornerShape(5.dp)))
        } else if (state.running) Badge("作業中", HermesColors.WorkingSurface, HermesColors.Working)
        else if (state.waiting) Badge("待機中", HermesColors.AccentSurface, HermesColors.Accent)
        Spacer(Modifier.size(if (compact) 2.dp else 8.dp))
        actions()
    }
    Box(Modifier.fillMaxWidth().heightIn(min = 1.dp).background(HermesColors.Line))
}

@Composable
private fun Transcript(
    state: SessionState, onAnswer: (PendingPrompt, String, String) -> Unit, onOpenMedia: (String) -> Unit, onOpenTool: (ChatItem.Tool) -> Unit,
) {
    // Laid out bottom-up (newest first, reversed) so a session always opens on its latest message,
    // however late the history arrives. New rows follow along only while the reader is at the bottom.
    val list = rememberLazyListState()
    val clarifies = state.prompts.filterNot { it is PendingPrompt.Approval }
    val rows = remember(state.items) { groupTools(state.items) }
    val newestKey = clarifies.lastOrNull()?.promptId ?: state.items.lastOrNull()?.key
    // Whether the reader is at the bottom, judged only while they scroll: the list keeps its place by item key,
    // so rows arriving below shift firstVisibleItemIndex up and would otherwise read as "scrolled away".
    var atBottom by remember { mutableStateOf(true) }
    LaunchedEffect(list) {
        snapshotFlow { list.isScrollInProgress to list.firstVisibleItemIndex }
            .collect { (scrolling, index) -> if (scrolling) atBottom = index <= 1 }
    }
    LaunchedEffect(newestKey) {
        if (atBottom) list.scrollToItem(0)
    }
    // On a tablet the pane is far wider than a comfortable line: keep the conversation in a centred column,
    // padded rather than narrowed so the whole pane still scrolls.
    // The prompt of the turn being read, once its own bubble has scrolled off the top. Rows are laid out newest
    // first, so "above" is a higher index: walk up from the topmost visible row to the nearest prompt.
    val layout by rememberUpdatedState(rows to clarifies.size)
    val pinned by remember(list) {
        derivedStateOf {
            val (current, offset) = layout
            val top = list.layoutInfo.visibleItemsInfo.maxByOrNull { it.index } ?: return@derivedStateOf null
            val index = pinnedPromptIndex(current, firstRow = current.lastIndex - (top.index - offset)) ?: return@derivedStateOf null
            val isTopRow = index == current.lastIndex - (top.index - offset)
            // Still on screen in full: the bubble speaks for itself.
            if (isTopRow && top.offset + top.size <= list.layoutInfo.viewportEndOffset) null
            else (current[index] as TranscriptRow.Single).item as ChatItem.User
        }
    }
    BoxWithConstraints {
        val side = maxOf(16.dp, (maxWidth - READING_WIDTH) / 2)
        LazyColumn(
            state = list, modifier = Modifier.fillMaxSize(), reverseLayout = true,
            contentPadding = PaddingValues(horizontal = side, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Bottom),
        ) {
            items(clarifies.asReversed(), key = { "clarify-" + it.promptId }) { ClarifyCard(it, onAnswer) }
            items(rows.asReversed(), key = { it.key }) { row ->
                when (row) {
                    is TranscriptRow.Tools -> ToolGroup(row, onOpenTool)
                    is TranscriptRow.Single -> when (val item = row.item) {
                        is ChatItem.User -> UserBubble(item)
                        is ChatItem.Assistant -> AssistantText(item, onOpenMedia)
                        is ChatItem.Tool -> ToolRow(item, onOpenTool)
                        is ChatItem.Notice -> Text(item.text, fontSize = 12.sp, color = HermesColors.Danger)
                    }
                }
            }
        }
        PinnedPrompt(pinned, Modifier.align(Alignment.TopCenter).padding(horizontal = side - 4.dp, vertical = 6.dp))
    }
}

/**
 * Floats over the top of the conversation. Two lines by default, since the point is to recall what was asked
 * without giving up the screen; a tap opens the whole prompt and another closes it.
 */
@Composable
private fun PinnedPrompt(prompt: ChatItem.User?, modifier: Modifier) {
    var expandedKey by remember { mutableStateOf<String?>(null) }
    AnimatedContent(
        targetState = prompt, modifier = modifier, contentKey = { it?.key },
        transitionSpec = { (fadeIn() + slideInVertically { -it / 2 }) togetherWith (fadeOut() + slideOutVertically { -it / 2 }) },
        label = "pinned prompt",
    ) { shown ->
        if (shown == null) Box(Modifier.fillMaxWidth())
        else {
            val expanded = expandedKey == shown.key
            val shape = RoundedCornerShape(14.dp)
            Row(
                Modifier.fillMaxWidth().shadow(6.dp, shape).background(HermesColors.SurfaceHigh, shape).border(1.dp, HermesColors.Line, shape)
                    .clip(shape).clickable(onClickLabel = if (expanded) "たたむ" else "全文を見る") { expandedKey = if (expanded) null else shown.key }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Box(Modifier.padding(top = 5.dp).size(width = 3.dp, height = 14.dp).background(HermesColors.Accent, RoundedCornerShape(2.dp)))
                Spacer(Modifier.size(10.dp))
                Text(
                    shown.text.trim(), fontSize = 13.sp, lineHeight = 20.sp, color = HermesColors.Text,
                    maxLines = if (expanded) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).then(if (expanded) Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()) else Modifier),
                )
            }
        }
    }
}

/** The signed-in providers and their models, with a filter: some providers list dozens. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelSheet(current: Pair<String, String>, load: suspend () -> ModelOptions, onPick: (String, String) -> Unit, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var options by remember { mutableStateOf<ModelOptions?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    // Open on the provider in use; the rest stay folded until asked for (or until a search looks inside them).
    var opened by remember { mutableStateOf(setOf(current.first)) }
    LaunchedEffect(Unit) {
        runCatching { load() }.onSuccess { options = it }.onFailure { error = "モデルの一覧を取れませんでした（${it.message}）" }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = HermesColors.Surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Text("モデル", fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text("この会話だけに効きます。Bot の既定のモデルは変わりません。", fontSize = 12.sp, color = HermesColors.Muted, modifier = Modifier.padding(top = 2.dp, bottom = 10.dp))
            OutlinedTextField(
                value = query, onValueChange = { query = it }, placeholder = { Text("モデル名で絞り込む", fontSize = 14.sp) },
                singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
            )
        }
        val loaded = options
        when {
            error != null -> Text(error!!, color = HermesColors.Danger, fontSize = 13.sp, modifier = Modifier.padding(20.dp))
            loaded == null -> Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = HermesColors.Working) }
            else -> {
                val needle = query.trim()
                // The provider in use first, the rest in the order Hermes lists them.
                val providers = loaded.providers.sortedByDescending { it.slug == current.first }
                LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 28.dp)) {
                    providers.forEach { provider ->
                        val matches = if (needle.isEmpty()) provider.models else provider.models.filter { it.contains(needle, ignoreCase = true) }
                        if (matches.isEmpty()) return@forEach
                        val open = needle.isNotEmpty() || provider.slug in opened
                        item(key = "provider-" + provider.slug) {
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                    .clickable { opened = if (provider.slug in opened) opened - provider.slug else opened + provider.slug }
                                    .padding(horizontal = 8.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(provider.name.ifBlank { provider.slug }, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = HermesColors.TextSoft, modifier = Modifier.weight(1f))
                                Text("${matches.size} 件", fontSize = 12.sp, color = HermesColors.Muted)
                                Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = HermesColors.Muted, modifier = Modifier.padding(start = 6.dp).size(20.dp))
                            }
                        }
                        if (open) {
                            // Hermes's own picks for the provider lead, then the rest as listed.
                            val ordered = matches.filter { it in provider.featured } + matches.filterNot { it in provider.featured }
                            items(ordered, key = { provider.slug + "/" + it }) { model ->
                                val selected = provider.slug == current.first && model == current.second
                                Row(
                                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                        .background(if (selected) HermesColors.SurfaceHigh else HermesColors.Surface)
                                        .clickable { onPick(provider.slug, model) }.padding(horizontal = 12.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(model, fontSize = 14.sp, fontFamily = MonoFamily, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                    if (selected) Icon(Icons.Default.Check, "使用中", tint = HermesColors.Accent, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The widest the conversation column gets on a tablet, so lines stay short enough to read. */
private val READING_WIDTH = 860.dp

@Composable
private fun UserBubble(item: ChatItem.User) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        SelectionContainer {
            Text(
                item.text, fontSize = 14.sp, lineHeight = 22.sp,
                color = if (item.pending) HermesColors.TextSoft else HermesColors.Text,
                modifier = Modifier.widthIn(max = 520.dp)
                    .background(HermesColors.UserBubble, RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun AssistantText(item: ChatItem.Assistant, onOpenMedia: (String) -> Unit) {
    if (item.text.isBlank() && item.streaming) {
        Text("…", color = HermesColors.Muted)
        return
    }
    val parts = remember(item.text) { splitMedia(item.text) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        parts.forEach { part ->
            when (part) {
                is TextPart.Prose -> MarkdownText(part.text)
                is TextPart.Media -> MediaChip(part.path, onOpen = { onOpenMedia(part.path) })
            }
        }
    }
}

@Composable
private fun MarkdownText(text: String) {
    SelectionContainer {
        Markdown(
            content = text,
            colors = markdownColor(text = HermesColors.Text, codeBackground = HermesColors.Terminal),
            typography = markdownTypography(
                text = TextStyle(fontSize = 14.sp, lineHeight = 24.sp),
                paragraph = TextStyle(fontSize = 14.sp, lineHeight = 24.sp),
                code = TextStyle(fontFamily = MonoFamily, fontSize = 12.sp, lineHeight = 19.sp),
                inlineCode = TextStyle(fontFamily = MonoFamily, fontSize = 12.5.sp),
                h1 = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.Bold),
                h2 = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Bold),
                h3 = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold),
                bullet = TextStyle(fontSize = 14.sp, lineHeight = 24.sp),
                list = TextStyle(fontSize = 14.sp, lineHeight = 24.sp),
                ordered = TextStyle(fontSize = 14.sp, lineHeight = 24.sp),
            ),
            components = autoWidthTables,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Tables sized by their content (the renderer's own gives every column the same width and cuts cells to one line). */
private val autoWidthTables = markdownComponents(table = { model ->
    AutoWidthTable(model.content, model.node, model.typography.table)
})

@Composable
private fun ClarifyCard(prompt: PendingPrompt, onAnswer: (PendingPrompt, String, String) -> Unit) {
    val (questions, answered) = when (prompt) {
        is PendingPrompt.Clarify -> listOf(prompt.question) to emptyMap()
        is PendingPrompt.ClarifyBatch -> prompt.questions to prompt.answered
        is PendingPrompt.Approval -> return
    }
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier.fillMaxWidth().background(HermesColors.AccentSurface, shape).border(1.dp, HermesColors.AccentLine, shape).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Hermes が確認しています", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = HermesColors.Accent)
        // A batch is answered in order, one question at a time, like Desktop's card.
        val active = questions.firstOrNull { it.qid !in answered }
        questions.forEachIndexed { index, q ->
            if (questions.size > 1) Text("質問 ${index + 1} / ${questions.size}", fontSize = 11.sp, color = HermesColors.Muted)
            Text(q.question, fontSize = 14.sp, lineHeight = 22.sp)
            val sent = answered[q.qid]
            when {
                sent != null -> Text("回答済み：" + describeAnswer(sent), fontSize = 12.sp, color = HermesColors.Muted)
                q == active -> ClarifyQuestionForm(prompt.promptId, q) { onAnswer(prompt, it, q.qid) }
            }
        }
    }
}

/** Choices to tap (several, then send, when multi-select) or a free-text answer. */
@Composable
private fun ClarifyQuestionForm(promptId: String, q: ClarifyQuestion, onSubmit: (String) -> Unit) {
    var custom by rememberSaveable(promptId, q.qid) { mutableStateOf("") }
    var picked by rememberSaveable(promptId, q.qid) { mutableStateOf(listOf<String>()) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (q.multiSelect) Text("複数選べます", fontSize = 11.sp, color = HermesColors.Muted)
        q.choices.forEach { choice ->
            val selected = choice in picked
            OutlinedButton(
                onClick = {
                    if (q.multiSelect) picked = if (selected) picked - choice else picked + choice else onSubmit(choice)
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = if (selected) HermesColors.SurfaceHigh else HermesColors.Surface,
                    contentColor = HermesColors.Text,
                ),
                border = BorderStroke(1.dp, if (selected) HermesColors.Accent else HermesColors.Line),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
            ) {
                if (q.multiSelect) {
                    Icon(if (selected) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank, null,
                        tint = if (selected) HermesColors.Accent else HermesColors.Muted, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.size(10.dp))
                }
                ChoiceLabel(choice, Modifier.weight(1f))
            }
        }
        if (q.multiSelect && q.choices.isNotEmpty()) {
            Button(
                onClick = { onSubmit(JsonArray(picked.map(::JsonPrimitive)).toString()) },
                enabled = picked.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) { Text("選んだ ${picked.size} 件で回答") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                custom, { custom = it },
                placeholder = { Text(if (q.choices.isEmpty()) "回答を入力" else "ほかの回答を入力") },
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { onSubmit(custom.trim()) }, enabled = custom.isNotBlank()) { Icon(Icons.Default.ArrowUpward, "回答") }
        }
    }
}

/** Hermes marks its first choice "(Recommended)"; show that as a badge instead of English text. */
@Composable
private fun ChoiceLabel(choice: String, modifier: Modifier) {
    val recommended = choice.trimEnd().endsWith(RECOMMENDED, ignoreCase = true)
    val label = if (recommended) choice.trimEnd().dropLast(RECOMMENDED.length).trimEnd() else choice
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 14.sp, modifier = Modifier.weight(1f, fill = false))
        if (recommended) {
            Spacer(Modifier.size(8.dp))
            Badge("おすすめ", HermesColors.Accent, HermesColors.OnAccent)
        }
    }
}

private const val RECOMMENDED = "(Recommended)"

/** A multi-select answer is a JSON array of labels; show it as a plain list. */
private fun describeAnswer(raw: String): String =
    runCatching { Json.parseToJsonElement(raw).jsonArray.joinToString("、") { it.jsonPrimitive.content } }.getOrNull() ?: raw

@Composable
private fun Composer(
    running: Boolean, model: String, onPickModel: () -> Unit, reasoning: String, onReasoning: (String?) -> Unit,
    onSend: (String) -> Unit, onStop: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    // The pane transition hands focus to the first focusable it finds, which would raise the
    // keyboard on every open. The field only accepts focus once the user has touched it.
    var touched by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().background(HermesColors.Rail).navigationBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (running) {
            Text("Hermes が作業中です。送った指示は順番に処理されます", fontSize = 12.sp, color = HermesColors.Muted,
                modifier = Modifier.widthIn(max = READING_WIDTH).fillMaxWidth().padding(start = 4.dp, bottom = 8.dp))
        }
        // Which model answers, one tap from changing it: with a bot per client the right model differs per bot.
        if (model.isNotBlank()) Row(Modifier.widthIn(max = READING_WIDTH).fillMaxWidth().padding(start = 2.dp, bottom = 6.dp)) {
            Row(
Modifier.weight(1f, fill = false).clip(RoundedCornerShape(10.dp)).clickable(onClickLabel = "モデルを選ぶ", onClick = onPickModel).padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(model, fontSize = 12.sp, color = HermesColors.TextSoft, fontFamily = MonoFamily, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Icon(Icons.Default.ArrowDropDown, null, tint = HermesColors.Muted, modifier = Modifier.size(18.dp))
            }
            ReasoningPicker(reasoning, onReasoning)
        }
        Row(Modifier.widthIn(max = READING_WIDTH), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, placeholder = { Text("指示を入力…") },
                modifier = Modifier.weight(1f)
                    .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(pass = PointerEventPass.Initial); touched = true } }
                    .focusProperties { canFocus = touched },
                maxLines = 6, shape = RoundedCornerShape(24.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = HermesColors.Surface, unfocusedContainerColor = HermesColors.Surface,
                    focusedIndicatorColor = HermesColors.Line, unfocusedIndicatorColor = HermesColors.Line,
                ),
            )
            if (running && text.isBlank()) {
                FilledIconButton(onClick = onStop, modifier = Modifier.size(52.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = HermesColors.SurfaceHigh, contentColor = HermesColors.Text)) {
                    Icon(Icons.Default.Stop, "止める")
                }
            } else {
                FilledIconButton(onClick = { onSend(text); text = "" }, enabled = text.isNotBlank(), modifier = Modifier.size(52.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = HermesColors.Accent, contentColor = HermesColors.OnAccent)) {
                    Icon(Icons.Default.ArrowUpward, "送信")
                }
            }
        }
    }
}

/** Hermes's levels, lightest first; `none` switches thinking off where the model allows it. */
private val REASONING_LEVELS = listOf(
    "none" to "なし", "minimal" to "最小", "low" to "低", "medium" to "中", "high" to "高", "xhigh" to "かなり高", "max" to "最大", "ultra" to "ウルトラ",
)

/** How hard the model thinks, next to which model it is: the two are usually chosen together. */
@Composable
private fun ReasoningPicker(current: String, onPick: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClickLabel = "思考の深さを選ぶ") { open = true }.padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Blank: the bot has no level of its own set, so the model's default applies.
            Text("思考 " + (REASONING_LEVELS.firstOrNull { it.first == current }?.second ?: current.ifBlank { "既定" }), fontSize = 12.sp, color = HermesColors.TextSoft, maxLines = 1)
            Icon(Icons.Default.ArrowDropDown, null, tint = HermesColors.Muted, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = HermesColors.SurfaceHigh) {
            REASONING_LEVELS.forEach { (level, label) ->
                DropdownMenuItem(
                    text = { Text("$label  ", fontSize = 14.sp) },
                    leadingIcon = { Text(level, fontSize = 12.sp, color = HermesColors.Muted, fontFamily = MonoFamily) },
                    trailingIcon = { if (level == current) Icon(Icons.Default.Check, "使用中", tint = HermesColors.Accent, modifier = Modifier.size(18.dp)) },
                    onClick = { open = false; if (level != current) onPick(level) },
                )
            }
            HorizontalDivider(color = HermesColors.Line)
            DropdownMenuItem(text = { Text("Bot の設定に戻す", fontSize = 14.sp) }, onClick = { open = false; onPick(null) })
        }
    }
}

private val choiceLabels = mapOf("once" to "今回だけ許可", "session" to "この会話では許可", "always" to "常に許可", "deny" to "拒否する")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ApprovalSheet(prompt: PendingPrompt.Approval, sessionTitle: String, onChoose: (String) -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var dismissed by remember(prompt.promptId) { mutableStateOf(false) }
    if (dismissed) return
    ModalBottomSheet(onDismissRequest = { dismissed = true }, sheetState = sheet, containerColor = HermesColors.Surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Badge("承認待ち", HermesColors.Accent, HermesColors.OnAccent)
                Spacer(Modifier.size(8.dp))
                Text(sessionTitle, fontSize = 12.sp, color = HermesColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text("コマンドの実行を許可しますか？", fontSize = 21.sp, fontWeight = FontWeight.Bold)
            if (prompt.description.isNotBlank()) Text(prompt.description, fontSize = 14.sp, lineHeight = 22.sp, color = HermesColors.Muted)
            SelectionContainer {
                Text(prompt.command, fontFamily = MonoFamily, fontSize = 12.5.sp, lineHeight = 21.sp,
                    modifier = Modifier.fillMaxWidth().background(HermesColors.Terminal, RoundedCornerShape(14.dp))
                        .border(1.dp, HermesColors.Line, RoundedCornerShape(14.dp)).padding(14.dp))
            }
            Text("Desktop にも同じ承認が出ています", fontSize = 12.sp, color = HermesColors.Muted)
            val primary = prompt.choices.firstOrNull { it == "once" }
            primary?.let {
                Button(onClick = { onChoose(it) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = HermesColors.Accent, contentColor = HermesColors.OnAccent)) {
                    Text(choiceLabels.getValue(it), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                prompt.choices.filter { it != primary }.forEach { choice ->
                    OutlinedButton(onClick = { onChoose(choice) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = RoundedCornerShape(14.dp)) {
                        Text(choiceLabels[choice] ?: choice, color = if (choice == "deny") HermesColors.Danger else HermesColors.Text, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
