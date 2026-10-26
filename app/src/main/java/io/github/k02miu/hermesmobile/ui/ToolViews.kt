package io.github.k02miu.hermesmobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.k02miu.hermesmobile.ui.theme.HermesColors
import io.github.k02miu.hermesmobile.ui.theme.MonoFamily
import kotlinx.coroutines.delay

/** What the transcript shows per row: a run of consecutive tool calls folds into one [Tools] row. */
sealed interface TranscriptRow {
    val key: String

    data class Single(val item: ChatItem) : TranscriptRow { override val key get() = item.key }
    data class Tools(val tools: List<ChatItem.Tool>) : TranscriptRow { override val key get() = "tools-" + tools.first().key }
}

fun groupTools(items: List<ChatItem>): List<TranscriptRow> {
    val rows = mutableListOf<TranscriptRow>()
    var run = mutableListOf<ChatItem.Tool>()
    fun flush() {
        when (run.size) {
            0 -> {}
            1 -> rows += TranscriptRow.Single(run.single())
            else -> rows += TranscriptRow.Tools(run)
        }
        run = mutableListOf()
    }
    for (item in items) {
        if (item is ChatItem.Tool) run += item else { flush(); rows += TranscriptRow.Single(item) }
    }
    flush()
    return rows
}

/** "ツール 12 件 · terminal 8・execute_code 4", folded until tapped; the latest call stays readable while it runs. */
@Composable
fun ToolGroup(row: TranscriptRow.Tools, onOpen: (ChatItem.Tool) -> Unit) {
    var expanded by rememberSaveable(row.key) { mutableStateOf(false) }
    val tools = row.tools
    val running = tools.lastOrNull { it.state == ToolState.Running }
    val state = when {
        running != null -> ToolState.Running
        tools.any { it.state == ToolState.Failed } -> ToolState.Failed
        else -> ToolState.Done
    }
    val counts = tools.groupingBy { it.name }.eachCount().entries.sortedByDescending { it.value }
        .joinToString("・") { (name, n) -> "$name $n" }
    Column(Modifier.fillMaxWidth().background(HermesColors.Surface, RoundedCornerShape(12.dp))) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StateIcon(state)
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                Text("ツール ${tools.size} 件", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = HermesColors.TextSoft)
                Text(running?.let { "${it.name}  ${toolSummary(it.name, it.args)}" } ?: counts,
                    fontFamily = MonoFamily, fontSize = 11.sp, color = HermesColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            running?.startedAt?.let { Spacer(Modifier.size(8.dp)); Elapsed(it) }
            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, if (expanded) "畳む" else "開く", tint = HermesColors.Muted)
        }
        if (expanded) {
            Column(Modifier.padding(start = 6.dp, end = 6.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                tools.forEach { ToolRow(it, onOpen, inGroup = true) }
            }
        }
    }
}

@Composable
fun ToolRow(item: ChatItem.Tool, onOpen: (ChatItem.Tool) -> Unit, inGroup: Boolean = false) {
    val summary = remember(item.args) { toolSummary(item.name, item.args) }
    Row(
        Modifier.fillMaxWidth().background(if (inGroup) HermesColors.Ground else HermesColors.Surface, RoundedCornerShape(10.dp))
            .clickable { onOpen(item) }.padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StateIcon(item.state)
        Spacer(Modifier.size(10.dp))
        Text(item.name, fontFamily = MonoFamily, fontSize = 12.sp, color = HermesColors.TextSoft)
        Spacer(Modifier.size(8.dp))
        Text(summary, fontFamily = MonoFamily, fontSize = 11.sp, color = HermesColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f))
        if (item.state == ToolState.Running) item.startedAt?.let {
            Spacer(Modifier.size(8.dp))
            Elapsed(it)
        }
    }
}

/** "2 分" since [startedAt], ticking while on screen: how long Hermes has been waiting on this call. */
@Composable
private fun Elapsed(startedAt: Double) {
    val now by produceState(System.currentTimeMillis() / 1000.0, startedAt) {
        while (true) { delay(1_000); value = System.currentTimeMillis() / 1000.0 }
    }
    val seconds = (now - startedAt).coerceAtLeast(0.0)
    Text(if (seconds < 60) "${seconds.toInt()} 秒" else describeSeconds(seconds), fontSize = 11.sp, color = HermesColors.Working)
}

@Composable
private fun StateIcon(state: ToolState) = when (state) {
    ToolState.Running -> CircularProgressIndicator(Modifier.size(14.dp), color = HermesColors.Working, strokeWidth = 2.dp)
    ToolState.Done -> Icon(Icons.Default.Check, "完了", tint = HermesColors.Done, modifier = Modifier.size(16.dp))
    ToolState.Failed -> Icon(Icons.Default.Close, "失敗", tint = HermesColors.Danger, modifier = Modifier.size(16.dp))
}

/** One call in full: what it ran, what came back, how long it took. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolSheet(item: ChatItem.Tool, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val (inputLabel, input) = remember(item.args) { toolInput(item.args) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = HermesColors.Surface) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StateIcon(item.state)
                Spacer(Modifier.size(10.dp))
                Text(item.name, fontFamily = MonoFamily, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                val status = when (item.state) { ToolState.Running -> "実行中"; ToolState.Done -> "完了"; ToolState.Failed -> "失敗" }
                Text(listOfNotNull(status, item.durationS?.let(::describeSeconds)).joinToString(" · "), fontSize = 12.sp, color = HermesColors.Muted)
            }
            if (input.isNotBlank()) CodeBlock(inputLabel, input, wrap = true)
            item.diff?.let { CodeBlock("差分", it) }
            when {
                item.output != null -> CodeBlock("出力", item.output, limit = OUTPUT_PREVIEW)
                item.state == ToolState.Running -> Text("実行中です。終わると出力がここに出ます", fontSize = 13.sp, color = HermesColors.Muted)
                else -> Text("出力は記録されていません", fontSize = 13.sp, color = HermesColors.Muted)
            }
        }
    }
}

private const val OUTPUT_PREVIEW = 6_000

/** Monospace text in a terminal-coloured box; long text shows its start until "全部見る". */
@Composable
private fun CodeBlock(label: String, text: String, limit: Int = Int.MAX_VALUE, tail: Boolean = false, wrap: Boolean = false) {
    var full by remember(text) { mutableStateOf(false) }
    val cut = !full && text.length > limit
    val shown = if (!cut) text else if (tail) "…\n" + text.takeLast(limit) else text.take(limit) + "\n…"
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = HermesColors.TextSoft)
        SelectionContainer {
            Text(shown, fontFamily = MonoFamily, fontSize = 12.sp, lineHeight = 19.sp,
                modifier = Modifier.fillMaxWidth().background(HermesColors.Terminal, RoundedCornerShape(12.dp))
                    .border(1.dp, HermesColors.Line, RoundedCornerShape(12.dp))
                    .then(if (wrap) Modifier else Modifier.horizontalScroll(rememberScrollState())).padding(12.dp))
        }
        if (cut) TextButton(onClick = { full = true }) { Text("全部見る（${"%,d".format(text.length)} 文字）") }
    }
}

/** Above the composer while background processes run; says so when one of them will wake Hermes. */
@Composable
fun ProcessStrip(processes: List<BackgroundProcess>, onOpen: () -> Unit) {
    val live = processes.filter { it.running }
    if (live.isEmpty()) return
    val waker = live.firstOrNull { it.notifyOnComplete || it.watchPatterns.isNotEmpty() }
    val shown = waker ?: live.first()
    Row(
        Modifier.fillMaxWidth().background(HermesColors.AccentSurface).clickable(onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.HourglassTop, null, tint = HermesColors.Accent, modifier = Modifier.size(16.dp))
        Spacer(Modifier.size(8.dp))
        Text(if (waker != null) "終わるのを待っています" else "バックグラウンド ${live.size} 件", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.size(8.dp))
        Text(shown.command, fontFamily = MonoFamily, fontSize = 11.sp, color = HermesColors.Muted, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        shown.startedAt?.let { Spacer(Modifier.size(8.dp)); Elapsed(it) }
        if (live.size > 1) {
            Spacer(Modifier.size(8.dp))
            Text("ほか ${live.size - 1} 件", fontSize = 11.sp, color = HermesColors.Muted)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProcessSheet(processes: List<BackgroundProcess>, onKill: (BackgroundProcess) -> Unit, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var confirm by remember { mutableStateOf<BackgroundProcess?>(null) }
    val (live, finished) = processes.partition { it.running }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = HermesColors.Surface) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("バックグラウンドの処理", fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Text("Hermes が裏で動かしている処理です。「知らせる」と付いた処理が終わると（または指定の出力が出ると）、Hermes が作業を再開します。",
                fontSize = 13.sp, lineHeight = 20.sp, color = HermesColors.Muted)
            if (live.isEmpty()) Text("いま動いている処理はありません", fontSize = 13.sp, color = HermesColors.Muted)
            live.forEach { p ->
                Column(
                    Modifier.fillMaxWidth().border(1.dp, HermesColors.Line, RoundedCornerShape(14.dp)).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Badge("実行中", HermesColors.WorkingSurface, HermesColors.Working)
                        Spacer(Modifier.size(8.dp))
                        p.startedAt?.let { Elapsed(it) }
                        Spacer(Modifier.weight(1f))
                        OutlinedButton(onClick = { confirm = p }, modifier = Modifier.heightIn(min = 36.dp)) {
                            Text("止める", color = HermesColors.Danger, fontSize = 13.sp)
                        }
                    }
                    val wakes = buildList {
                        if (p.notifyOnComplete) add("終わったら Hermes に知らせる")
                        if (p.watchPatterns.isNotEmpty()) add("「" + p.watchPatterns.joinToString("」「") + "」が出たら知らせる")
                    }
                    if (wakes.isNotEmpty()) Text(wakes.joinToString("・"), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = HermesColors.Accent)
                    CodeBlock("コマンド", p.command, wrap = true)
                    if (p.output.isNotBlank()) CodeBlock("出力（末尾）", p.output.trimEnd(), limit = 2_000, tail = true)
                }
            }
            if (finished.isNotEmpty()) {
                Text("最近終わった処理", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = HermesColors.TextSoft)
                finished.take(FINISHED_SHOWN).forEach { p ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (p.exitCode == null || p.exitCode == 0) Icon(Icons.Default.Check, "成功", tint = HermesColors.Done, modifier = Modifier.size(16.dp))
                        else Icon(Icons.Default.Close, "失敗", tint = HermesColors.Danger, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(8.dp))
                        Text(p.command, fontFamily = MonoFamily, fontSize = 11.sp, color = HermesColors.Muted, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        p.exitCode?.takeIf { it != 0 }?.let { Text("exit $it", fontSize = 11.sp, color = HermesColors.Danger) }
                    }
                }
                if (finished.size > FINISHED_SHOWN) Text("ほか ${finished.size - FINISHED_SHOWN} 件", fontSize = 12.sp, color = HermesColors.Muted)
            }
        }
    }
    confirm?.let { p ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("この処理を止めますか？") },
            text = { Text(p.command, fontFamily = MonoFamily, fontSize = 12.sp, maxLines = 4, overflow = TextOverflow.Ellipsis) },
            confirmButton = { TextButton(onClick = { onKill(p); confirm = null }) { Text("止める", color = HermesColors.Danger) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("やめる") } },
        )
    }
}

private const val FINISHED_SHOWN = 5
