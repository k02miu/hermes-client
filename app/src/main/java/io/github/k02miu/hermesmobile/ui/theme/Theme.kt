package io.github.k02miu.hermesmobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Palette from the design canvas: ink ground, amber accent for "needs you", blue for "working". */
data class HermesPalette(
    val isDark: Boolean,
    val ground: Color,
    val rail: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val line: Color,
    val text: Color,
    val textSoft: Color,
    val muted: Color,
    val accent: Color,
    val onAccent: Color,
    val accentSurface: Color,
    val accentLine: Color,
    val working: Color,
    val workingSurface: Color,
    val userBubble: Color,
    val botAvatar: Color,
    val botCard: Color,
    val botCardLine: Color,
    val terminal: Color,
    val done: Color,
    val danger: Color,
)

val DarkPalette = HermesPalette(
    isDark = true,
    ground = Color(0xFF0D1117),
    rail = Color(0xFF0F141A),
    surface = Color(0xFF161B22),
    surfaceHigh = Color(0xFF1F2630),
    line = Color(0xFF2A323D),
    text = Color(0xFFE6EDF3),
    textSoft = Color(0xFFC3CBD5),
    muted = Color(0xFF9BA6B4),
    accent = Color(0xFFF5B342),
    onAccent = Color(0xFF1A1205),
    accentSurface = Color(0xFF1E1810),
    accentLine = Color(0xFF5C4718),
    working = Color(0xFF8FC7FF),
    workingSurface = Color(0xFF13263D),
    userBubble = Color(0xFF1F2A3A),
    botAvatar = Color(0xFF233044),
    botCard = Color(0xFF18202B),
    botCardLine = Color(0xFF2E3A4A),
    terminal = Color(0xFF06080B),
    done = Color(0xFF7EE0A0),
    danger = Color(0xFFFF9C94),
)

/** Same roles on paper: amber stays the call to action, blue darkens to keep contrast on white. */
val LightPalette = HermesPalette(
    isDark = false,
    ground = Color(0xFFFFFFFF),
    rail = Color(0xFFF6F8FA),
    surface = Color(0xFFFFFFFF),
    surfaceHigh = Color(0xFFEAEEF2),
    line = Color(0xFFD0D7DE),
    text = Color(0xFF1F2328),
    textSoft = Color(0xFF3D444D),
    muted = Color(0xFF656D76),
    accent = Color(0xFFF5B342),
    onAccent = Color(0xFF1A1205),
    accentSurface = Color(0xFFFFF6E3),
    accentLine = Color(0xFFE5C06E),
    working = Color(0xFF0969DA),
    workingSurface = Color(0xFFDDF4FF),
    userBubble = Color(0xFFE3EEFB),
    botAvatar = Color(0xFFDCE6F2),
    botCard = Color(0xFFFFFFFF),
    botCardLine = Color(0xFFD0D7DE),
    terminal = Color(0xFFF3F5F7),
    done = Color(0xFF1A7F37),
    danger = Color(0xFFCF222E),
)

val LocalHermesPalette = staticCompositionLocalOf { DarkPalette }

/** The current palette's colors; read inside composition so screens follow light/dark switches. */
object HermesColors {
    val Ground @Composable @ReadOnlyComposable get() = LocalHermesPalette.current.ground
    val Rail @Composable @ReadOnlyComposable get() = LocalHermesPalette.current.rail
    val Surface @Composable @ReadOnlyComposable get() = LocalHermesPalette.current.surface
    val SurfaceHigh @Composable @ReadOnlyComposable get() = LocalHermesPalette.current.surfaceHigh
    val Line @Composable @ReadOnlyComposable get() = LocalHermesPalette.current.line
    val Text @Composable @ReadOnlyComposable get() = LocalHermesPalette.current.text
    val TextSoft @Composable @ReadOnlyComposable get() = LocalHermesPalette.current.textSoft
    val Muted @Composable @ReadOnlyComposable get() = LocalHermesPalette.current.muted
    val Accent @Composable @ReadOnlyComposable get() = LocalHermesPalette.current.accent
    val OnAccent @Composable @ReadOnlyComposable get() = LocalHermesPalette.current.onAccent
    val AccentSurface @Composable @ReadOnlyComposable get() = LocalHermesPalette.current.accentSurface
    val AccentLine @Composable @ReadOnlyComposable get() = LocalHermesPalette.current.accentLine
    val Working @Composable @ReadOnlyComposable get() = LocalHermesPalette.current.working
    val WorkingSurface @Composable @ReadOnlyComposable get() = LocalHermesPalette.current.workingSurface
    val UserBubble @Composable @ReadOnlyComposable get() = LocalHermesPalette.current.userBubble
    val BotAvatar @Composable @ReadOnlyComposable get() = LocalHermesPalette.current.botAvatar
    val BotCard @Composable @ReadOnlyComposable get() = LocalHermesPalette.current.botCard
    val BotCardLine @Composable @ReadOnlyComposable get() = LocalHermesPalette.current.botCardLine
    val Terminal @Composable @ReadOnlyComposable get() = LocalHermesPalette.current.terminal
    val Done @Composable @ReadOnlyComposable get() = LocalHermesPalette.current.done
    val Danger @Composable @ReadOnlyComposable get() = LocalHermesPalette.current.danger
}

val MonoFamily = FontFamily.Monospace

private fun HermesPalette.scheme() = (if (isDark) darkColorScheme() else lightColorScheme()).copy(
    primary = accent,
    onPrimary = onAccent,
    secondary = working,
    background = ground,
    onBackground = text,
    surface = ground,
    onSurface = text,
    surfaceVariant = surface,
    onSurfaceVariant = muted,
    surfaceContainer = surface,
    surfaceContainerHigh = surfaceHigh,
    outline = line,
    outlineVariant = line,
    error = danger,
)

@Composable
fun isHermesDark(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun HermesTheme(dark: Boolean, content: @Composable () -> Unit) {
    val palette = if (dark) DarkPalette else LightPalette
    CompositionLocalProvider(LocalHermesPalette provides palette) {
        MaterialTheme(colorScheme = palette.scheme(), content = content)
    }
}
