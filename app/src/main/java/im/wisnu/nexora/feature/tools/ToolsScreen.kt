package im.wisnu.nexora.feature.tools

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import android.content.ClipData
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import im.wisnu.nexora.core.adb.ConnectionState
import im.wisnu.nexora.core.adb.RemoteFile
import im.wisnu.nexora.domain.device.DeviceConnections
import im.wisnu.nexora.domain.device.SelectedDevice
import im.wisnu.nexora.feature.common.MessageCard
import im.wisnu.nexora.feature.common.RequireConnected
import im.wisnu.nexora.ui.theme.NexoraTokens
import dagger.hilt.android.lifecycle.HiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ToolsViewModel @Inject constructor(connections: DeviceConnections) : ViewModel() {
    val selected: StateFlow<SelectedDevice> = connections.selected
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SelectedDevice(null, ConnectionState.Disconnected))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(vm: ToolsViewModel = hiltViewModel()) {
    val selected by vm.selected.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(0) }
    RequireConnected(selected) {
        Column(Modifier.fillMaxSize()) {
            PrimaryTabRow(selectedTabIndex = tab) {
                listOf("Terminal", "Files", "Screenshot").forEachIndexed { i, label ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label) })
                }
            }
            when (tab) {
                0 -> TerminalTab()
                1 -> FilesTab()
                else -> ScreenshotTab()
            }
        }
    }
}

@Composable
private fun TerminalTab(vm: TerminalViewModel = hiltViewModel()) {
    val entries by vm.entries.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val saved by vm.saved.collectAsStateWithLifecycle()
    val acknowledged by vm.warningAcknowledged.collectAsStateWithLifecycle()
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val running = entries.lastOrNull()?.running == true

    LaunchedEffect(entries.size) { if (entries.isNotEmpty()) listState.animateScrollToItem(entries.size - 1) }

    if (acknowledged == false) {
        // Shell is not a sandbox (TER-08).
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Shell commands are powerful") },
            text = {
                Text(
                    "Commands run on the TV with ADB shell privileges (or root on rooted devices). " +
                        "They can delete data, change settings or make the device unusable. Only run commands you understand.",
                )
            },
            confirmButton = { TextButton(onClick = { vm.acknowledge() }) { Text("I understand") } },
        )
    }

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Not a sandbox: commands run with shell privileges on the TV.",
            style = MaterialTheme.typography.labelMedium,
            color = NexoraTokens.Warning,
        )
        if (saved.isNotEmpty() || history.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                saved.forEach { cmd -> AssistChip(onClick = { input = cmd }, label = { Text("★ $cmd", maxLines = 1) }) }
                history.take(10).filter { it !in saved }.forEach { cmd ->
                    AssistChip(onClick = { input = cmd }, label = { Text(cmd, maxLines = 1) })
                }
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState) {
            items(entries) { e ->
                Column(Modifier.padding(vertical = 6.dp)) {
                    Text("${e.deviceName} $ ${e.command}", fontFamily = FontFamily.Monospace, color = NexoraTokens.Accent)
                    SelectionContainer {
                        Column {
                            if (e.stdout.isNotEmpty()) Text(e.stdout.trimEnd(), fontFamily = FontFamily.Monospace)
                            e.stderr?.takeIf { it.isNotBlank() }?.let {
                                Text(it.trimEnd(), fontFamily = FontFamily.Monospace, color = NexoraTokens.Error)
                            }
                        }
                    }
                    e.error?.let { Text(it, color = NexoraTokens.Error) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val status = when {
                            e.running -> "Running…"
                            e.exitCode != null -> "exit ${e.exitCode}"
                            e.error == null -> "done (exit code not available on this device)"
                            else -> ""
                        }
                        Text(status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        if (!e.running && (e.stdout.isNotEmpty() || !e.stderr.isNullOrEmpty())) {
                            TextButton(onClick = {
                                val text = e.stdout + e.stderr.orEmpty()
                                scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Terminal output", text))) }
                            }) { Text("Copy") }
                        }
                        TextButton(onClick = { vm.save(e.command) }) { Text("Save") }
                    }
                }
                HorizontalDivider()
            }
        }
        if (running) LinearProgressIndicator(Modifier.fillMaxWidth())
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Shell command") },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onGo = { vm.run(input); input = "" }),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.run(input); input = "" }, enabled = input.isNotBlank() && !running && acknowledged == true) { Text("Run") }
            OutlinedButton(onClick = vm::cancel, enabled = running) { Text("Cancel") }
            TextButton(onClick = vm::clear) { Text("Clear") }
        }
    }
}

@Composable
private fun FilesTab(vm: FilesViewModel = hiltViewModel()) {
    val ui by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var pendingDownload by remember { mutableStateOf<RemoteFile?>(null) }
    val createDoc = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val entry = pendingDownload
        pendingDownload = null
        if (uri != null && entry != null) vm.download(entry, uri)
    }
    val openDoc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.upload(uri)
    }

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = vm::up, enabled = ui.path != "/") { Text("Up") }
            Text(ui.path, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f), maxLines = 2)
            Button(onClick = { openDoc.launch(arrayOf("*/*")) }, enabled = ui.transfer == null) { Text("Upload") }
        }
        ui.transfer?.let { t ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${t.label}: ${Formatter.formatShortFileSize(context, t.bytes)}", Modifier.weight(1f))
                TextButton(onClick = vm::cancelTransfer) { Text("Cancel") }
            }
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        MessageCard(ui.message, vm::dismiss)
        if (ui.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (ui.loaded && ui.entries.isEmpty() && !ui.loading) {
            Text(
                "Empty, or not readable by the ADB shell user.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn {
            items(ui.entries, key = { it.name }) { entry ->
                val navigable = entry.isDirectory || entry.isSymlink
                Row(
                    Modifier.fillMaxWidth()
                        .clickable {
                            if (navigable) vm.enter(entry) else {
                                pendingDownload = entry
                                createDoc.launch(entry.name)
                            }
                        }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(if (entry.isDirectory) "▸ " else if (entry.isSymlink) "↪ " else "  ", fontFamily = FontFamily.Monospace)
                    Text(entry.name, Modifier.weight(1f))
                    if (!navigable) {
                        Text(
                            Formatter.formatShortFileSize(context, entry.size),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                HorizontalDivider()
            }
        }
        Text(
            "Tap a file to download it to the phone.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ScreenshotTab(vm: ScreenshotViewModel = hiltViewModel()) {
    val ui by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val createDoc = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        if (uri != null) vm.save(uri)
    }

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.capture() }, enabled = !ui.busy) { Text("Capture") }
            OutlinedButton(onClick = { createDoc.launch(vm.suggestedName()) }, enabled = ui.shot != null) { Text("Save") }
            OutlinedButton(
                onClick = { scope.launch { vm.shareIntent()?.let(context::startActivity) } },
                enabled = ui.shot != null,
            ) { Text("Share") }
        }
        if (ui.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        MessageCard(ui.message, vm::dismiss)
        ui.shot?.let { shot ->
            Text(
                "${shot.deviceName} · " + DateUtils.formatDateTime(
                    context, shot.takenAt, DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE,
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ui.bitmap?.let { bmp ->
            Image(
                bmp.asImageBitmap(),
                contentDescription = "Screenshot of ${ui.shot?.deviceName}",
                modifier = Modifier.fillMaxWidth(),
                contentScale = ContentScale.FillWidth,
            )
        } ?: Text("No screenshot yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
