package im.wisnu.nexora.core.adb

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * App-owned ADB boundary. Features depend on this package only, never on a
 * third-party ADB library, so the implementation can be replaced.
 *
 * Each [connect] returns an isolated [AdbSession] per target, so several
 * saved devices can be connected without commands leaking between them.
 */
interface AdbClient {

    /** Wireless Debugging pairing. [endpoint] is the pairing port, not the connect port. */
    suspend fun pair(
        endpoint: AdbEndpoint,
        pairingCode: String,
        timeout: Duration = DEFAULT_TIMEOUT,
    ): AdbResult<Unit>

    /**
     * Opens an authenticated transport. Legacy TCP and TLS are negotiated by the target.
     *
     * If the target does not yet trust this phone's key, the public key is sent so the
     * target shows its "Allow USB debugging?" prompt. [onAwaitingApproval] is called
     * then, and the call waits up to [approvalTimeout] for the user to accept.
     */
    suspend fun connect(
        endpoint: AdbEndpoint,
        timeout: Duration = DEFAULT_TIMEOUT,
        approvalTimeout: Duration = APPROVAL_TIMEOUT,
        onAwaitingApproval: () -> Unit = {},
    ): AdbResult<AdbSession>

    companion object {
        val DEFAULT_TIMEOUT: Duration = 5.seconds
        val APPROVAL_TIMEOUT: Duration = 60.seconds
    }
}

/** One authenticated connection to one target. Reuse it; do not reconnect per key press (REM-08). */
interface AdbSession {
    val endpoint: AdbEndpoint
    val isOpen: Boolean

    /** Runs [command] and collects its output. */
    suspend fun execute(
        command: ShellCommand,
        timeout: Duration = AdbClient.DEFAULT_TIMEOUT,
    ): AdbResult<ShellResult>

    /** Lightweight authenticated round trip, used by foreground validation (CON-12). */
    suspend fun ping(timeout: Duration = AdbClient.DEFAULT_TIMEOUT): AdbResult<Unit>

    /**
     * Long-lived shell for fast fire-and-forget commands such as key events
     * (REM-08). Output is discarded. Commands run in order.
     */
    suspend fun openInteractiveShell(
        timeout: Duration = AdbClient.DEFAULT_TIMEOUT,
    ): AdbResult<InteractiveShell>

    /** ADB sync (push/pull/list). May report [AdbError.Unsupported]. */
    val files: FileTransfer

    suspend fun disconnect()
}

interface InteractiveShell {
    val isOpen: Boolean
    suspend fun send(command: ShellCommand): AdbResult<Unit>
    fun close()
}

/** One directory entry from ADB sync LIST. */
data class RemoteFile(
    val name: String,
    val mode: Int,
    val size: Long,
    val mtimeSeconds: Long,
) {
    val isDirectory: Boolean get() = mode and S_IFMT == S_IFDIR
    val isSymlink: Boolean get() = mode and S_IFMT == S_IFLNK

    private companion object {
        const val S_IFMT = 0xF000
        const val S_IFDIR = 0x4000
        const val S_IFLNK = 0xA000
    }
}

/**
 * File push/pull. A transfer fails with [AdbError.Timeout] only if no bytes
 * move for [idleTimeout]; cancel the calling coroutine to stop it (FIL-05).
 * [onProgress] gets the cumulative byte count and runs on an I/O thread.
 */
interface FileTransfer {
    suspend fun push(
        source: java.io.InputStream,
        remotePath: String,
        mode: Int = 420, // 0644
        idleTimeout: Duration = DEFAULT_IDLE_TIMEOUT,
        onProgress: (bytes: Long) -> Unit = {},
    ): AdbResult<Unit>

    suspend fun pull(
        remotePath: String,
        sink: java.io.OutputStream,
        idleTimeout: Duration = DEFAULT_IDLE_TIMEOUT,
        onProgress: (bytes: Long) -> Unit = {},
    ): AdbResult<Unit>

    /**
     * Lists [remotePath]. An unreadable directory comes back empty: the sync
     * protocol does not distinguish it from an empty one (FIL-07).
     */
    suspend fun list(
        remotePath: String,
        timeout: Duration = AdbClient.DEFAULT_TIMEOUT,
    ): AdbResult<List<RemoteFile>>

    companion object {
        val DEFAULT_IDLE_TIMEOUT: Duration = 15.seconds
    }
}
