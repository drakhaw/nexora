package im.wisnu.nexora.feature.remote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import im.wisnu.nexora.core.adb.AdbResult
import im.wisnu.nexora.core.adb.ConnectionState
import im.wisnu.nexora.domain.device.DeviceConnections
import im.wisnu.nexora.domain.device.SelectedDevice
import im.wisnu.nexora.domain.remote.RemoteController
import im.wisnu.nexora.domain.remote.RemoteKey
import im.wisnu.nexora.feature.devices.describe
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RemoteViewModel @Inject constructor(
    connections: DeviceConnections,
    private val remote: RemoteController,
) : ViewModel() {

    val selected: StateFlow<SelectedDevice> = connections.selected
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SelectedDevice(null, ConnectionState.Disconnected))

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun press(key: RemoteKey, longPress: Boolean) {
        viewModelScope.launch { report(remote.press(key, longPress)) }
    }

    fun type(text: String, onSent: () -> Unit) {
        viewModelScope.launch {
            val result = remote.type(text)
            report(result)
            if (result is AdbResult.Ok) onSent()
        }
    }

    fun dismiss() {
        _message.value = null
    }

    private fun report(result: AdbResult<Unit>) {
        if (result is AdbResult.Err) _message.value = result.error.describe()
    }
}
