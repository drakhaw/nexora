package im.wisnu.nexora.core.adb.libadb

import im.wisnu.nexora.core.adb.AdbEndpoint
import im.wisnu.nexora.core.adb.AdbError
import im.wisnu.nexora.core.adb.AdbResult
import im.wisnu.nexora.core.adb.AdbSession
import im.wisnu.nexora.core.adb.FileTransfer
import im.wisnu.nexora.core.adb.InteractiveShell
import im.wisnu.nexora.core.adb.RemoteFile
import im.wisnu.nexora.core.adb.ShellCommand
import im.wisnu.nexora.core.adb.ShellResult
import im.wisnu.nexora.core.adb.protocol.MaxDataOutputStream
import im.wisnu.nexora.core.adb.protocol.ShellV2
import im.wisnu.nexora.core.adb.protocol.SyncClient
import im.wisnu.nexora.core.adb.protocol.SyncFailException
import io.github.muntashirakon.adb.AdbStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

internal class LibAdbSession(
    override val endpoint: AdbEndpoint,
    private val manager: LibAdbConnectionManager,
) : AdbSession {

    override val isOpen: Boolean
        get() = runCatching { manager.isConnected }.getOrDefault(false)

    /** Shell v2 support, probed once per session. null = not probed yet. */
    @Volatile private var shellV2: Boolean? = null
    private val probeLock = Mutex()

    override suspend fun execute(command: ShellCommand, timeout: Duration): AdbResult<ShellResult> {
        if (!isOpen) return AdbResult.Err(AdbError.Offline)
        val v2 = when (val probe = shellV2Supported(timeout)) {
            is AdbResult.Ok -> probe.value
            is AdbResult.Err -> return probe
        }
        return if (v2) executeV2(command.script, timeout) else executeV1(command.script, timeout)
    }

    override suspend fun ping(timeout: Duration): AdbResult<Unit> =
        when (val result = execute(ShellCommand.of("echo", PING_TOKEN), timeout)) {
            is AdbResult.Ok ->
                if (result.value.stdout.trim() == PING_TOKEN) AdbResult.Ok(Unit)
                else AdbResult.Err(AdbError.ShellFailure("unexpected ping reply"))
            is AdbResult.Err -> result
        }

    override val files: FileTransfer = SyncFileTransfer()

    override suspend fun openInteractiveShell(timeout: Duration): AdbResult<InteractiveShell> {
        if (!isOpen) return AdbResult.Err(AdbError.Offline)
        return adbCall {
            val stream = AtomicReference<AdbStream?>()
            val opened = BoundedBlocking.run(timeout, abandon = { stream.get()?.close() }) {
                openStream("shell:").also(stream::set)
            }
            AdbResult.Ok(LibAdbInteractiveShell(opened, writer(opened)))
        }
    }

    /**
     * Interactive v1 shell (works on every adbd). Commands are written as
     * lines; output is drained on a daemon thread and discarded so the
     * stream never stalls.
     */
    private class LibAdbInteractiveShell(
        private val stream: AdbStream,
        private val out: OutputStream,
    ) : InteractiveShell {

        init {
            Thread({
                val input = stream.openInputStream()
                val sink = ByteArray(4096)
                runCatching { while (input.read(sink) >= 0) Unit }
            }, "adb-shell-drain").apply { isDaemon = true }.start()
        }

        override val isOpen: Boolean get() = !stream.isClosed

        override suspend fun send(command: ShellCommand): AdbResult<Unit> {
            if (!isOpen) return AdbResult.Err(AdbError.Offline)
            return adbCall {
                BoundedBlocking.run(SEND_TIMEOUT, abandon = { close() }) {
                    out.write((command.script + "\n").toByteArray(Charsets.UTF_8))
                    out.flush()
                }
                AdbResult.Ok(Unit)
            }
        }

        override fun close() {
            runCatching { stream.close() }
        }
    }

    override suspend fun disconnect() {
        withContext(Dispatchers.IO) { runCatching { manager.close() } }
    }

    /**
     * Probes with a harmless command instead of falling back after a real one,
     * so a command is never run twice. Pre-Nougat adbd rejects the
     * `shell,v2` service, which surfaces as an open failure or an empty stream.
     */
    private suspend fun shellV2Supported(timeout: Duration): AdbResult<Boolean> {
        shellV2?.let { return AdbResult.Ok(it) }
        return probeLock.withLock {
            shellV2?.let { return@withLock AdbResult.Ok(it) }
            val supported = when (val r = executeV2(ShellCommand.of("echo", PING_TOKEN).script, timeout)) {
                is AdbResult.Ok -> r.value.exitCode != null && r.value.stdout.trim() == PING_TOKEN
                // Timeouts and lost connections say nothing about v2; do not cache them.
                is AdbResult.Err -> if (r.error == AdbError.Timeout || !isOpen) return@withLock r else false
            }
            shellV2 = supported
            AdbResult.Ok(supported)
        }
    }

    /** Shell v2: separate stdout/stderr and an exit code. */
    private suspend fun executeV2(script: String, timeout: Duration): AdbResult<ShellResult> = adbCall {
        val stream = AtomicReference<AdbStream?>()
        val out = BoundedBlocking.run(timeout, abandon = { stream.get()?.close() }) {
            openStream(ShellV2.service(script)).also(stream::set).use { s ->
                writer(s).apply { write(ShellV2.closeStdinPacket()); flush() }
                ShellV2.read(s.openInputStream(), MAX_OUTPUT_BYTES)
            }
        }
        if (out.exitCode == null && out.packets == 0) {
            AdbResult.Err(AdbError.ShellFailure("shell v2 stream closed without reply"))
        } else {
            AdbResult.Ok(ShellResult(out.stdout.decodeToString(), out.stderr.decodeToString(), out.exitCode))
        }
    }

    /** Shell v1 fallback: stdout and stderr merged, no exit code. */
    private suspend fun executeV1(script: String, timeout: Duration): AdbResult<ShellResult> = adbCall {
        val stream = AtomicReference<AdbStream?>()
        val output = BoundedBlocking.run(timeout, abandon = { stream.get()?.close() }) {
            openStream("shell:$script").also(stream::set).use(::readCappedV1)
        }
        AdbResult.Ok(ShellResult(stdout = output, stderr = null, exitCode = null))
    }

    private fun openStream(service: String): AdbStream = manager.openStream(service)

    /** Writes at most one ADB payload per AdbStream.write call (see [MaxDataOutputStream]). */
    private fun writer(stream: AdbStream): OutputStream {
        val maxData = manager.adbConnection?.maxData ?: throw IOException("not connected")
        return MaxDataOutputStream(stream.openOutputStream(), maxData)
    }

    /** Reads to EOF, capped so a malformed or endless reply cannot exhaust memory (SEC-10). */
    private fun readCappedV1(stream: AdbStream): String {
        val out = ByteArrayOutputStream()
        val input = stream.openInputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = try {
                input.read(buffer)
            } catch (e: IOException) {
                // libadb throws "Stream closed." instead of EOF after a peer CLSE (upstream #35).
                if (stream.isClosed) -1 else throw e
            }
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > MAX_OUTPUT_BYTES) throw IOException("shell output exceeds limit")
        }
        return out.toString(Charsets.UTF_8.name())
    }

    /** ADB sync over one `sync:` stream per transfer. */
    private inner class SyncFileTransfer : FileTransfer {

        override suspend fun push(
            source: InputStream,
            remotePath: String,
            mode: Int,
            idleTimeout: Duration,
            onProgress: (bytes: Long) -> Unit,
        ): AdbResult<Unit> = transfer(remotePath, idleTimeout) { sync, touch ->
            val mtime = (System.currentTimeMillis() / 1000).toInt()
            sync.send(source, remotePath, mode, mtime) { touch(); onProgress(it) }
        }

        override suspend fun pull(
            remotePath: String,
            sink: OutputStream,
            idleTimeout: Duration,
            onProgress: (bytes: Long) -> Unit,
        ): AdbResult<Unit> = transfer(remotePath, idleTimeout) { sync, touch ->
            sync.recv(remotePath, sink) { touch(); onProgress(it) }
        }

        override suspend fun list(remotePath: String, timeout: Duration): AdbResult<List<RemoteFile>> =
            transfer(remotePath, timeout) { sync, _ -> sync.list(remotePath) }

        private suspend fun <T> transfer(
            remotePath: String,
            idleTimeout: Duration,
            body: (SyncClient, touch: () -> Unit) -> T,
        ): AdbResult<T> {
            if (!isOpen) return AdbResult.Err(AdbError.Offline)
            if (!remotePath.startsWith("/")) return AdbResult.Err(AdbError.TransferFailure("remote path must be absolute"))
            return adbCall {
                val stream = AtomicReference<AdbStream?>()
                try {
                    val value = BoundedBlocking.runIdle(idleTimeout, abandon = { stream.get()?.close() }) { touch ->
                        openStream("sync:").also(stream::set).use { s ->
                            val sync = SyncClient(s.openInputStream(), writer(s))
                            body(sync, touch).also { runCatching { sync.quit() } }
                        }
                    }
                    AdbResult.Ok(value)
                } catch (e: SyncFailException) {
                    AdbResult.Err(e.toAdbError(remotePath))
                }
            }
        }
    }

    private companion object {
        const val PING_TOKEN = "nexora-ping"
        const val MAX_OUTPUT_BYTES = 4 * 1024 * 1024
        val SEND_TIMEOUT = 3.seconds
    }
}

private fun SyncFailException.toAdbError(path: String): AdbError = when {
    remoteMessage.contains("Permission denied", ignoreCase = true) ||
        remoteMessage.contains("Read-only file system", ignoreCase = true) -> AdbError.PermissionDenied(path)
    else -> AdbError.TransferFailure(remoteMessage.take(200))
}
