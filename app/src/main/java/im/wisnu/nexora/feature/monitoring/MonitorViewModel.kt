package im.wisnu.nexora.feature.monitoring

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import im.wisnu.nexora.core.adb.AdbResult
import im.wisnu.nexora.core.adb.ConnectionState
import im.wisnu.nexora.core.adb.isUsable
import im.wisnu.nexora.domain.device.DeviceConnections
import im.wisnu.nexora.domain.device.SelectedDevice
import im.wisnu.nexora.domain.monitoring.DeviceInfo
import im.wisnu.nexora.domain.monitoring.DeviceInfoReader
import im.wisnu.nexora.domain.monitoring.Metrics
import im.wisnu.nexora.domain.monitoring.MetricsReader
import im.wisnu.nexora.feature.devices.describe
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MonitorUiState(
    val loading: Boolean = false,
    val info: DeviceInfo? = null,
    val metrics: Metrics? = null,
    val message: String? = null,
)

/** Snapshot monitoring (MON). No background polling: the user refreshes. */
@HiltViewModel
class MonitorViewModel @Inject constructor(
    private val connections: DeviceConnections,
    private val infoReader: DeviceInfoReader,
    private val metricsReader: MetricsReader,
) : ViewModel() {

    val selected: StateFlow<SelectedDevice> = connections.selected
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SelectedDevice(null, ConnectionState.Disconnected))

    private val _state = MutableStateFlow(MonitorUiState())
    val state: StateFlow<MonitorUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            connections.selected.distinctUntilChangedBy { it.device?.id to it.state.isUsable }.collect {
                _state.value = MonitorUiState()
                if (it.state.isUsable) refresh()
            }
        }
    }

    fun refresh() {
        if (_state.value.loading) return
        viewModelScope.launch {
            val session = connections.selectedSession()?.second ?: return@launch
            _state.update { it.copy(loading = true, message = null) }
            val info = infoReader.read(session)
            val metrics = metricsReader.read(session)
            _state.update {
                it.copy(
                    loading = false,
                    info = (info as? AdbResult.Ok)?.value ?: it.info,
                    metrics = (metrics as? AdbResult.Ok)?.value ?: it.metrics,
                    message = (metrics as? AdbResult.Err)?.error?.describe() ?: (info as? AdbResult.Err)?.error?.describe(),
                )
            }
        }
    }

    fun dismiss() = _state.update { it.copy(message = null) }
}
