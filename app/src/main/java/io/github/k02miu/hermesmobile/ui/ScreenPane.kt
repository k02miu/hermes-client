package io.github.k02miu.hermesmobile.ui

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.k02miu.hermesmobile.data.RfbSession
import io.github.k02miu.hermesmobile.ui.theme.HermesColors
import io.github.k02miu.hermesmobile.ui.theme.MonoFamily
import kotlin.math.roundToInt

/** A bot's Bot Desktop: watch what its computer use is doing, or take the screen over to log in or unstick it. */
@Composable
fun ScreenPane(
    vm: ScreenViewModel, botLabel: String, avatar: ImageBitmap?, onBack: (() -> Unit)?, modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val activity = LocalActivity.current
    // Streams only while visible. Leaving the pane hands the screen back to the bot; the app going to the
    // background or the phone rotating does not.
    DisposableEffect(vm, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) vm.attach()
            else if (event == Lifecycle.Event.ON_STOP) vm.detach(release = false)
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            vm.detach(release = activity?.isChangingConfigurations != true)
        }
    }
    Column(modifier.fillMaxSize().background(HermesColors.Ground).imePadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = if (onBack != null || leading != null) 4.dp else 20.dp, end = 8.dp, top = 12.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "戻る") }
            leading?.invoke()
            BotAvatar(32, avatar)
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                Text(botLabel, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Bot Desktop", fontSize = 11.sp, color = HermesColors.Muted, maxLines = 1)
            }
            if (state.phase == ScreenPhase.Live && state.holder == ScreenHolder.Me) Badge("操作中", HermesColors.AccentSurface, HermesColors.Accent)
            Spacer(Modifier.size(8.dp))
            actions()
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(HermesColors.Line))
        Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black), contentAlignment = Alignment.Center) {
            when (val phase = state.phase) {
                ScreenPhase.Loading, ScreenPhase.Connecting -> CircularProgressIndicator(color = HermesColors.Working)
                ScreenPhase.Live -> ScreenCanvas(vm, state)
                is ScreenPhase.Unavailable -> Message(phase.message)
                is ScreenPhase.Failed -> Message(phase.message, action = "もう一度つなぐ", onAction = vm::retry)
                is ScreenPhase.Stopped -> when {
                    phase.starting -> Message("起動しています…", busy = true)
                    phase.blocker != null -> Message("Bot Desktop は止まっています。\n${phase.blocker}")
                    else -> Message(phase.error ?: "Bot Desktop は止まっています。", action = "起動する", onAction = vm::start)
                }
            }
        }
        if (state.phase == ScreenPhase.Live) {
            state.notice?.let {
                Text(it, color = HermesColors.Danger, fontSize = 12.sp, modifier = Modifier.clickable(onClick = vm::clearNotice).padding(horizontal = 16.dp, vertical = 4.dp))
            }
            LeaseBar(state, onTake = vm::takeControl, onHandBack = vm::handBack)
            if (state.holder == ScreenHolder.Me) Keyboard(onType = vm::type, onKey = vm::key, onWheel = vm::wheel)
        }
    }
}

@Composable
private fun Message(text: String, action: String? = null, onAction: () -> Unit = {}, busy: Boolean = false) {
    Column(Modifier.widthIn(max = 420.dp).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (busy) CircularProgressIndicator(color = HermesColors.Working)
        Text(text, color = Color(0xFFD8D8D8), fontSize = 14.sp, lineHeight = 22.sp, textAlign = TextAlign.Center)
        if (action != null) Button(
            onClick = onAction, shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = HermesColors.Accent, contentColor = HermesColors.OnAccent),
        ) { Text(action, fontWeight = FontWeight.Bold) }
    }
}

/** Who has the screen, and the one thing this phone can do about it. */
@Composable
private fun LeaseBar(state: ScreenState, onTake: () -> Unit, onHandBack: () -> Unit) {
    val (text, action, onAction) = when (state.holder) {
        ScreenHolder.Agent -> Triple("Bot が操作しています。見ているだけです", "操作する", onTake)
        ScreenHolder.Me -> Triple("あなたが操作中です。Bot は手を止めて待っています", "Bot に返す", onHandBack)
        ScreenHolder.Other -> Triple("ほかの端末（Desktop など）で操作中です", "引き継ぐ", onTake)
    }
    Row(
        Modifier.fillMaxWidth().background(HermesColors.Rail).padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, fontSize = 12.sp, color = HermesColors.TextSoft, modifier = Modifier.weight(1f))
        Spacer(Modifier.size(8.dp))
        val own = state.holder == ScreenHolder.Me
        Button(
            onClick = onAction, enabled = !state.busy, shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (own) HermesColors.Surface else HermesColors.Accent,
                contentColor = if (own) HermesColors.Text else HermesColors.OnAccent,
            ),
        ) { Text(action, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
    }
}

/**
 * Typing goes through a field of the phone's own (so the Japanese keyboard, paste and autofill all work) and is
 * sent as key presses; the keys a phone keyboard lacks sit in a row underneath.
 */
@Composable
private fun Keyboard(onType: (String) -> Unit, onKey: (Int) -> Unit, onWheel: (Boolean) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    fun submit() {
        if (text.isEmpty()) onKey(RfbSession.KEY_ENTER) else { onType(text); text = "" }
    }
    Column(Modifier.fillMaxWidth().background(HermesColors.Rail).padding(horizontal = 12.dp).padding(bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                placeholder = { Text("ここに打って送ると、画面に入力されます", fontSize = 13.sp) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onSend = { submit() }),
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = ::submit) { Icon(Icons.AutoMirrored.Filled.Send, "入力を送る", tint = HermesColors.Accent) }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            KeyChip("Enter") { onKey(RfbSession.KEY_ENTER) }
            KeyChip("⌫") { onKey(RfbSession.KEY_BACKSPACE) }
            KeyChip("Tab") { onKey(RfbSession.KEY_TAB) }
            KeyChip("Esc") { onKey(RfbSession.KEY_ESCAPE) }
            KeyChip("←") { onKey(RfbSession.KEY_LEFT) }
            KeyChip("↑") { onKey(RfbSession.KEY_UP) }
            KeyChip("↓") { onKey(RfbSession.KEY_DOWN) }
            KeyChip("→") { onKey(RfbSession.KEY_RIGHT) }
            KeyChip("スクロール ▲") { onWheel(true) }
            KeyChip("スクロール ▼") { onWheel(false) }
        }
    }
}

@Composable
private fun KeyChip(label: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier.heightIn(min = 40.dp).widthIn(min = 44.dp).background(HermesColors.Surface, shape).border(1.dp, HermesColors.Line, shape)
            .clickable(onClick = onClick).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, fontSize = 13.sp, fontFamily = MonoFamily) }
}

/**
 * The picture, fitted to the pane. Two fingers zoom and pan. One finger pans while watching; while this phone
 * holds the screen it is the mouse: a tap clicks, a long press right-clicks, a drag drags.
 */
@Composable
private fun ScreenCanvas(vm: ScreenViewModel, state: ScreenState) {
    val frame by vm.frame.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    var box by remember { mutableStateOf(IntSize.Zero) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }

    val w = state.width; val h = state.height
    if (w == 0 || h == 0) return
    val fit = if (box.width == 0) 1f else minOf(box.width / w.toFloat(), box.height / h.toFloat())
    val scale = fit * zoom
    // The picture may not be dragged further than its own overhang, so it never leaves the pane.
    val maxPan = Offset(((w * scale - box.width) / 2).coerceAtLeast(0f), ((h * scale - box.height) / 2).coerceAtLeast(0f))
    val clamped = Offset(pan.x.coerceIn(-maxPan.x, maxPan.x), pan.y.coerceIn(-maxPan.y, maxPan.y))
    val origin = Offset((box.width - w * scale) / 2, (box.height - h * scale) / 2) + clamped

    val controlling by rememberUpdatedState(state.holder == ScreenHolder.Me)
    val view by rememberUpdatedState(Triple(origin, scale, clamped))

    Canvas(
        Modifier.fillMaxSize().onSizeChanged { box = it }.pointerInput(vm) {
            val slop = viewConfiguration.touchSlop
            val longPress = viewConfiguration.longPressTimeoutMillis
            fun onScreen(p: Offset): IntOffset = ((p - view.first) / view.second).let { IntOffset(it.x.roundToInt(), it.y.roundToInt()) }
            awaitEachGesture {
                val down = awaitFirstDown()
                val start = down.position
                val startedAt = System.currentTimeMillis()
                var last = start
                var moved = false; var twoFingers = false; var dragging = false; var pressedLong = false
                while (true) {
                    val waitsForLongPress = controlling && !moved && !twoFingers && !pressedLong
                    val event = if (waitsForLongPress) {
                        val left = longPress - (System.currentTimeMillis() - startedAt)
                        withTimeoutOrNull(left.coerceAtLeast(1)) { awaitPointerEvent() }
                    } else awaitPointerEvent()
                    if (event == null) {
                        pressedLong = true
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onScreen(start).let { vm.click(it.x, it.y, RfbSession.BUTTON_RIGHT) }
                        continue
                    }
                    val fingers = event.changes.filter { it.pressed }
                    if (fingers.isEmpty()) break
                    if (fingers.size >= 2) {
                        if (dragging) { onScreen(last).let { vm.pointer(0, it.x, it.y) }; dragging = false }
                        twoFingers = true
                        val centre = Offset(size.width / 2f, size.height / 2f)
                        val around = event.calculateCentroid(useCurrent = false) - centre
                        val next = (zoom * event.calculateZoom()).coerceIn(1f, 8f)
                        // Keep the point under the fingers where it is while the scale changes.
                        pan = (view.third - around) * (next / zoom) + around + event.calculatePan()
                        zoom = next
                        event.changes.forEach { it.consume() }
                    } else if (!twoFingers) {
                        val finger = fingers[0]
                        last = finger.position
                        if (!moved && (finger.position - start).getDistance() > slop) moved = true
                        if (moved && !pressedLong) {
                            if (controlling) {
                                if (!dragging) {
                                    onScreen(start).let { vm.pointer(0, it.x, it.y); vm.pointer(RfbSession.BUTTON_LEFT, it.x, it.y) }
                                    dragging = true
                                }
                                onScreen(last).let { vm.pointer(RfbSession.BUTTON_LEFT, it.x, it.y) }
                            } else pan = view.third + finger.positionChange()
                            finger.consume()
                        }
                    }
                }
                if (dragging) onScreen(last).let { vm.pointer(0, it.x, it.y) }
                else if (controlling && !moved && !twoFingers && !pressedLong) onScreen(start).let { vm.click(it.x, it.y) }
            }
        },
    ) {
        frame // read so every new frame redraws
        val bitmap = vm.bitmap ?: return@Canvas
        drawImage(
            bitmap.asImageBitmap(),
            dstOffset = IntOffset(origin.x.roundToInt(), origin.y.roundToInt()),
            dstSize = IntSize((w * scale).roundToInt(), (h * scale).roundToInt()),
            filterQuality = FilterQuality.Medium,
        )
    }
}
