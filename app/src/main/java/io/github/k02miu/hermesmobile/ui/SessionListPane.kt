package io.github.k02miu.hermesmobile.ui

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuOpen
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.Image
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

@Composable
fun SessionListPane(
    host: String,
    state: ListState,
    avatars: Map<String, ImageBitmap>,
    selectedKey: String?,
    onSelectProfile: (String) -> Unit,
    onOpen: (SessionTarget) -> Unit,
    onRefresh: () -> Unit,
    themeMode: ThemeMode,
    onThemeMode: (ThemeMode) -> Unit,
    modifier: Modifier = Modifier,
    onHide: (() -> Unit)? = null,
) {
    Column(modifier.fillMaxSize().background(HermesColors.Rail)) {
        Row(Modifier.fillMaxWidth().padding(start = if (onHide != null) 4.dp else 16.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onHide != null) IconButton(onClick = onHide) { Icon(Icons.AutoMirrored.Filled.MenuOpen, "一覧を閉じる", tint = HermesColors.Muted) }
            Row(
                Modifier.background(HermesColors.Surface, RoundedCornerShape(16.dp)).border(1.dp, HermesColors.Line, RoundedCornerShape(16.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(8.dp).background(HermesColors.Done, RoundedCornerShape(4.dp)))
                Spacer(Modifier.width(8.dp))
                Text(host, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.weight(1f))
            ThemeButton(themeMode, onThemeMode)
            IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, "再読み込み", tint = HermesColors.Muted) }
            if (state.profile.isNotEmpty()) {
                FilledIconButton(
                    onClick = { onOpen(SessionTarget.newChat(state.profile)) },
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = HermesColors.Accent, contentColor = HermesColors.OnAccent),
                ) { Icon(Icons.Default.Add, "新しい会話") }
            }
        }
        BotPicker(state, avatars, onSelectProfile)
        if (state.refreshing) LinearProgressIndicator(Modifier.fillMaxWidth(), color = HermesColors.Working, trackColor = HermesColors.Rail)
        state.error?.let { Text(it, color = HermesColors.Danger, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp)) }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp)) {
            if (state.profile.isNotEmpty()) {
                item(key = "bot") {
                    val target = SessionTarget.botChat(state.profile)
                    BotChatCard(avatars[state.profile], selected = selectedKey == target.key, onClick = { onOpen(target) })
                }
            }
            item(key = "header") {
                Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 16.dp, bottom = 8.dp)) {
                    Text("会話", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = HermesColors.TextSoft)
                    Spacer(Modifier.weight(1f))
                    Text("${state.sessions.size} 件", fontSize = 12.sp, color = HermesColors.Muted)
                }
            }
            items(state.sessions, key = { it.id }) { s ->
                val target = SessionTarget(state.profile, s.id, s.displayTitle)
                SessionRow(s, selected = selectedKey == target.key, onClick = { onOpen(target) })
            }
        }
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

@Composable
private fun BotPicker(state: ListState, avatars: Map<String, ImageBitmap>, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val current = state.profiles.firstOrNull { it.name == state.profile }
    Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { open = true },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BotAvatar(40, avatars[state.profile])
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Bot", fontSize = 11.sp, color = HermesColors.Muted)
                Text(current?.label ?: state.profile, fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = MonoFamily, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Default.ArrowDropDown, "Bot を選ぶ")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = HermesColors.SurfaceHigh) {
            state.profiles.forEach { p ->
                DropdownMenuItem(
                    leadingIcon = { BotAvatar(32, avatars[p.name]) },
                    text = {
                        Column {
                            Text(p.label, fontFamily = MonoFamily, fontWeight = FontWeight.SemiBold)
                            Text(p.model, fontSize = 11.sp, color = HermesColors.Muted)
                        }
                    },
                    trailingIcon = { if (p.name == state.profile) Icon(Icons.Default.Check, null, tint = HermesColors.Accent) },
                    onClick = { open = false; onSelect(p.name) },
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
private fun SessionRow(s: SessionSummary, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(if (selected) HermesColors.SurfaceHigh else HermesColors.Rail, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(s.displayTitle, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (s.isActive) Badge("実行中", HermesColors.WorkingSurface, HermesColors.Working)
        }
        Row {
            Text("${s.messageCount} 件", fontSize = 11.sp, color = HermesColors.Muted, fontFamily = MonoFamily)
            Spacer(Modifier.weight(1f))
            Text(relative(s.activeAt), fontSize = 11.sp, color = HermesColors.Muted, fontFamily = MonoFamily)
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
