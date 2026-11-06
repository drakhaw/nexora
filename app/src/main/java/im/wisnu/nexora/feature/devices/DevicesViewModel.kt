package im.wisnu.nexora.feature.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import im.wisnu.nexora.core.adb.AdbClient
import im.wisnu.nexora.core.adb.AdbEndpoint
import im.wisnu.nexora.core.adb.AdbError
import im.wisnu.nexora.core.adb.AdbResult
import im.wisnu.nexora.core.adb.ConnectionState
import im.wisnu.nexora.core.network.LinkType
import im.wisnu.nexora.core.network.NetworkMonitor
import im.wisnu.nexora.domain.device.DeviceConnections
import im.wisnu.nexora.domain.device.DeviceRepository
import im.wisnu.nexora.domain.device.SavedDevice
import im.wisnu.nexora.domain.monitoring.DeviceInfo
import im.wisnu.nexora.domain.monitoring.DeviceInfoReader
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DeviceRow(val device: SavedDevice, val state: ConnectionState, val selected: Boolean)

data class DevicesUiState(
    val rows: List<DeviceRow> = emptyList(),
    val info: DeviceInfo? = null,
    val busy: Boolean = false,
    val message: String? = null,
)

@HiltViewModel
class DevicesViewModel @Inject constructor(
    private val repo: DeviceRepository,
    private val connections: DeviceConnections,
    private val adb: AdbClient,
    private val infoReader: DeviceInfoReader,
    network: NetworkMonitor,
) : ViewModel() {

    val link: StateFlow<LinkType> = network.link

    private val local = MutableStateFlow(DevicesUiState())

    val state: StateFlow<DevicesUiState> = combine(
        repo.devices, repo.selectedId, connections.states, local,
    ) { devices, selectedId, states, l ->
        l.copy(rows = devices.map { DeviceRow(it, states[it.id] ?: ConnectionState.Disconnected, it.id == selectedId) })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DevicesUiState())

    fun add(name: String, host: String, port: Int) = viewModelScope.launch {
        val id = repo.add(name.ifBlank { host }, host, port)
        repo.select(id)
        connections.connect(id)
    }

    fun pair(host: String, pairingPort: Int, code: String) = viewModelScope.launch {
        local.update { it.copy(busy = true, message = null) }
        val result = adb.pair(AdbEndpoint(host, pairingPort), code)
        local.update {
            it.copy(
                busy = false,
                message = when (result) {
                    is AdbResult.Ok -> "Paired. Add the device with its connection port (not the pairing port)."
                    is AdbResult.Err -> result.error.describe()
                },
            )
        }
    }

    fun select(id: Long) = viewModelScope.launch {
        repo.select(id)
        local.update { it.copy(info = null) }
        connections.validateSelected()
    }

    fun connect(id: Long) { connections.connect(id) }

    fun disconnect(id: Long) = viewModelScope.launch { connections.disconnect(id) }

    fun forget(id: Long) = viewModelScope.launch {
        connections.disconnect(id)
        repo.forget(id)
    }

    fun readInfo(id: Long) = viewModelScope.launch {
        val session = connections.session(id)
        if (session == null) {
            local.update { it.copy(message = "Not connected.") }
            return@launch
        }
        local.update { it.copy(busy = true, message = null) }
        when (val result = infoReader.read(session)) {
            is AdbResult.Ok -> local.update { it.copy(busy = false, info = result.value) }
            is AdbResult.Err -> local.update { it.copy(busy = false, message = result.error.describe()) }
        }
    }

    fun rename(id: Long, name: String) = viewModelScope.launch {
        if (name.isNotBlank()) repo.rename(id, name)
    }

    fun setAutoReconnect(id: Long, enabled: Boolean) = viewModelScope.launch { repo.setAutoReconnect(id, enabled) }

    fun dismissMessage() = local.update { it.copy(message = null) }
}

/** Actionable, secret-free error text (CON-09). */
fun AdbError.describe(): String = when (this) {
    AdbError.NetworkUnreachable -> "Target not reachable. Check the IP address and that both devices are on the same network."
    AdbError.ConnectionRefused -> "Connection refused. Check the port and that ADB debugging is enabled on the target."
    AdbError.Timeout -> "Target did not answer in time. It may be off or asleep."
    AdbError.PairingFailed -> "Pairing failed. Check the pairing code and pairing port, then try again."
    AdbError.PairingRequired -> "This target needs Wireless Debugging pairing first."
    AdbError.Unauthorized -> "Target rejected this phone. Accept the debugging prompt on the TV, then reconnect."
    AdbError.Offline -> "Not connected."
    is AdbError.Unsupported -> "Not supported yet: $feature."
    is AdbError.PermissionDenied -> "Permission denied${path?.let { ": $it" } ?: "."}"
    is AdbError.ShellFailure -> "Command failed ($detail)."
    is AdbError.TransferFailure -> "Transfer failed ($detail)."
    is AdbError.Unknown -> "Unexpected error ($detail)."
}
