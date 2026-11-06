package im.wisnu.nexora.feature.tools

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import im.wisnu.nexora.core.adb.AdbResult
import im.wisnu.nexora.core.adb.ShellCommand
import im.wisnu.nexora.data.preferences.TerminalStore
import im.wisnu.nexora.domain.device.DeviceConnections
import im.wisnu.nexora.feature.devices.describe
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

data class TerminalEntry(
    val command: String,
    val deviceName: String,
    val stdout: String = "",
    val stderr: String? = null,
    val exitCode: Int? = null,
    val error: String? = null,
    val running: Boolean = true,
)

/** ADB terminal (TER-01 to TER-08). Commands run as the target's shell user; this is not a sandbox. */
@HiltViewModel
class TerminalViewModel @Inject constructor(
    private val connections: DeviceConnections,
    private val store: TerminalStore,
) : ViewModel() {

    private val _entries = MutableStateFlow<List<TerminalEntry>>(emptyList())
    val entries: StateFlow<List<TerminalEntry>> = _entries.asStateFlow()

    val history = store.history.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val saved = store.saved.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val warningAcknowledged = store.warningAcknowledged
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private var job: Job? = null

    val running: Boolean get() = job?.isActive == true

    fun run(command: String) {
        val text = command.trim()
        if (text.isEmpty() || running) return
        job = viewModelScope.launch {
            store.addHistory(text)
            val target = connections.selectedSession()
            if (target == null) {
                append(TerminalEntry(text, "-", error = "Not connected.", running = false))
                return@launch
            }
            val (device, session) = target
            append(TerminalEntry(text, device.name))
            try {
                val result = session.execute(ShellCommand.raw(text), RUN_TIMEOUT)
                updateLast {
                    when (result) {
                        is AdbResult.Ok -> it.copy(
                            stdout = result.value.stdout,
                            stderr = result.value.stderr,
                            exitCode = result.value.exitCode,
                            running = false,
                        )
                        is AdbResult.Err -> it.copy(error = result.error.describe(), running = false)
                    }
                }
            } catch (e: CancellationException) {
                updateLast { it.copy(error = "Cancelled.", running = false) }
                throw e
            }
        }
    }

    /** Cancels the running command by closing its stream (TER-07). */
    fun cancel() {
        job?.cancel()
    }

    fun clear() = _entries.update { emptyList() }

    fun save(command: String) = viewModelScope.launch { store.save(command.trim()) }

    fun unsave(command: String) = viewModelScope.launch { store.unsave(command) }

    fun acknowledge() = viewModelScope.launch { store.acknowledgeWarning() }

    private fun append(entry: TerminalEntry) = _entries.update { (it + entry).takeLast(MAX_ENTRIES) }

    private fun updateLast(transform: (TerminalEntry) -> TerminalEntry) = _entries.update { list ->
        if (list.isEmpty()) list else list.dropLast(1) + transform(list.last())
    }

    private companion object {
        val RUN_TIMEOUT = 60.seconds
        const val MAX_ENTRIES = 100
    }
}
