package io.github.k02miu.hermesmobile.ui

import android.os.Parcelable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.VerticalSplit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.VerticalDivider
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirectiveWithTwoPanesOnMediumWidth
import androidx.compose.ui.unit.dp
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.PaneAdaptedValue
import androidx.compose.material3.adaptive.navigation.NavigableListDetailPaneScaffold
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.k02miu.hermesmobile.HermesApplication
import io.github.k02miu.hermesmobile.ui.theme.HermesColors
import kotlinx.coroutines.launch
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize

@Composable
fun HermesApp(vm: AppViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val adding by vm.adding.collectAsStateWithLifecycle()
    when {
        !state.loaded -> Box(Modifier.fillMaxSize().background(HermesColors.Ground), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = HermesColors.Working)
        }
        state.servers.isEmpty() -> ConnectScreen(adding ?: AddServerState(), onLogin = vm::addServer)
        else -> {
            MainScreen(vm, state)
            adding?.let {
                BackHandler(onBack = vm::cancelAdding)
                ConnectScreen(it, onLogin = vm::addServer, onCancel = vm::cancelAdding)
            }
        }
    }
}

/** What a pane shows: one of a bot's conversations, or (with [screen]) its Bot Desktop. */
@Parcelize
private data class PaneKey(
    val bot: BotRef, val sessionId: String? = null, val title: String = SessionTarget.BOT_CHAT,
    val newId: String? = null, val screen: Boolean = false,
) : Parcelable {
    fun target() = SessionTarget(bot.profile, sessionId, title, newId)
    @IgnoredOnParcel val id = bot.server + "|" + if (screen) bot.profile + "/screen" else target().key
}

private fun chatKey(bot: BotRef, target: SessionTarget = SessionTarget.botChat(bot.profile)) =
    PaneKey(bot, target.sessionId, target.title, target.newId)

/** The sheets any pane can raise; kept in one place so both layouts offer the same ones. */
private class Sheets {
    var sessionsFor by mutableStateOf<BotRef?>(null)
    var servers by mutableStateOf(false)
}

/**
 * Phones and a Fold: list + detail, one pane on the cover screen and side by side when opened.
 * Tablets: up to three panes side by side, with the list folded away when not needed.
 */
@Composable
private fun MainScreen(vm: AppViewModel, state: AppState) {
    // Back from the background: running badges and last-active times may have moved on meanwhile.
    LifecycleResumeEffect(vm) {
        vm.refresh()
        onPauseOrDispose {}
    }
    // Shared by both layouts so a rotation or a resized window keeps the open panes.
    val columns = rememberSaveable(saver = columnsSaver) { mutableStateListOf<PaneKey?>(null) }
    var focus by rememberSaveable { mutableIntStateOf(0) }
    val sheets = remember { Sheets() }
    // A server that was removed takes its panes with it.
    val urls = state.servers.map { it.url }
    LaunchedEffect(urls) {
        columns.indices.forEach { i -> if (columns[i]?.bot?.server?.let { it !in urls } == true) columns[i] = null }
    }
    val maxColumns = (LocalConfiguration.current.screenWidthDp / MIN_COLUMN_WIDTH).coerceIn(1, 3)
    val open: (PaneKey) -> Unit =
        if (maxColumns == 1) listDetailScreen(vm, state, columns, focus, sheets)
        else columnsScreen(vm, state, columns, focus, { focus = it }, maxColumns, sheets)

    val avatars by vm.avatars.collectAsStateWithLifecycle()
    sheets.sessionsFor?.let { ref ->
        val bot = state.bot(ref)
        if (bot == null) sheets.sessionsFor = null
        else BotSessionsSheet(
            bot, avatars[ref.key],
            selectedKey = columns.getOrNull(focus)?.takeIf { it.bot == ref && !it.screen }?.target()?.key,
            onOpen = { target -> sheets.sessionsFor = null; open(chatKey(ref, target)) },
            onPin = { session, pinned -> vm.setPinned(ref, session, pinned) },
            onDismiss = { sheets.sessionsFor = null },
        )
    }
    if (sheets.servers) ServersSheet(
        state, onAdd = { sheets.servers = false; vm.startAdding() },
        onRemove = vm::removeServer, onRetry = vm::retry, onDismiss = { sheets.servers = false },
    )
}

/** A pane narrower than this is not worth splitting for: two fit an unfolded Fold held upright (~750dp), three a tablet. */
private const val MIN_COLUMN_WIDTH = 360

private val columnsSaver = listSaver<SnapshotStateList<PaneKey?>, PaneKey?>(
    save = { it.toList() },
    restore = { it.toMutableStateList() },
)

@Composable
private fun BotList(
    vm: AppViewModel, state: AppState, selected: PaneKey?, sheets: Sheets, open: (PaneKey) -> Unit,
    modifier: Modifier = Modifier, onHide: (() -> Unit)? = null,
) {
    val avatars by vm.avatars.collectAsStateWithLifecycle()
    val themeMode by vm.themeMode.collectAsStateWithLifecycle()
    BotListPane(
        state = state, avatars = avatars, selected = selected?.bot,
        onOpenChat = { open(chatKey(it)) },
        onOpenSessions = { sheets.sessionsFor = it },
        onNewChat = { open(chatKey(it, SessionTarget.newChat(it.profile))) },
        onOpenScreen = { open(PaneKey(it, screen = true)) },
        onRefresh = vm::refresh, onServers = { sheets.servers = true }, onRetry = vm::retry,
        onDismissNotice = vm::clearNotice,
        themeMode = themeMode, onThemeMode = vm::setThemeMode,
        modifier = modifier, onHide = onHide,
    )
}

/** Returns how to open a pane in this layout, for the sheets raised from [MainScreen]. */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
private fun listDetailScreen(vm: AppViewModel, state: AppState, columns: SnapshotStateList<PaneKey?>, focus: Int, sheets: Sheets): (PaneKey) -> Unit {
    // A Fold's inner screen is ~750dp wide ("medium"); the default directive only splits from 840dp.
    val navigator = rememberListDetailPaneScaffoldNavigator<PaneKey>(
        scaffoldDirective = calculatePaneScaffoldDirectiveWithTwoPanesOnMediumWidth(currentWindowAdaptiveInfo()),
    )
    val scope = rememberCoroutineScope()
    val current = navigator.currentDestination?.contentKey
    val singlePane = navigator.scaffoldValue[ListDetailPaneScaffoldRole.List] == PaneAdaptedValue.Hidden ||
        navigator.scaffoldValue[ListDetailPaneScaffoldRole.Detail] == PaneAdaptedValue.Hidden
    val open: (PaneKey) -> Unit = { key -> scope.launch { navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, key) } }
    // Coming from the tablet layout (a window made narrower): carry on with the pane in focus.
    LaunchedEffect(Unit) {
        val carried = columns.getOrNull(focus)
        if (current == null && carried != null) navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, carried)
    }
    LaunchedEffect(current) {
        if (current != null) columns[focus.coerceIn(0, columns.lastIndex)] = current
    }
    // Its server was removed: nothing left to show.
    LaunchedEffect(current, state.servers.size) {
        if (current != null && state.servers.none { it.url == current.bot.server }) navigator.navigateBack()
    }

    NavigableListDetailPaneScaffold(
        navigator = navigator,
        modifier = Modifier.background(HermesColors.Ground).safeDrawingPadding(),
        listPane = {
            AnimatedPane(Modifier.preferredWidth(300.dp)) { BotList(vm, state, current, sheets, open) }
        },
        detailPane = {
            AnimatedPane {
                val key = current
                if (key == null) Placeholder("左の一覧から Bot を選んでください")
                else PaneColumn(vm, state, key, sheets, open, onBack = if (singlePane) ({ scope.launch { navigator.navigateBack() } }) else null)
            }
        },
    )
    return open
}

/**
 * Up to [maxColumns] panes side by side. Picking from the list fills the column in focus (the one last
 * touched); an empty column offers the list itself, so the side list can stay folded.
 */
@Composable
private fun columnsScreen(
    vm: AppViewModel, state: AppState, columns: SnapshotStateList<PaneKey?>,
    focus: Int, onFocus: (Int) -> Unit, maxColumns: Int, sheets: Sheets,
): (PaneKey) -> Unit {
    var showList by rememberSaveable { mutableStateOf(true) }
    // Fewer columns fit after a rotation: drop the ones furthest from the focus.
    LaunchedEffect(maxColumns, columns.size) {
        while (columns.size > maxColumns) columns.removeAt(if (focus == columns.lastIndex) 0 else columns.lastIndex)
        if (focus !in columns.indices) onFocus(columns.lastIndex)
    }
    val focused = focus.coerceIn(0, columns.lastIndex)
    val listWidth = if (columns.size >= 3) 280 else 320
    // On an unfolded Fold the list and two panes do not fit side by side. The list then gives way: it folds when a
    // second pane opens and, called back, slides over the panes until a bot is picked.
    val listFits = LocalConfiguration.current.screenWidthDp - listWidth >= columns.size * MIN_COLUMN_WIDTH
    var hadRoom by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(listFits) {
        if (hadRoom && !listFits) showList = false
        hadRoom = listFits
    }

    fun open(index: Int, key: PaneKey) {
        // Already open elsewhere: go there rather than showing one thing twice.
        val existing = columns.indexOfFirst { it?.id == key.id }
        if (existing >= 0 && existing != index) onFocus(existing)
        else { columns[index] = key; onFocus(index) }
    }

    /** A bot's screen opens next to its chat when there is room, so both can be seen at once. */
    fun openBeside(index: Int, key: PaneKey) {
        val existing = columns.indexOfFirst { it?.id == key.id }
        when {
            existing >= 0 -> onFocus(existing)
            columns.getOrNull(index + 1) == null && index + 1 < columns.size -> open(index + 1, key)
            columns.size < maxColumns -> { columns.add(index + 1, key); onFocus(index + 1) }
            else -> open(index, key)
        }
    }

    Box(Modifier.fillMaxSize().background(HermesColors.Ground).safeDrawingPadding()) {
    Row(Modifier.fillMaxSize()) {
        if (showList && listFits) {
            BotList(vm, state, columns[focused], sheets, { open(focused, it) }, Modifier.width(listWidth.dp), onHide = { showList = false })
            VerticalDivider(color = HermesColors.Line)
        }
        columns.forEachIndexed { index, key ->
            if (index > 0) VerticalDivider(color = HermesColors.Line)
            Box(
                Modifier.weight(1f).fillMaxHeight().pointerInput(index) {
                    // Watch touches without taking them, so the column under the finger becomes the focus.
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        onFocus(index)
                    }
                },
            ) {
                val leading: (@Composable () -> Unit)? = if (index == 0 && !showList) ({
                    IconButton(onClick = { showList = true }) { Icon(Icons.Default.Menu, "一覧を開く", tint = HermesColors.Muted) }
                }) else null
                val actions: @Composable RowScope.() -> Unit = {
                    if (columns.size < maxColumns) IconButton(onClick = { columns.add(index + 1, null); onFocus(index + 1) }) {
                        Icon(Icons.Default.VerticalSplit, "列を増やす", tint = HermesColors.Muted)
                    }
                    if (columns.size > 1) IconButton(onClick = { columns.removeAt(index); onFocus((index - 1).coerceAtLeast(0)) }) {
                        Icon(Icons.Default.Close, "この列を閉じる", tint = HermesColors.Muted)
                    }
                }
                if (key != null) PaneColumn(vm, state, key, sheets, { if (it.screen) openBeside(index, it) else open(index, it) }, onBack = null, leading = leading, actions = actions)
                else Column(Modifier.fillMaxSize().background(if (showList && listFits) HermesColors.Ground else HermesColors.Rail)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        leading?.invoke()
                        Spacer(Modifier.weight(1f))
                        actions()
                    }
                    // With the side list folded away, an empty column offers the list in its place.
                    if (showList && listFits) Placeholder(if (index == focused) "一覧から Bot を選んでください" else "この列を押してから、一覧で Bot を選んでください")
                    else BotList(vm, state, null, sheets, { open(index, it) }, Modifier.weight(1f))
                }
                if (columns.size > 1 && index == focused) {
                    Box(Modifier.fillMaxWidth().height(2.dp).background(HermesColors.Accent))
                }
            }
        }
    }
    if (showList && !listFits) {
        BackHandler { showList = false }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)).clickable(interactionSource = null, indication = null) { showList = false })
        Row(Modifier.fillMaxHeight()) {
            BotList(vm, state, columns[focused], sheets, { showList = false; open(focused, it) }, Modifier.width(listWidth.dp), onHide = { showList = false })
            VerticalDivider(color = HermesColors.Line)
        }
    }
    }
    return { open(focused, it) }
}

/** One pane: a conversation with shortcuts to the bot's other conversations and its screen, or the screen itself. */
@Composable
private fun PaneColumn(
    vm: AppViewModel, state: AppState, key: PaneKey, sheets: Sheets, open: (PaneKey) -> Unit, onBack: (() -> Unit)?,
    leading: (@Composable () -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {},
) {
    val avatars by vm.avatars.collectAsStateWithLifecycle()
    val app = LocalContext.current.applicationContext as HermesApplication
    val bot = state.bot(key.bot)
    val label = bot?.label ?: key.bot.profile
    val avatar = avatars[key.bot.key]
    if (key.screen) {
        val screenVm: ScreenViewModel = viewModel(key = key.id) { ScreenViewModel(app, key.bot) }
        ScreenPane(screenVm, label, avatar, onBack = onBack, leading = leading, actions = {
            IconButton(onClick = { open(chatKey(key.bot)) }) { Icon(Icons.Default.Forum, "この Bot と話す", tint = HermesColors.Muted) }
            actions()
        })
        return
    }
    val sessionVm: SessionViewModel = viewModel(key = key.id) { SessionViewModel(app, key.bot.server, key.target()) }
    SessionPane(sessionVm, label, avatar, onBack = onBack, leading = leading, actions = {
        IconButton(onClick = { sheets.sessionsFor = key.bot }) { Icon(Icons.Default.Forum, "この Bot の会話", tint = HermesColors.Muted) }
        if (bot?.screen != null) IconButton(onClick = { open(PaneKey(key.bot, screen = true)) }) {
            Icon(Icons.Default.DesktopWindows, "Bot Desktop", tint = if (bot.screen.running) HermesColors.Working else HermesColors.Muted)
        }
        actions()
    })
}

@Composable
private fun Placeholder(text: String) {
    Box(Modifier.fillMaxSize().background(HermesColors.Ground), contentAlignment = Alignment.Center) {
        Text(text, color = HermesColors.Muted, fontSize = 14.sp, modifier = Modifier.padding(24.dp))
    }
}
