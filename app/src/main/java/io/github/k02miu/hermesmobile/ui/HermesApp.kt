package io.github.k02miu.hermesmobile.ui

import android.os.Parcelable
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.VerticalSplit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.k02miu.hermesmobile.HermesApplication
import io.github.k02miu.hermesmobile.ui.theme.HermesColors
import kotlinx.coroutines.launch
import kotlinx.parcelize.Parcelize

@Composable
fun HermesApp(vm: AppViewModel = viewModel()) {
    val auth by vm.auth.collectAsStateWithLifecycle()
    when (val a = auth) {
        AuthState.Loading -> Box(Modifier.fillMaxSize().background(HermesColors.Ground), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = HermesColors.Working)
        }
        is AuthState.NeedsLogin -> ConnectScreen(a, onLogin = vm::login)
        is AuthState.Ready -> MainScreen(vm, a)
    }
}

@Parcelize
private data class PaneKey(val profile: String, val sessionId: String?, val title: String, val newId: String?) : Parcelable {
    fun target() = SessionTarget(profile, sessionId, title, newId)
}

/**
 * Phones and a Fold: list + detail, one pane on the cover screen and side by side when opened.
 * Tablets: up to three conversations side by side, with the list folded away when not needed.
 */
@Composable
private fun MainScreen(vm: AppViewModel, auth: AuthState.Ready) {
    // Back from the background: running badges and last-active times may have moved on meanwhile.
    LifecycleResumeEffect(vm) {
        vm.refresh()
        onPauseOrDispose {}
    }
    // Shared by both layouts so a rotation or a resized window keeps the open conversations.
    val columns = rememberSaveable(saver = columnsSaver) { mutableStateListOf<PaneKey?>(null) }
    var focus by rememberSaveable { mutableIntStateOf(0) }
    val maxColumns = (LocalConfiguration.current.screenWidthDp / MIN_COLUMN_WIDTH).coerceIn(1, 3)
    if (maxColumns == 1) ListDetailScreen(vm, auth, columns, focus)
    else ColumnsScreen(vm, auth, columns, focus, { focus = it }, maxColumns)
}

private const val MIN_COLUMN_WIDTH = 400

private val columnsSaver = listSaver<SnapshotStateList<PaneKey?>, PaneKey?>(
    save = { it.toList() },
    restore = { it.toMutableStateList() },
)

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
private fun ListDetailScreen(vm: AppViewModel, auth: AuthState.Ready, columns: SnapshotStateList<PaneKey?>, focus: Int) {
    val list by vm.list.collectAsStateWithLifecycle()
    val avatars by vm.avatars.collectAsStateWithLifecycle()
    val themeMode by vm.themeMode.collectAsStateWithLifecycle()
    // A Fold's inner screen is ~750dp wide ("medium"); the default directive only splits from 840dp.
    val navigator = rememberListDetailPaneScaffoldNavigator<PaneKey>(
        scaffoldDirective = calculatePaneScaffoldDirectiveWithTwoPanesOnMediumWidth(currentWindowAdaptiveInfo()),
    )
    val scope = rememberCoroutineScope()
    val current = navigator.currentDestination?.contentKey
    val singlePane = navigator.scaffoldValue[ListDetailPaneScaffoldRole.List] == PaneAdaptedValue.Hidden ||
        navigator.scaffoldValue[ListDetailPaneScaffoldRole.Detail] == PaneAdaptedValue.Hidden
    // Coming from the tablet layout (a window made narrower): carry on with the conversation in focus.
    LaunchedEffect(Unit) {
        val carried = columns.getOrNull(focus)
        if (current == null && carried != null) navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, carried)
    }
    LaunchedEffect(current) {
        if (current != null) columns[focus.coerceIn(0, columns.lastIndex)] = current
    }

    NavigableListDetailPaneScaffold(
        navigator = navigator,
        modifier = Modifier.background(HermesColors.Ground).safeDrawingPadding(),
        listPane = {
            AnimatedPane(Modifier.preferredWidth(280.dp)) {
                SessionListPane(
                    host = auth.host, state = list, avatars = avatars, selectedKey = current?.target()?.key,
                    onSelectProfile = vm::selectProfile,
                    onOpen = { t -> scope.launch { navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, t.paneKey()) } },
                    onRefresh = vm::refresh,
                    themeMode = themeMode,
                    onThemeMode = vm::setThemeMode,
                )
            }
        },
        detailPane = {
            AnimatedPane {
                val key = current
                if (key == null) Placeholder("左の一覧からセッションを選んでください")
                else SessionColumn(vm, auth, key, onBack = if (singlePane) ({ scope.launch { navigator.navigateBack() } }) else null)
            }
        },
    )
}

/**
 * Up to [maxColumns] conversations side by side. Picking from the list fills the column in focus
 * (the one last touched); an empty column offers the list itself, so the side list can stay folded.
 */
@Composable
private fun ColumnsScreen(
    vm: AppViewModel, auth: AuthState.Ready, columns: SnapshotStateList<PaneKey?>,
    focus: Int, onFocus: (Int) -> Unit, maxColumns: Int,
) {
    val list by vm.list.collectAsStateWithLifecycle()
    val avatars by vm.avatars.collectAsStateWithLifecycle()
    val themeMode by vm.themeMode.collectAsStateWithLifecycle()
    var showList by rememberSaveable { mutableStateOf(true) }
    // Fewer columns fit after a rotation: drop the ones furthest from the focus.
    LaunchedEffect(maxColumns, columns.size) {
        while (columns.size > maxColumns) columns.removeAt(if (focus == columns.lastIndex) 0 else columns.lastIndex)
        if (focus !in columns.indices) onFocus(columns.lastIndex)
    }
    val focused = focus.coerceIn(0, columns.lastIndex)

    fun open(index: Int, t: SessionTarget) {
        val key = t.paneKey()
        // Already open elsewhere: go there rather than showing one conversation twice.
        val existing = columns.indexOfFirst { it?.target()?.key == t.key }
        if (existing >= 0 && existing != index) onFocus(existing)
        else { columns[index] = key; onFocus(index) }
    }

    @Composable
    fun listPane(index: Int, modifier: Modifier, onHide: (() -> Unit)?) = SessionListPane(
        host = auth.host, state = list, avatars = avatars, selectedKey = columns[index]?.target()?.key,
        onSelectProfile = vm::selectProfile,
        onOpen = { open(index, it) },
        onRefresh = vm::refresh,
        themeMode = themeMode,
        onThemeMode = vm::setThemeMode,
        modifier = modifier,
        onHide = onHide,
    )

    Row(Modifier.fillMaxSize().background(HermesColors.Ground).safeDrawingPadding()) {
        if (showList) {
            listPane(focused, Modifier.width(if (columns.size >= 3) 280.dp else 320.dp), onHide = { showList = false })
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
                if (key != null) SessionColumn(vm, auth, key, onBack = null, leading = leading, actions = actions)
                else Column(Modifier.fillMaxSize().background(if (showList) HermesColors.Ground else HermesColors.Rail)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        leading?.invoke()
                        Spacer(Modifier.weight(1f))
                        actions()
                    }
                    // With the side list folded away, an empty column offers the list in its place.
                    if (showList) Placeholder(if (index == focused) "一覧から会話を選んでください" else "この列を押してから、一覧で会話を選んでください")
                    else listPane(index, Modifier.weight(1f), onHide = null)
                }
                if (columns.size > 1 && index == focused) {
                    Box(Modifier.fillMaxWidth().height(2.dp).background(HermesColors.Accent))
                }
            }
        }
    }
}

@Composable
private fun SessionColumn(
    vm: AppViewModel, auth: AuthState.Ready, key: PaneKey, onBack: (() -> Unit)?,
    leading: (@Composable () -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {},
) {
    val list by vm.list.collectAsStateWithLifecycle()
    val avatars by vm.avatars.collectAsStateWithLifecycle()
    val app = LocalContext.current.applicationContext as HermesApplication
    val target = key.target()
    val sessionVm: SessionViewModel = viewModel(key = target.key) { SessionViewModel(app, auth.serverUrl, target) }
    val label = list.profiles.firstOrNull { it.name == target.profile }?.label ?: target.profile
    SessionPane(sessionVm, label, avatars[target.profile], onBack = onBack, leading = leading, actions = actions)
}

@Composable
private fun Placeholder(text: String) {
    Box(Modifier.fillMaxSize().background(HermesColors.Ground), contentAlignment = Alignment.Center) {
        Text(text, color = HermesColors.Muted, fontSize = 14.sp, modifier = Modifier.padding(24.dp))
    }
}

private fun SessionTarget.paneKey() = PaneKey(profile, sessionId, title, newId)

private val AuthState.Ready.host get() = serverUrl.removePrefix("https://").removePrefix("http://").substringBefore('.')
