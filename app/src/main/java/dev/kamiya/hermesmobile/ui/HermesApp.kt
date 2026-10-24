package dev.kamiya.hermesmobile.ui

import android.os.Parcelable
import androidx.compose.foundation.background
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.kamiya.hermesmobile.HermesApplication
import dev.kamiya.hermesmobile.ui.theme.HermesColors
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

/** List + detail: one pane on the cover screen, side by side when a Fold is opened. */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
private fun MainScreen(vm: AppViewModel, auth: AuthState.Ready) {
    val list by vm.list.collectAsStateWithLifecycle()
    val avatars by vm.avatars.collectAsStateWithLifecycle()
    val themeMode by vm.themeMode.collectAsStateWithLifecycle()
    // Back from the background: running badges and last-active times may have moved on meanwhile.
    LifecycleResumeEffect(vm) {
        vm.refresh()
        onPauseOrDispose {}
    }
    // A Fold's inner screen is ~750dp wide ("medium"); the default directive only splits from 840dp.
    val navigator = rememberListDetailPaneScaffoldNavigator<PaneKey>(
        scaffoldDirective = calculatePaneScaffoldDirectiveWithTwoPanesOnMediumWidth(currentWindowAdaptiveInfo()),
    )
    val scope = rememberCoroutineScope()
    val app = LocalContext.current.applicationContext as HermesApplication
    val host = auth.serverUrl.removePrefix("https://").removePrefix("http://").substringBefore('.')
    val current = navigator.currentDestination?.contentKey
    val singlePane = navigator.scaffoldValue[ListDetailPaneScaffoldRole.List] == PaneAdaptedValue.Hidden ||
        navigator.scaffoldValue[ListDetailPaneScaffoldRole.Detail] == PaneAdaptedValue.Hidden

    NavigableListDetailPaneScaffold(
        navigator = navigator,
        modifier = Modifier.background(HermesColors.Ground).safeDrawingPadding(),
        listPane = {
            AnimatedPane(Modifier.preferredWidth(280.dp)) {
                SessionListPane(
                    host = host, state = list, avatars = avatars, selectedKey = current?.target()?.key,
                    onSelectProfile = vm::selectProfile,
                    onOpen = { t -> scope.launch { navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, PaneKey(t.profile, t.sessionId, t.title, t.newId)) } },
                    onRefresh = vm::refresh,
                    themeMode = themeMode,
                    onThemeMode = vm::setThemeMode,
                )
            }
        },
        detailPane = {
            AnimatedPane {
                val key = current
                if (key == null) {
                    Box(Modifier.fillMaxSize().background(HermesColors.Ground), contentAlignment = Alignment.Center) {
                        Text("左の一覧からセッションを選んでください", color = HermesColors.Muted, fontSize = 14.sp)
                    }
                } else {
                    val target = key.target()
                    val sessionVm: SessionViewModel = viewModel(key = target.key) { SessionViewModel(app, auth.serverUrl, target) }
                    val label = list.profiles.firstOrNull { it.name == target.profile }?.label ?: target.profile
                    SessionPane(
                        sessionVm, label, avatars[target.profile],
                        onBack = if (singlePane) ({ scope.launch { navigator.navigateBack() } }) else null,
                    )
                }
            }
        },
    )
}
