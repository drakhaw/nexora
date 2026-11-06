package im.wisnu.nexora.feature.monitoring

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import im.wisnu.nexora.feature.common.MessageCard
import im.wisnu.nexora.feature.common.RequireConnected
import im.wisnu.nexora.ui.theme.NexoraTokens

private const val UNAVAILABLE = "Unavailable"

@Composable
fun MonitorScreen(vm: MonitorViewModel = hiltViewModel()) {
    val selected by vm.selected.collectAsStateWithLifecycle()
    val ui by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    fun kb(v: Long) = Formatter.formatShortFileSize(context, v * 1024)

    RequireConnected(selected) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Monitor", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = vm::refresh, enabled = !ui.loading) { Text("Refresh") }
            }
            if (ui.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            MessageCard(ui.message, vm::dismiss)

            val m = ui.metrics
            m?.let {
                Text(
                    "Snapshot collected " + DateUtils.getRelativeTimeSpanString(it.collectedAt) +
                        " · " + DateUtils.formatDateTime(context, it.collectedAt, DateUtils.FORMAT_SHOW_TIME),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Section("Device") {
                val i = ui.info
                Line("Manufacturer", i?.manufacturer ?: UNAVAILABLE)
                Line("Model", i?.model ?: UNAVAILABLE)
                Line("Android", i?.androidVersion?.let { v -> "$v (SDK ${i.sdkInt ?: "?"})" } ?: UNAVAILABLE)
                Line("Build", i?.buildFingerprint ?: UNAVAILABLE)
                Line("Uptime", m?.uptimeSeconds?.let { DateUtils.formatElapsedTime(it) } ?: UNAVAILABLE)
            }
            Section("CPU") {
                Line("Usage (1 s sample)", m?.cpuPercent?.let { "%.0f%%".format(it) } ?: UNAVAILABLE)
                m?.cpuPercent?.let { Meter((it / 100).toFloat()) }
            }
            Section("Memory") {
                val mem = m?.memory
                Line("Used", mem?.let { "${kb(it.totalKb - it.availableKb)} of ${kb(it.totalKb)}" } ?: UNAVAILABLE)
                Line("Available", mem?.let { kb(it.availableKb) } ?: UNAVAILABLE)
                mem?.let { Meter(1f - it.availableKb.toFloat() / it.totalKb) }
            }
            Section("Storage (/data)") {
                val s = m?.storage
                Line("Used", s?.let { "${kb(it.usedKb)} of ${kb(it.totalKb)}" } ?: UNAVAILABLE)
                Line("Free", s?.let { kb(it.availableKb) } ?: UNAVAILABLE)
                s?.takeIf { it.totalKb > 0 }?.let { Meter(it.usedKb.toFloat() / it.totalKb) }
            }
            Section("Battery") {
                val b = m?.battery
                Line("Level", b?.levelPercent?.let { "$it%" } ?: UNAVAILABLE)
                Line("Charging", b?.charging?.let { if (it) "Yes" else "No" } ?: UNAVAILABLE)
                Line("Temperature", b?.temperatureC?.let { "%.1f °C".format(it) } ?: UNAVAILABLE)
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = NexoraTokens.Surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun Line(label: String, value: String) {
    Row {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(1.4f))
    }
}

@Composable
private fun Meter(fraction: Float) {
    LinearProgressIndicator(
        progress = { fraction.coerceIn(0f, 1f) },
        modifier = Modifier.fillMaxWidth(),
        color = NexoraTokens.Accent,
        trackColor = NexoraTokens.SurfaceElevated,
    )
}
