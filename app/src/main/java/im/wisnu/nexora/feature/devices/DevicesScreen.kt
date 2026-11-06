package im.wisnu.nexora.feature.devices

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import im.wisnu.nexora.core.adb.ConnectionState
import im.wisnu.nexora.core.network.LinkType
import im.wisnu.nexora.domain.monitoring.DeviceInfo
import im.wisnu.nexora.ui.theme.NexoraTokens
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material3.Icon
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import im.wisnu.nexora.core.links.Links

@Composable
fun DevicesScreen(vm: DevicesViewModel = hiltViewModel()) {
    val ui by vm.state.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    var pairing by remember { mutableStateOf(false) }
    var forgetting by remember { mutableStateOf<DeviceRow?>(null) }
    var renaming by remember { mutableStateOf<DeviceRow?>(null) }
    val link by vm.link.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Devices", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            DonateButton()
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { adding = true }) { Text("Add device") }
            OutlinedButton(onClick = { pairing = true }) { Text("Pair") }
        }
        if (link != LinkType.Local) {
            // SEC-08: ADB traffic should stay on a trusted local network.
            Text(
                if (link == LinkType.None) "The phone has no network connection."
                else "The phone is not on Wi-Fi or Ethernet. Use a trusted local network for ADB.",
                color = NexoraTokens.Warning,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (ui.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        ui.message?.let { msg ->
            Card(colors = CardDefaults.cardColors(containerColor = NexoraTokens.SurfaceElevated)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(msg, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = vm::dismissMessage) { Text("OK") }
                }
            }
        }
        if (ui.rows.isEmpty()) {
            Text(
                "No saved devices. Add one by IP address and port, or pair with Wireless Debugging first.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(ui.rows, key = { it.device.id }) { row ->
                DeviceCard(
                    row = row,
                    onSelect = { vm.select(row.device.id) },
                    onConnect = { vm.connect(row.device.id) },
                    onDisconnect = { vm.disconnect(row.device.id) },
                    onForget = { forgetting = row },
                    onRename = { renaming = row },
                    onAutoReconnect = { vm.setAutoReconnect(row.device.id, it) },
                    onReadInfo = { vm.readInfo(row.device.id) },
                    info = ui.info.takeIf { row.selected },
                )
            }
        }
    }

    if (adding) {
        AddDeviceDialog(onDismiss = { adding = false }) { name, host, port ->
            adding = false
            vm.add(name, host, port)
        }
    }
    if (pairing) {
        PairDialog(onDismiss = { pairing = false }) { host, port, code ->
            pairing = false
            vm.pair(host, port, code)
        }
    }
    renaming?.let { row ->
        var name by remember(row.device.id) { mutableStateOf(row.device.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename device") },
            text = { OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = { vm.rename(row.device.id, name); renaming = null }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }
    forgetting?.let { row ->
        // Destructive action needs confirmation (SEC-06).
        AlertDialog(
            onDismissRequest = { forgetting = null },
            title = { Text("Forget ${row.device.name}?") },
            text = { Text("The device is removed from this phone. You may need to pair or authorize it again.") },
            confirmButton = {
                TextButton(onClick = { vm.forget(row.device.id); forgetting = null }) { Text("Forget") }
            },
            dismissButton = { TextButton(onClick = { forgetting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun DeviceCard(
    row: DeviceRow,
    info: DeviceInfo?,
    onSelect: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onForget: () -> Unit,
    onReadInfo: () -> Unit,
    onRename: () -> Unit,
    onAutoReconnect: (Boolean) -> Unit,
) {
    val border = if (row.selected) NexoraTokens.Accent else NexoraTokens.Border
    Card(
        modifier = Modifier.fillMaxWidth().border(1.dp, border, MaterialTheme.shapes.large).clickable(onClick = onSelect),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = NexoraTokens.Surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(row.device.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (row.selected) Text("Selected", color = NexoraTokens.Accent, style = MaterialTheme.typography.labelMedium)
            }
            Text("${row.device.host}:${row.device.connectPort}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            StateLabel(row.state)
            Text(
                "Last check: " + (row.device.lastCheckedAt?.let { DateUtils.getRelativeTimeSpanString(it).toString() } ?: "never"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (row.state) {
                    ConnectionState.Connected -> {
                        OutlinedButton(onClick = onDisconnect) { Text("Disconnect") }
                        Button(onClick = onReadInfo) { Text("Device info") }
                    }
                    ConnectionState.Connecting, ConnectionState.Validating -> Unit
                    else -> Button(onClick = onConnect) { Text("Connect") }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onRename) { Text("Rename") }
                TextButton(onClick = onForget) { Text("Forget") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Auto-reconnect", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(checked = row.device.autoReconnect, onCheckedChange = onAutoReconnect)
            }
            info?.let { InfoBlock(it) }
        }
    }
}

/** State is shown as text plus a dot, never by color alone. */
@Composable
private fun StateLabel(state: ConnectionState) {
    val (label, color) = when (state) {
        ConnectionState.Connected -> "Connected" to NexoraTokens.Success
        ConnectionState.Connecting -> "Connecting…" to NexoraTokens.Warning
        ConnectionState.Validating -> "Validating…" to NexoraTokens.Warning
        ConnectionState.Disconnected -> "Disconnected" to NexoraTokens.TextSecondary
        ConnectionState.PairingRequired -> "Pairing required" to NexoraTokens.Warning
        ConnectionState.Unauthorized -> "Unauthorized: accept the prompt on the TV" to NexoraTokens.Error
        is ConnectionState.Error -> "Error: ${state.error.describe()}" to NexoraTokens.Error
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun InfoBlock(info: DeviceInfo) {
    fun v(s: Any?) = s?.toString() ?: "Unavailable"
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("Manufacturer: ${v(info.manufacturer)}")
        Text("Model: ${v(info.model)}")
        Text("Android: ${v(info.androidVersion)} (SDK ${v(info.sdkInt)})")
        Text(
            "Collected " + DateUtils.getRelativeTimeSpanString(info.collectedAt),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun String.validPort(): Int? = toIntOrNull()?.takeIf { it in 1..65535 }

@Composable
private fun AddDeviceDialog(onDismiss: () -> Unit, onAdd: (String, String, Int) -> Unit) {
    var name by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("5555") }
    val valid = host.isNotBlank() && port.validPort() != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add device") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name (optional)") }, singleLine = true)
                OutlinedTextField(host, { host = it.trim() }, label = { Text("IP address") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                OutlinedTextField(port, { port = it.filter(Char::isDigit) }, label = { Text("Connection port") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    supportingText = { Text("5555 for legacy TCP, or the Wireless Debugging port") })
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onAdd(name, host, port.validPort()!!) }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PairDialog(onDismiss: () -> Unit, onPair: (String, Int, String) -> Unit) {
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    val valid = host.isNotBlank() && port.validPort() != null && code.length == 6
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pair with Wireless Debugging") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "On the TV: Developer options → Wireless debugging → Pair device with pairing code.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(host, { host = it.trim() }, label = { Text("IP address") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                OutlinedTextField(port, { port = it.filter(Char::isDigit) }, label = { Text("Pairing port") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    supportingText = { Text("Differs from the connection port") })
                OutlinedTextField(code, { code = it.filter(Char::isDigit).take(6) }, label = { Text("Pairing code") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onPair(host, port.validPort()!!, code) }) { Text("Pair") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Small pill in the Devices header that opens the donation page (Links.DONATE_URL). */
@Composable
private fun DonateButton() {
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    OutlinedButton(
        onClick = {
            val url = Links.DONATE_URL
            if (url.isBlank()) {
                Toast.makeText(context, "Donation link is not set yet.", Toast.LENGTH_SHORT).show()
            } else {
                runCatching { uriHandler.openUri(url) }
                    .onFailure { Toast.makeText(context, "No browser found to open the link.", Toast.LENGTH_SHORT).show() }
            }
        },
        border = BorderStroke(1.dp, NexoraTokens.Success),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Icon(
            Icons.Rounded.Favorite,
            contentDescription = null,
            tint = NexoraTokens.Success,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text("Donate", color = NexoraTokens.TextPrimary)
    }
}

