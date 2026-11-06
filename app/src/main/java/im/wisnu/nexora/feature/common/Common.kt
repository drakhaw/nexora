package im.wisnu.nexora.feature.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import im.wisnu.nexora.core.adb.AdbError
import im.wisnu.nexora.core.adb.ConnectionState
import im.wisnu.nexora.core.adb.isUsable
import im.wisnu.nexora.domain.device.SelectedDevice
import im.wisnu.nexora.feature.devices.describe
import im.wisnu.nexora.ui.theme.NexoraTokens

/** Shows [content] only when the selected device is connected; otherwise explains why not. */
@Composable
fun RequireConnected(selected: SelectedDevice, content: @Composable () -> Unit) {
    val device = selected.device
    when {
        device == null -> Notice("No device selected. Pick one on the Devices tab.")
        !selected.state.isUsable ->
            Notice("${device.name} is not connected (${selected.state.label()}). Connect it on the Devices tab.")
        else -> content()
    }
}

fun ConnectionState.label(): String = when (this) {
    ConnectionState.Connected -> "connected"
    ConnectionState.Connecting -> "connecting"
    ConnectionState.AwaitingApproval -> "waiting for approval on the TV"
    ConnectionState.Validating -> "validating"
    ConnectionState.Disconnected -> "disconnected"
    ConnectionState.PairingRequired -> "pairing required"
    ConnectionState.Unauthorized -> "unauthorized"
    is ConnectionState.Error -> "error"
}

@Composable
fun Notice(text: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Dismissible message card for results and errors (REM-09, CON-09). */
@Composable
fun MessageCard(message: String?, onDismiss: () -> Unit) {
    if (message == null) return
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = NexoraTokens.SurfaceElevated),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onDismiss) { Text("OK") }
        }
    }
}

fun <T> im.wisnu.nexora.core.adb.AdbResult<T>.errorText(): String? =
    (this as? im.wisnu.nexora.core.adb.AdbResult.Err)?.error?.describe()

fun AdbError.text(): String = describe()
