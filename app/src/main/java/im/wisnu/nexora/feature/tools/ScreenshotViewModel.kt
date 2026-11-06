package im.wisnu.nexora.feature.tools

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import im.wisnu.nexora.core.adb.AdbResult
import im.wisnu.nexora.domain.device.DeviceConnections
import im.wisnu.nexora.domain.tools.Screenshot
import im.wisnu.nexora.domain.tools.ScreenshotTaker
import im.wisnu.nexora.feature.devices.describe
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

data class ScreenshotUiState(
    val busy: Boolean = false,
    val shot: Screenshot? = null,
    val bitmap: Bitmap? = null,
    val message: String? = null,
)

/** Screenshot capture, preview, save and share (SCR-01 to SCR-04). */
@HiltViewModel
class ScreenshotViewModel @Inject constructor(
    private val connections: DeviceConnections,
    private val taker: ScreenshotTaker,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(ScreenshotUiState())
    val state: StateFlow<ScreenshotUiState> = _state.asStateFlow()

    fun capture() = viewModelScope.launch {
        val target = connections.selectedSession()
        if (target == null) {
            _state.update { it.copy(message = "Not connected.") }
            return@launch
        }
        _state.update { it.copy(busy = true, message = null) }
        when (val r = taker.capture(target.second, target.first.name)) {
            is AdbResult.Ok -> {
                val bitmap = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(r.value.png, 0, r.value.png.size) }
                _state.update {
                    if (bitmap == null) it.copy(busy = false, message = "The image could not be decoded.")
                    else it.copy(busy = false, shot = r.value, bitmap = bitmap)
                }
            }
            is AdbResult.Err -> _state.update { it.copy(busy = false, message = r.error.describe()) }
        }
    }

    fun suggestedName(): String = "screenshot-${_state.value.shot?.takenAt ?: System.currentTimeMillis()}.png"

    fun save(target: Uri) = viewModelScope.launch {
        val shot = _state.value.shot ?: return@launch
        val ok = withContext(Dispatchers.IO) {
            runCatching { context.contentResolver.openOutputStream(target)?.use { it.write(shot.png) } != null }
                .getOrDefault(false)
        }
        _state.update { it.copy(message = if (ok) "Screenshot saved." else "Could not save the screenshot.") }
    }

    /** Builds a share intent for the current screenshot via FileProvider. */
    suspend fun shareIntent(): Intent? {
        val shot = _state.value.shot ?: return null
        val file = withContext(Dispatchers.IO) {
            File(context.cacheDir, "shared").apply { mkdirs() }
                .resolve("screenshot.png").apply { writeBytes(shot.png) }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.let { Intent.createChooser(it, "Share screenshot") }
    }

    fun dismiss() = _state.update { it.copy(message = null) }
}
