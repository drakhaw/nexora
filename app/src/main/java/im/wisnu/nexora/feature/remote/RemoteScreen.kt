package im.wisnu.nexora.feature.remote

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardReturn
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ViewCarousel
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import im.wisnu.nexora.domain.remote.RemoteKey
import im.wisnu.nexora.feature.common.MessageCard
import im.wisnu.nexora.feature.common.RequireConnected
import im.wisnu.nexora.ui.theme.NexoraTokens
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Keys that send a long press when held (REM-05). */
private val longPressKeys = setOf(RemoteKey.Center, RemoteKey.Home, RemoteKey.Back)

@Composable
fun RemoteScreen(vm: RemoteViewModel = hiltViewModel()) {
    val selected by vm.selected.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()

    RequireConnected(selected) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(selected.device?.name.orEmpty(), style = MaterialTheme.typography.titleMedium)
            MessageCard(message, vm::dismiss)

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                KeyButton(RemoteKey.Power, Icons.Outlined.PowerSettingsNew, vm::press)
                KeyButton(RemoteKey.Menu, Icons.Outlined.Menu, vm::press)
                KeyButton(RemoteKey.Recents, Icons.Outlined.ViewCarousel, vm::press)
                KeyButton(RemoteKey.PlayPause, Icons.Outlined.PlayArrow, vm::press)
            }

            DPad(vm::press)

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                KeyButton(RemoteKey.Back, Icons.AutoMirrored.Outlined.ArrowBack, vm::press)
                KeyButton(RemoteKey.Home, Icons.Outlined.Home, vm::press)
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                KeyButton(RemoteKey.VolumeDown, Icons.Outlined.Remove, vm::press)
                KeyButton(RemoteKey.Mute, Icons.AutoMirrored.Outlined.VolumeOff, vm::press)
                KeyButton(RemoteKey.VolumeUp, Icons.Outlined.Add, vm::press)
            }

            KeyboardPanel(vm)
        }
    }
}

/** Inner OK circle radius as a fraction of the pad radius. */
private const val CENTER_FRACTION = 0.42f

/**
 * Round D-pad: four wedge sectors split by diagonal gaps around an OK circle.
 * One touch handler picks the key from where the finger lands, so the whole
 * wedge is the hit area, not just the arrow.
 */
@Composable
private fun DPad(onPress: (RemoteKey, Boolean) -> Unit) {
    var pressed by remember { mutableStateOf<RemoteKey?>(null) }
    val padSize = 280.dp
    Box(
        Modifier
            .size(padSize)
            .clip(CircleShape)
            .keyGestures(keyAt = ::dpadKeyAt, onPress = onPress, onPressedChange = { pressed = it }),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) { drawDPad(pressed) }
        Text(
            RemoteKey.Center.label,
            color = NexoraTokens.TextPrimary,
            style = MaterialTheme.typography.headlineSmall,
        )
        // Invisible targets that carry each key's accessibility actions.
        val step = padSize * 0.36f
        DPadSemantics(RemoteKey.Up, onPress, Modifier.offset(y = -step))
        DPadSemantics(RemoteKey.Down, onPress, Modifier.offset(y = step))
        DPadSemantics(RemoteKey.Left, onPress, Modifier.offset(x = -step))
        DPadSemantics(RemoteKey.Right, onPress, Modifier.offset(x = step))
        DPadSemantics(RemoteKey.Center, onPress, Modifier)
    }
}

@Composable
private fun DPadSemantics(key: RemoteKey, onPress: (RemoteKey, Boolean) -> Unit, modifier: Modifier) {
    Box(modifier.size(56.dp).keySemantics(key, onPress))
}

private fun dpadKeyAt(position: Offset, size: IntSize): RemoteKey? {
    val radius = size.width / 2f
    val dx = position.x - radius
    val dy = position.y - size.height / 2f
    val distance = hypot(dx, dy)
    return when {
        distance > radius -> null
        distance <= radius * CENTER_FRACTION -> RemoteKey.Center
        abs(dx) > abs(dy) -> if (dx > 0) RemoteKey.Right else RemoteKey.Left
        else -> if (dy > 0) RemoteKey.Down else RemoteKey.Up
    }
}

/** Sector start angles in degrees, clockwise from the positive x axis, each 90 degrees wide. */
private val sectorAngles = mapOf(
    RemoteKey.Right to -45f,
    RemoteKey.Down to 45f,
    RemoteKey.Left to 135f,
    RemoteKey.Up to 225f,
)

private fun DrawScope.drawDPad(pressed: RemoteKey?) {
    val radius = size.minDimension / 2f
    val inner = radius * CENTER_FRACTION
    val gap = 2.dp.toPx()
    val base = NexoraTokens.SurfaceElevated
    val highlight = NexoraTokens.Accent.copy(alpha = 0.35f).compositeOver(base)

    drawCircle(base, radius)
    sectorAngles[pressed]?.let { start ->
        drawArc(highlight, start, 90f, useCenter = true, topLeft = Offset.Zero, size = Size(size.width, size.height))
    }

    // Diagonal gaps between the wedges.
    for (i in 0 until 4) {
        val angle = Math.toRadians(45.0 + 90.0 * i)
        val dir = Offset(cos(angle).toFloat(), sin(angle).toFloat())
        drawLine(NexoraTokens.Background, center + dir * inner, center + dir * radius, strokeWidth = gap)
    }

    // OK circle with a ring in the gap color.
    drawCircle(if (pressed == RemoteKey.Center) highlight else base, inner)
    drawCircle(NexoraTokens.Background, inner, style = Stroke(width = gap * 1.5f))

    // Filled triangle arrows, drawn pointing up and rotated into place.
    val arrowCenter = radius * 0.72f
    val half = radius * 0.11f
    val arrow = Path().apply {
        moveTo(center.x, center.y - arrowCenter - half * 0.9f)
        lineTo(center.x + half, center.y - arrowCenter + half * 0.7f)
        lineTo(center.x - half, center.y - arrowCenter + half * 0.7f)
        close()
    }
    for (degrees in listOf(0f, 90f, 180f, 270f)) {
        rotate(degrees, pivot = center) { drawPath(arrow, NexoraTokens.TextPrimary) }
    }
}

/** Accessibility actions mirror tap and long press. */
private fun Modifier.keySemantics(key: RemoteKey, onPress: (RemoteKey, Boolean) -> Unit): Modifier =
    semantics {
        role = Role.Button
        contentDescription = key.label
        onClick { onPress(key, false); true }
        if (key in longPressKeys) onLongClick(label = "Long press") { onPress(key, true); true }
    }

/**
 * Press handling shared by every key. [keyAt] maps the touch-down point to a
 * key (null ignores the touch). Repeatable keys repeat while held; OK/Home/Back
 * send a long press when held past 500 ms.
 */
@Composable
private fun Modifier.keyGestures(
    keyAt: (Offset, IntSize) -> RemoteKey?,
    onPress: (RemoteKey, Boolean) -> Unit,
    onPressedChange: (RemoteKey?) -> Unit,
): Modifier {
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val currentKeyAt by rememberUpdatedState(keyAt)
    val currentOnPress by rememberUpdatedState(onPress)
    val currentOnPressedChange by rememberUpdatedState(onPressedChange)
    return pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown()
            val key = currentKeyAt(down.position, size) ?: return@awaitEachGesture
            currentOnPressedChange(key)
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            if (key.repeatable) {
                currentOnPress(key, false)
                val repeater = scope.launch {
                    delay(400)
                    while (true) {
                        currentOnPress(key, false)
                        delay(110)
                    }
                }
                waitForUpOrCancellation()
                repeater.cancel()
            } else {
                var released = false
                var cancelled = false
                withTimeoutOrNull(500) {
                    if (waitForUpOrCancellation() == null) cancelled = true else released = true
                }
                when {
                    released -> currentOnPress(key, false)
                    cancelled -> Unit
                    else -> {
                        currentOnPress(key, key in longPressKeys)
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        waitForUpOrCancellation()
                    }
                }
            }
            currentOnPressedChange(null)
        }
    }
}

/** A square remote key outside the D-pad. */
@Composable
private fun KeyButton(
    key: RemoteKey,
    icon: ImageVector,
    onPress: (RemoteKey, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
    shape: Shape = RoundedCornerShape(NexoraTokens.CardRadius),
) {
    var pressed by remember { mutableStateOf(false) }
    val base = NexoraTokens.SurfaceElevated
    Box(
        modifier
            .size(size)
            .clip(shape)
            .background(if (pressed) base.copy(alpha = 0.6f) else base)
            .keySemantics(key, onPress)
            .keyGestures(keyAt = { _, _ -> key }, onPress = onPress, onPressedChange = { pressed = it != null }),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = NexoraTokens.TextPrimary)
    }
}

@Composable
private fun KeyboardPanel(vm: RemoteViewModel) {
    var text by remember { mutableStateOf("") }
    val send = { if (text.isNotEmpty()) vm.type(text) { text = "" } }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Keyboard", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Text to send") },
            supportingText = { Text("Focus a text field on the TV first. ASCII only; \"%s\" is sent as a space.") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send() }),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = send, enabled = text.isNotEmpty()) { Text("Send text") }
            Spacer(Modifier.weight(1f))
            KeyButton(RemoteKey.Delete, Icons.AutoMirrored.Outlined.Backspace, vm::press, size = 48.dp)
            KeyButton(RemoteKey.Enter, Icons.AutoMirrored.Outlined.KeyboardReturn, vm::press, size = 48.dp)
        }
    }
}
