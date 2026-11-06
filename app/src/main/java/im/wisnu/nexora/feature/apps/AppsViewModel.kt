package im.wisnu.nexora.feature.apps

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import im.wisnu.nexora.core.adb.AdbError
import im.wisnu.nexora.core.adb.AdbResult
import im.wisnu.nexora.core.adb.AdbSession
import im.wisnu.nexora.core.adb.ConnectionState
import im.wisnu.nexora.core.adb.isUsable
import im.wisnu.nexora.domain.apps.AppDetails
import im.wisnu.nexora.domain.apps.AppManager
import im.wisnu.nexora.domain.apps.InstalledApp
import im.wisnu.nexora.domain.device.DeviceConnections
import im.wisnu.nexora.domain.device.SelectedDevice
import im.wisnu.nexora.feature.devices.describe
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class AppsUiState(
    val loading: Boolean = false,
    val apps: List<InstalledApp> = emptyList(),
    val loadedOnce: Boolean = false,
    val details: AppDetails? = null,
    val installProgress: Long? = null,
    val message: String? = null,
)

@HiltViewModel
class AppsViewModel @Inject constructor(
    private val connections: DeviceConnections,
    private val apps: AppManager,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    val selected: StateFlow<SelectedDevice> = connections.selected
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SelectedDevice(null, ConnectionState.Disconnected))

    private val _state = MutableStateFlow(AppsUiState())
    val state: StateFlow<AppsUiState> = _state.asStateFlow()

    private var installJob: Job? = null

    init {
        // Reload whenever the selected device connects; clear on switch so lists never mix targets.
        viewModelScope.launch {
            connections.selected.distinctUntilChangedBy { it.device?.id to it.state.isUsable }.collect {
                _state.value = AppsUiState()
                if (it.state.isUsable) refresh()
            }
        }
    }

    fun refresh() = withSession { session ->
        _state.update { it.copy(loading = true) }
        when (val r = apps.list(session)) {
            is AdbResult.Ok -> _state.update { it.copy(loading = false, apps = r.value, loadedOnce = true) }
            is AdbResult.Err -> fail(r.error)
        }
    }

    fun openDetails(pkg: String) = withSession { session ->
        _state.update { it.copy(loading = true) }
        when (val r = apps.details(session, pkg)) {
            is AdbResult.Ok -> _state.update { it.copy(loading = false, details = r.value) }
            is AdbResult.Err -> fail(r.error)
        }
    }

    fun closeDetails() = _state.update { it.copy(details = null) }

    fun launchApp(pkg: String) = action("Launched $pkg") { apps.launch(it, pkg) }

    fun forceStop(pkg: String) = action("Stopped $pkg") { apps.forceStop(it, pkg) }

    fun uninstall(pkg: String) = action("Uninstalled $pkg", reload = true) { apps.uninstall(it, pkg) }

    fun install(uri: Uri) {
        installJob = withSession { session ->
            _state.update { it.copy(installProgress = 0, message = null) }
            val result = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    apps.installApk(session, input) { bytes -> _state.update { it.copy(installProgress = bytes) } }
                } ?: AdbResult.Err(AdbError.TransferFailure("could not open the selected file"))
            }
            _state.update {
                it.copy(
                    installProgress = null,
                    message = if (result is AdbResult.Ok) "APK installed." else (result as AdbResult.Err).error.describe(),
                )
            }
            if (result is AdbResult.Ok) refresh()
        }
    }

    fun cancelInstall() {
        installJob?.cancel()
        _state.update { it.copy(installProgress = null, message = "Install cancelled.") }
    }

    fun dismiss() = _state.update { it.copy(message = null) }

    private fun action(success: String, reload: Boolean = false, op: suspend (AdbSession) -> AdbResult<Unit>) =
        withSession { session ->
            _state.update { it.copy(loading = true) }
            when (val r = op(session)) {
                is AdbResult.Ok -> {
                    _state.update { it.copy(loading = false, details = null, message = success) }
                    if (reload) refresh()
                }
                is AdbResult.Err -> fail(r.error)
            }
        }

    private fun fail(error: AdbError) = _state.update { it.copy(loading = false, message = error.describe()) }

    private fun withSession(block: suspend (AdbSession) -> Unit): Job = viewModelScope.launch {
        val session = connections.selectedSession()?.second
        if (session == null) fail(AdbError.Offline) else block(session)
    }
}
