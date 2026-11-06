package im.wisnu.nexora.domain.remote

import im.wisnu.nexora.core.adb.AdbError
import im.wisnu.nexora.core.adb.AdbResult
import im.wisnu.nexora.core.adb.AdbSession
import im.wisnu.nexora.core.adb.InteractiveShell
import im.wisnu.nexora.core.adb.ShellCommand
import im.wisnu.nexora.domain.device.DeviceConnections
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sends remote actions to the selected device over one reused interactive
 * shell per session, so a key press is a single write, not a new handshake
 * or stream (REM-08). Commands are serialized to keep their order.
 */
@Singleton
class RemoteController @Inject constructor(
    private val connections: DeviceConnections,
) {
    private val lock = Mutex()
    private var shellSession: AdbSession? = null
    private var shell: InteractiveShell? = null

    suspend fun press(key: RemoteKey, longPress: Boolean = false): AdbResult<Unit> {
        val args = if (longPress) arrayOf("keyevent", "--longpress", key.keyCode.toString())
        else arrayOf("keyevent", key.keyCode.toString())
        return send(ShellCommand.of("input", *args))
    }

    suspend fun type(text: String): AdbResult<Unit> = when (val encoded = InputText.encode(text)) {
        is InputText.Result.Unsupported ->
            AdbResult.Err(AdbError.Unsupported("characters ${encoded.characters.joinToString("")} (ASCII only)"))
        is InputText.Result.Ok -> {
            var result: AdbResult<Unit> = AdbResult.Ok(Unit)
            for (chunk in encoded.chunks) {
                result = send(ShellCommand.of("input", "text", chunk))
                if (result is AdbResult.Err) break
            }
            result
        }
    }

    private suspend fun send(command: ShellCommand): AdbResult<Unit> = lock.withLock {
        val (_, session) = connections.selectedSession() ?: return@withLock AdbResult.Err(AdbError.Offline)
        val first = shellFor(session)
        if (first is AdbResult.Err) return@withLock first
        val result = (first as AdbResult.Ok).value.send(command)
        if (result is AdbResult.Ok) return@withLock result
        // A failed write was not delivered, so one retry on a fresh shell cannot double a key press.
        dropShell()
        when (val retry = shellFor(session)) {
            is AdbResult.Ok -> retry.value.send(command)
            is AdbResult.Err -> retry
        }
    }

    private suspend fun shellFor(session: AdbSession): AdbResult<InteractiveShell> {
        val current = shell
        if (current != null && current.isOpen && shellSession === session) return AdbResult.Ok(current)
        dropShell()
        return session.openInteractiveShell().also {
            if (it is AdbResult.Ok) {
                shell = it.value
                shellSession = session
            }
        }
    }

    private fun dropShell() {
        shell?.close()
        shell = null
        shellSession = null
    }
}
