package im.wisnu.nexora.feature.apps

import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import im.wisnu.nexora.domain.apps.AppDetails
import im.wisnu.nexora.feature.common.MessageCard
import im.wisnu.nexora.feature.common.RequireConnected

@Composable
fun AppsScreen(vm: AppsViewModel = hiltViewModel()) {
    val selected by vm.selected.collectAsStateWithLifecycle()
    val ui by vm.state.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var showSystem by remember { mutableStateOf(false) }
    var confirmUninstall by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val pickApk = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.install(uri)
    }

    RequireConnected(selected) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Apps", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = vm::refresh) { Text("Refresh") }
            }
            Button(
                onClick = { pickApk.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream")) },
                enabled = ui.installProgress == null,
            ) { Text("Install APK from phone") }
            ui.installProgress?.let { bytes ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Uploading APK: ${Formatter.formatShortFileSize(context, bytes)}", Modifier.weight(1f))
                    TextButton(onClick = vm::cancelInstall) { Text("Cancel") }
                }
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            MessageCard(ui.message, vm::dismiss)
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("Search packages") }, singleLine = true)
            FilterChip(selected = showSystem, onClick = { showSystem = !showSystem }, label = { Text("Show system apps") })
            if (ui.loading) LinearProgressIndicator(Modifier.fillMaxWidth())

            val visible = ui.apps.filter { (showSystem || !it.system) && it.packageName.contains(query.trim(), ignoreCase = true) }
            if (ui.loadedOnce && visible.isEmpty()) {
                Text("No matching packages.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LazyColumn {
                items(visible, key = { it.packageName }) { app ->
                    Column(
                        Modifier.fillMaxWidth().clickable { vm.openDetails(app.packageName) }.padding(vertical = 12.dp),
                    ) {
                        Text(app.packageName, style = MaterialTheme.typography.bodyLarge)
                        if (app.system) {
                            Text("System", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    ui.details?.let { details ->
        DetailsDialog(
            details,
            onDismiss = vm::closeDetails,
            onLaunch = { vm.launchApp(details.packageName) },
            onForceStop = { vm.forceStop(details.packageName) },
            onUninstall = { confirmUninstall = details.packageName },
        )
    }
    confirmUninstall?.let { pkg ->
        // Destructive action needs confirmation (SEC-06).
        AlertDialog(
            onDismissRequest = { confirmUninstall = null },
            title = { Text("Uninstall $pkg?") },
            text = { Text("The app and its data are removed from the TV. System apps usually cannot be removed.") },
            confirmButton = { TextButton(onClick = { vm.uninstall(pkg); confirmUninstall = null }) { Text("Uninstall") } },
            dismissButton = { TextButton(onClick = { confirmUninstall = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun DetailsDialog(
    details: AppDetails,
    onDismiss: () -> Unit,
    onLaunch: () -> Unit,
    onForceStop: () -> Unit,
    onUninstall: () -> Unit,
) {
    fun v(s: String?) = s ?: "Unavailable"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(details.packageName) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Version: ${v(details.versionName)} (${v(details.versionCode)})")
                Text("Installed: ${v(details.firstInstall)}")
                Text("Updated: ${v(details.lastUpdate)}")
                Text("Launch activity: ${details.launchActivity ?: "none found"}")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    OutlinedButton(onClick = onForceStop) { Text("Force stop") }
                    OutlinedButton(onClick = onUninstall) { Text("Uninstall") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onLaunch) { Text("Launch") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
