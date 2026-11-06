package im.wisnu.nexora.feature.tools

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import im.wisnu.nexora.core.adb.AdbError
import im.wisnu.nexora.core.adb.AdbResult
import im.wisnu.nexora.core.adb.AdbSession
import im.wisnu.nexora.core.adb.ConnectionState
import im.wisnu.nexora.core.adb.isUsable
import im.wisnu.nexora.core.adb.RemoteFile
import im.wisnu.nexora.domain.device.DeviceConnections
import im.wisnu.nexora.feature.devices.describe
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class Transfer(val label: String, val bytes: Long)

data class FilesUiState(
    val path: String = START_PATH,
    val entries: List<RemoteFile> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val transfer: Transfer? = null,
    val message: String? = null,
) {
    companion object {
        const val START_PATH = "/sdcard"
    }
}

/** File browser (FIL-01 to FIL-07) over ADB sync. */
@HiltViewModel
class FilesViewModel @Inject constructor(
    private val connections: DeviceConnections,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(FilesUiState())
    val state: StateFlow<FilesUiState> = _state.asStateFlow()
    private var transferJob: Job? = null

    init {
        viewModelScope.launch {
            connections.selected.distinctUntilChangedBy { it.device?.id to it.state.isUsable }.collect {
                transferJob?.cancel()
                _state.value = FilesUiState()
                if (it.state.isUsable) open(FilesUiState.START_PATH)
            }
        }
    }

    fun open(path: String) = withSession { session ->
        _state.update { it.copy(loading = true, message = null) }
        when (val r = session.files.list(path)) {
            is AdbResult.Ok -> _state.update {
                it.copy(
                    path = path,
                    entries = r.value.sortedWith(compareBy({ !(it.isDirectory || it.isSymlink) }, { it.name.lowercase() })),
                    loading = false,
                    loaded = true,
                )
            }
            is AdbResult.Err -> fail(r.error)
        }
    }

    fun enter(entry: RemoteFile) = open(child(entry.name))

    fun up() {
        val parent = _state.value.path.trimEnd('/').substringBeforeLast('/', "").ifEmpty { "/" }
        open(parent)
    }

    fun child(name: String): String = _state.value.path.trimEnd('/') + "/" + name

    fun download(entry: RemoteFile, target: Uri) = transfer("Downloading ${entry.name}") { session, progress ->
        val remote = child(entry.name)
        withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(target)?.use { out ->
                session.files.pull(remote, out, onProgress = progress)
            } ?: AdbResult.Err(AdbError.TransferFailure("could not write the chosen file"))
        }
    }

    fun upload(source: Uri) = transfer("Uploading") { session, progress ->
        val name = displayName(source)?.takeIf(::isSafeName)
            ?: return@transfer AdbResult.Err(AdbError.TransferFailure("file name not usable on the target"))
        val remote = child(name)
        val result = withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(source)?.use { input ->
                session.files.push(input, remote, onProgress = progress)
            } ?: AdbResult.Err(AdbError.TransferFailure("could not open the selected file"))
        }
        if (result is AdbResult.Ok) open(_state.value.path)
        result
    }

    fun cancelTransfer() {
        transferJob?.cancel()
        _state.update { it.copy(transfer = null, message = "Transfer cancelled.") }
    }

    fun dismiss() = _state.update { it.copy(message = null) }

    private fun transfer(label: String, op: suspend (AdbSession, (Long) -> Unit) -> AdbResult<Unit>) {
        if (_state.value.transfer != null) return
        transferJob = withSession { session ->
            _state.update { it.copy(transfer = Transfer(label, 0), message = null) }
            val result = op(session) { bytes -> _state.update { it.copy(transfer = it.transfer?.copy(bytes = bytes)) } }
            _state.update {
                it.copy(transfer = null, message = if (result is AdbResult.Ok) "$label: done." else (result as AdbResult.Err).error.describe())
            }
        }
    }

    private fun displayName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    private fun isSafeName(name: String) = name.isNotBlank() && '/' !in name && name != "." && name != ".."

    private fun fail(error: AdbError) = _state.update { it.copy(loading = false, message = error.describe()) }

    private fun withSession(block: suspend (AdbSession) -> Unit): Job = viewModelScope.launch {
        val session = connections.selectedSession()?.second
        if (session == null) fail(AdbError.Offline) else block(session)
    }
}
