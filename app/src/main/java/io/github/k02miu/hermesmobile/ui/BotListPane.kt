package io.github.k02miu.hermesmobile.ui

import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuOpen
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.k02miu.hermesmobile.data.SessionSummary
import io.github.k02miu.hermesmobile.ui.theme.HermesColors
import io.github.k02miu.hermesmobile.ui.theme.MonoFamily
import io.github.k02miu.hermesmobile.ui.theme.ThemeMode

/**
 * Home: every bot of every server, one row each. A bot is the unit of work (one per client or project), so a tap
 * goes straight to its standing chat; its other conversations and its Bot Desktop are one step further.
 */
@Composable
fun BotListPane(
    state: AppState,
    avatars: Map<String, ImageBitmap>,
    selected: BotRef?,
    onOpenChat: (BotRef) -> Unit,
    onOpenSessions: (BotRef) -> Unit,
    onNewChat: (BotRef) -> Unit,
    onOpenScreen: (BotRef) -> Unit,
    onRefresh: () -> Unit,
    onServers: () -> Unit,
    onRetry: (String) -> Unit,
    onDismissNotice: () -> Unit,
    themeMode: ThemeMode,
    onThemeMode: (ThemeMode) -> Unit,
    modifier: Modifier = Modifier,
    onHide: (() -> Unit)? = null,
) {
    Column(modifier.fillMaxSize().background(HermesColors.Rail)) {
        Row(Modifier.fillMaxWidth().padding(start = if (onHide != null) 4.dp else 20.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onHide != null) IconButton(onClick = onHide) { Icon(Icons.AutoMirrored.Filled.MenuOpen, "一覧を閉じる", tint = HermesColors.Muted) }
            Text("Bot", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            ThemeButton(themeMode, onThemeMode)
            IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, "再読み込み", tint = HermesColors.Muted) }
            IconButton(onClick = onServers) { Icon(Icons.Default.Dns, "サーバー", tint = HermesColors.Muted) }
        }
        if (state.refreshing) LinearProgressIndicator(Modifier.fillMaxWidth(), color = HermesColors.Working, trackColor = HermesColors.Rail)
        else Spacer(Modifier.height(4.dp))
        state.notice?.let {
            Text(it, color = HermesColors.Danger, fontSize = 12.sp, modifier = Modifier.clickable(onClick = onDismissNotice).padding(horizontal = 20.dp, vertical = 4.dp))
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 16.dp)) {
            state.servers.forEach { server ->
                item(key = "server-" + server.url) { ServerHeader(server, onRetry = { onRetry(server.url) }) }
                items(server.bots, key = { it.ref.key }) { bot ->
                    BotRow(
                        bot, avatars[bot.ref.key], selected = bot.ref == selected,
                        onClick = { onOpenChat(bot.ref) }, onSessions = { onOpenSessions(bot.ref) },
                        onNewChat = { onNewChat(bot.ref) }, onScreen = { onOpenScreen(bot.ref) },
                    )
                }
                if (server.bots.isEmpty() && server.status == ServerStatus.Ready) item(key = "empty-" + server.url) {
                    Text("Bot がありません", fontSize = 12.sp, color = HermesColors.Muted, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun ServerHeader(server: ServerState, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 14.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(server.status.color(), CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(server.host, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = HermesColors.TextSoft, fontFamily = MonoFamily, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            when (server.status) {
                ServerStatus.Connecting -> Text("接続中…", fontSize = 11.sp, color = HermesColors.Muted, modifier = Modifier.padding(end = 8.dp))
                ServerStatus.Failed -> TextButton(onClick = onRetry) { Text("つなぎ直す", fontSize = 12.sp, color = HermesColors.Accent) }
                ServerStatus.Ready -> Unit
            }
        }
        server.error?.let { Text(it, fontSize = 12.sp, color = HermesColors.Danger, modifier = Modifier.padding(top = 2.dp, end = 8.dp)) }
    }
}

@Composable
private fun ServerStatus.color(): Color = when (this) {
    ServerStatus.Ready -> HermesColors.Done
    ServerStatus.Connecting -> HermesColors.Muted
    ServerStatus.Failed -> HermesColors.Danger
}

/** A long press offers what is one step further: the bot's other conversations, a new one, its Bot Desktop. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BotRow(
    bot: Bot, avatar: ImageBitmap?, selected: Boolean,
    onClick: () -> Unit, onSessions: () -> Unit, onNewChat: () -> Unit, onScreen: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().background(if (selected) HermesColors.SurfaceHigh else HermesColors.Rail, RoundedCornerShape(14.dp))
                .combinedClickable(onClick = onClick, onLongClick = { menu = true }, onLongClickLabel = "この Bot の操作")
                .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                BotAvatar(44, avatar)
                if (bot.running) Box(
                    Modifier.align(Alignment.BottomEnd).size(14.dp).background(HermesColors.Rail, CircleShape).padding(2.dp).background(HermesColors.Working, CircleShape),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(bot.label, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (bot.running) {
                        Spacer(Modifier.width(8.dp))
                        Badge("実行中", HermesColors.WorkingSurface, HermesColors.Working)
                    }
                }
                Text(
                    bot.summary,
                    fontSize = 12.sp, color = HermesColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(relative(bot.lastAt), fontSize = 11.sp, color = HermesColors.Muted, fontFamily = MonoFamily, modifier = Modifier.padding(end = 8.dp))
                // A running screen is worth a shortcut: it usually means the bot is in a browser right now.
                if (bot.screen?.running == true) IconButton(onClick = onScreen, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.DesktopWindows, "Bot Desktop を開く", tint = if (bot.screen.humanHolds) HermesColors.Accent else HermesColors.Working, modifier = Modifier.size(18.dp))
                }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = HermesColors.SurfaceHigh) {
            DropdownMenuItem(text = { Text("会話の一覧") }, leadingIcon = { Icon(Icons.Default.Forum, null) }, onClick = { menu = false; onSessions() })
            DropdownMenuItem(text = { Text("新しい会話") }, leadingIcon = { Icon(Icons.Default.Add, null) }, onClick = { menu = false; onNewChat() })
            if (bot.screen != null) DropdownMenuItem(
                text = { Text(if (bot.screen.running) "Bot Desktop" else "Bot Desktop（停止中）") },
                leadingIcon = { Icon(Icons.Default.DesktopWindows, null) }, onClick = { menu = false; onScreen() },
            )
        }
    }
}

/** One bot's conversations: its standing chat, Desktop's pins, then the rest by recency. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BotSessionsSheet(
    bot: Bot, avatar: ImageBitmap?, selectedKey: String?,
    onOpen: (SessionTarget) -> Unit, onPin: (SessionSummary, Boolean) -> Unit, onDismiss: () -> Unit,
) {
    val profile = bot.ref.profile
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = HermesColors.Rail) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            BotAvatar(36, avatar)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(bot.label, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("会話", fontSize = 11.sp, color = HermesColors.Muted)
            }
            Button(
                onClick = { onOpen(SessionTarget.newChat(profile)) }, shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = HermesColors.Accent, contentColor = HermesColors.OnAccent),
            ) {
                Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("新しい会話", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(12.dp)) {
            item(key = "bot") {
                val target = SessionTarget.botChat(profile)
                BotChatCard(avatar, selected = selectedKey == target.key, onClick = { onOpen(target) })
            }
            val (pinned, rest) = bot.sessions.partition { it.pinned }
            if (pinned.isNotEmpty()) {
                item(key = "pinned-header") { SectionHeader("ピン留め", pinned.size) }
                items(pinned, key = { "pinned-" + it.id }) { s ->
                    val target = SessionTarget(profile, s.id, s.displayTitle)
                    SessionRow(s, selected = selectedKey == target.key, onClick = { onOpen(target) }, onPin = { onPin(s, it) })
                }
            }
            item(key = "header") { SectionHeader("最近の会話", rest.size) }
            items(rest, key = { it.id }) { s ->
                val target = SessionTarget(profile, s.id, s.displayTitle)
                SessionRow(s, selected = selectedKey == target.key, onClick = { onOpen(target) }, onPin = { onPin(s, it) })
            }
        }
    }
}

/** The servers signed in to, with a way to add one and to drop one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServersSheet(state: AppState, onAdd: () -> Unit, onRemove: (String) -> Unit, onRetry: (String) -> Unit, onDismiss: () -> Unit) {
    var removing by remember { mutableStateOf<ServerState?>(null) }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = HermesColors.Rail) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("サーバー", fontSize = 17.sp, fontWeight = FontWeight.Bold)
            state.servers.forEach { server ->
                val shape = RoundedCornerShape(14.dp)
                Row(
                    Modifier.fillMaxWidth().background(HermesColors.Surface, shape).border(1.dp, HermesColors.Line, shape).padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(10.dp).background(server.status.color(), CircleShape))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(server.host, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFamily = MonoFamily, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            when (server.status) {
                                ServerStatus.Ready -> "Bot ${server.bots.size} 件 · ${server.user}"
                                ServerStatus.Connecting -> "接続中…"
                                ServerStatus.Failed -> server.error ?: "つながっていません"
                            },
                            fontSize = 12.sp, color = if (server.status == ServerStatus.Failed) HermesColors.Danger else HermesColors.Muted,
                        )
                    }
                    if (server.status == ServerStatus.Failed) TextButton(onClick = { onRetry(server.url) }) { Text("つなぎ直す", color = HermesColors.Accent, fontSize = 13.sp) }
                    TextButton(onClick = { removing = server }) { Text("外す", color = HermesColors.Muted, fontSize = 13.sp) }
                }
            }
            Button(
                onClick = onAdd, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = HermesColors.Accent, contentColor = HermesColors.OnAccent),
            ) {
                Icon(Icons.Default.Add, null)
                Spacer(Modifier.width(6.dp))
                Text("サーバーを追加", fontWeight = FontWeight.Bold)
            }
        }
    }
    removing?.let { server ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("${server.host} を外しますか") },
            text = { Text("この端末からサインアウトして、一覧から外します。サーバー側の会話は消えません。") },
            confirmButton = { TextButton(onClick = { removing = null; onRemove(server.url) }) { Text("外す", color = HermesColors.Danger) } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("やめる") } },
        )
    }
}

@Composable
private fun ThemeButton(mode: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val options = listOf(
        Triple(ThemeMode.SYSTEM, "端末の設定に合わせる", Icons.Default.BrightnessAuto),
        Triple(ThemeMode.LIGHT, "ライト", Icons.Default.LightMode),
        Triple(ThemeMode.DARK, "ダーク", Icons.Default.DarkMode),
    )
    Box {
        IconButton(onClick = { open = true }) {
            Icon(options.first { it.first == mode }.third, "テーマ", tint = HermesColors.Muted)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = HermesColors.SurfaceHigh) {
            options.forEach { (m, label, icon) ->
                DropdownMenuItem(
                    leadingIcon = { Icon(icon, null) },
                    text = { Text(label) },
                    trailingIcon = { if (m == mode) Icon(Icons.Default.Check, null, tint = HermesColors.Accent) },
                    onClick = { open = false; onSelect(m) },
                )
            }
        }
    }
}

/** The bot's own avatar from Hermes when it has one, else a generic robot glyph. */
@Composable
fun BotAvatar(size: Int, image: ImageBitmap? = null) {
    val shape = RoundedCornerShape((size / 3.5).dp)
    if (image != null) {
        Image(image, null, contentScale = ContentScale.Crop, modifier = Modifier.size(size.dp).clip(shape))
        return
    }
    Box(Modifier.size(size.dp).background(HermesColors.BotAvatar, shape), contentAlignment = Alignment.Center) {
        Icon(Icons.Default.SmartToy, null, tint = HermesColors.Working, modifier = Modifier.size((size * 0.55).dp))
    }
}

@Composable
private fun BotChatCard(avatar: ImageBitmap?, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier.fillMaxWidth().background(if (selected) HermesColors.SurfaceHigh else HermesColors.BotCard, shape)
            .border(1.dp, HermesColors.BotCardLine, shape).clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BotAvatar(32, avatar)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(SessionTarget.BOT_CHAT, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text("この Bot の常設窓口", fontSize = 11.sp, color = HermesColors.Muted)
        }
    }
}

@Composable
private fun SectionHeader(title: String, count: Int) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 16.dp, bottom = 8.dp)) {
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = HermesColors.TextSoft)
        Spacer(Modifier.weight(1f))
        Text("$count 件", fontSize = 12.sp, color = HermesColors.Muted)
    }
}

/** A long press offers pinning, the same pin Desktop shows. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(s: SessionSummary, selected: Boolean, onClick: () -> Unit, onPin: (Boolean) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Column(
            Modifier.fillMaxWidth().background(if (selected) HermesColors.SurfaceHigh else HermesColors.Rail, RoundedCornerShape(12.dp))
                .combinedClickable(onClick = onClick, onLongClick = { menu = true }, onLongClickLabel = "ピン留めの操作")
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (s.pinned) {
                    Icon(Icons.Default.PushPin, "ピン留め", tint = HermesColors.Accent, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                }
                Text(s.displayTitle, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (s.isActive) Badge("実行中", HermesColors.WorkingSurface, HermesColors.Working)
            }
            Row {
                Text("${s.messageCount} 件", fontSize = 11.sp, color = HermesColors.Muted, fontFamily = MonoFamily)
                Spacer(Modifier.weight(1f))
                Text(relative(s.activeAt), fontSize = 11.sp, color = HermesColors.Muted, fontFamily = MonoFamily)
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(if (s.pinned) "ピン留めを外す" else "ピン留めする") },
                leadingIcon = { Icon(Icons.Default.PushPin, null) },
                onClick = { menu = false; onPin(!s.pinned) },
            )
        }
    }
    HorizontalDivider(color = HermesColors.Rail, thickness = 2.dp)
}

@Composable
fun Badge(text: String, bg: Color, fg: Color) {
    Text(text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = fg,
        modifier = Modifier.background(bg, RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 3.dp))
}

private fun relative(epochSeconds: Double): String {
    if (epochSeconds <= 0) return ""
    return DateUtils.getRelativeTimeSpanString((epochSeconds * 1000).toLong(), System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
}
