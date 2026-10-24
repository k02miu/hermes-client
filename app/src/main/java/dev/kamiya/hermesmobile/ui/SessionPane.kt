package dev.kamiya.hermesmobile.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import dev.kamiya.hermesmobile.ui.theme.HermesColors
import dev.kamiya.hermesmobile.ui.theme.MonoFamily
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

@Composable
fun SessionPane(
    vm: SessionViewModel, profileLabel: String, avatar: ImageBitmap?, onBack: (() -> Unit)?, modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var preview by rememberSaveable { mutableStateOf<String?>(null) }
    Box(modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().background(HermesColors.Ground).imePadding()) {
        Header(state, profileLabel, avatar, onBack)
        Box(Modifier.weight(1f)) {
            Transcript(state, onAnswer = vm::answer, onOpenMedia = { preview = it })
            if (state.loading) CircularProgressIndicator(Modifier.align(Alignment.Center), color = HermesColors.Working)
            else if (state.items.isEmpty() && state.prompts.isEmpty()) {
                Text("$profileLabel に話しかけて始めましょう", color = HermesColors.Muted, fontSize = 14.sp,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp))
            }
        }
        state.error?.let { Text(it, color = HermesColors.Danger, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
        Composer(running = state.running, onSend = vm::send, onStop = vm::interrupt)
    }
    state.prompts.filterIsInstance<PendingPrompt.Approval>().firstOrNull()?.let { approval ->
        ApprovalSheet(approval, state.title, onChoose = { vm.answer(approval, it) })
    }
    // Over the chat rather than in a dialog: a WebView in a dialog window draws over the dialog's own top bar.
    preview?.let { MediaPreview(it, load = vm::readFile, onClose = { preview = null }) }
    }
}

@Composable
private fun Header(state: SessionState, profileLabel: String, avatar: ImageBitmap?, onBack: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().padding(start = if (onBack != null) 4.dp else 20.dp, end = 16.dp, top = 12.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "戻る") }
        BotAvatar(32, avatar)
        Spacer(Modifier.size(10.dp))
        Column(Modifier.weight(1f)) {
            Text(state.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(profileLabel, fontSize = 11.sp, color = HermesColors.Muted, fontFamily = MonoFamily)
        }
        if (state.running) Badge("作業中", HermesColors.WorkingSurface, HermesColors.Working)
    }
    Box(Modifier.fillMaxWidth().heightIn(min = 1.dp).background(HermesColors.Line))
}

@Composable
private fun Transcript(state: SessionState, onAnswer: (PendingPrompt, String, String) -> Unit, onOpenMedia: (String) -> Unit) {
    // Laid out bottom-up (newest first, reversed) so a session always opens on its latest message,
    // however late the history arrives. New rows follow along only while the reader is at the bottom.
    val list = rememberLazyListState()
    val clarifies = state.prompts.filterNot { it is PendingPrompt.Approval }
    val newestKey = clarifies.lastOrNull()?.promptId ?: state.items.lastOrNull()?.key
    LaunchedEffect(newestKey) {
        if (list.firstVisibleItemIndex <= 1) list.scrollToItem(0)
    }
    LazyColumn(
        state = list, modifier = Modifier.fillMaxSize(), reverseLayout = true,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Bottom),
    ) {
        items(clarifies.asReversed(), key = { "clarify-" + it.promptId }) { ClarifyCard(it, onAnswer) }
        items(state.items.asReversed(), key = { it.key }) { item ->
            when (item) {
                is ChatItem.User -> UserBubble(item)
                is ChatItem.Assistant -> AssistantText(item, onOpenMedia)
                is ChatItem.Tool -> ToolRow(item)
                is ChatItem.Notice -> Text(item.text, fontSize = 12.sp, color = HermesColors.Danger)
            }
        }
    }
}

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
            modifier = Modifier.widthIn(max = 640.dp),
        )
    }
}

/** Tables sized by their content (the renderer's own gives every column the same width and cuts cells to one line). */
private val autoWidthTables = markdownComponents(table = { model ->
    AutoWidthTable(model.content, model.node, model.typography.table)
})

@Composable
private fun ToolRow(item: ChatItem.Tool) {
    Row(
        Modifier.fillMaxWidth().background(HermesColors.Surface, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (item.state) {
            ToolState.Running -> CircularProgressIndicator(Modifier.size(14.dp), color = HermesColors.Working, strokeWidth = 2.dp)
            ToolState.Done -> Icon(Icons.Default.Check, null, tint = HermesColors.Done, modifier = Modifier.size(16.dp))
            ToolState.Failed -> Icon(Icons.Default.Close, null, tint = HermesColors.Danger, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.size(10.dp))
        Text(item.name, fontFamily = MonoFamily, fontSize = 12.sp, color = HermesColors.TextSoft)
        if (item.detail.isNotBlank()) {
            Spacer(Modifier.size(8.dp))
            Text(item.detail, fontFamily = MonoFamily, fontSize = 11.sp, color = HermesColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

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
private fun Composer(running: Boolean, onSend: (String) -> Unit, onStop: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    // The pane transition hands focus to the first focusable it finds, which would raise the
    // keyboard on every open. The field only accepts focus once the user has touched it.
    var touched by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(HermesColors.Rail).navigationBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp)) {
        if (running) {
            Text("Hermes が作業中です。送った指示は順番に処理されます", fontSize = 12.sp, color = HermesColors.Muted,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
