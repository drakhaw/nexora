package im.wisnu.nexora.feature.remote

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
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.KeyboardArrowUp
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import im.wisnu.nexora.domain.remote.RemoteKey
import im.wisnu.nexora.feature.common.MessageCard
import im.wisnu.nexora.feature.common.RequireConnected
import im.wisnu.nexora.ui.theme.NexoraTokens
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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

@Composable
private fun DPad(onPress: (RemoteKey, Boolean) -> Unit) {
    Box(
        Modifier.size(240.dp).clip(CircleShape).background(NexoraTokens.Surface),
        contentAlignment = Alignment.Center,
    ) {
        KeyButton(RemoteKey.Up, Icons.Outlined.KeyboardArrowUp, onPress, Modifier.align(Alignment.TopCenter).padding(8.dp))
        KeyButton(RemoteKey.Down, Icons.Outlined.KeyboardArrowDown, onPress, Modifier.align(Alignment.BottomCenter).padding(8.dp))
        KeyButton(RemoteKey.Left, Icons.AutoMirrored.Outlined.KeyboardArrowLeft, onPress, Modifier.align(Alignment.CenterStart).padding(8.dp))
        KeyButton(RemoteKey.Right, Icons.AutoMirrored.Outlined.KeyboardArrowRight, onPress, Modifier.align(Alignment.CenterEnd).padding(8.dp))
        KeyButton(RemoteKey.Center, null, onPress, size = 88.dp, shape = CircleShape, accent = true)
    }
}

/**
 * A remote key. Repeatable keys repeat while held; OK/Home/Back send a long
 * press when held past 500 ms. Accessibility actions mirror tap and long press.
 */
@Composable
private fun KeyButton(
    key: RemoteKey,
    icon: ImageVector?,
    onPress: (RemoteKey, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
    shape: Shape = RoundedCornerShape(NexoraTokens.CardRadius),
    accent: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    var pressed by remember { mutableStateOf(false) }
    val supportsLong = key in longPressKeys
    val base = if (accent) NexoraTokens.Accent else NexoraTokens.SurfaceElevated

    Box(
        modifier
            .size(size)
            .clip(shape)
            .background(if (pressed) base.copy(alpha = 0.6f) else base)
            .semantics {
                role = Role.Button
                contentDescription = key.label
                onClick { onPress(key, false); true }
                if (supportsLong) onLongClick(label = "Long press") { onPress(key, true); true }
            }
            .pointerInput(key) {
                awaitEachGesture {
                    awaitFirstDown()
                    pressed = true
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    if (key.repeatable) {
                        onPress(key, false)
                        val repeater = scope.launch {
                            delay(400)
                            while (true) {
                                onPress(key, false)
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
                            released -> onPress(key, false)
                            cancelled -> Unit
                            else -> {
                                onPress(key, supportsLong)
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                waitForUpOrCancellation()
                            }
                        }
                    }
                    pressed = false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = NexoraTokens.TextPrimary)
        } else {
            Text(key.label, color = NexoraTokens.Background, style = MaterialTheme.typography.titleMedium)
        }
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
