package im.wisnu.nexora.domain.remote

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import im.wisnu.nexora.core.adb.AdbError
import im.wisnu.nexora.core.adb.AdbResult
import im.wisnu.nexora.core.adb.AdbSession
import im.wisnu.nexora.core.adb.InteractiveShell
import im.wisnu.nexora.core.adb.ShellCommand
import im.wisnu.nexora.domain.device.DeviceConnections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

/**
 * Sends remote actions to the selected device over one reused interactive
 * shell per session, so a key press is a single write, not a new handshake
 * or stream (REM-08). Commands are serialized to keep their order.
 *
 * Keys go to a small injector (src/inputserver) that runs on the target under
 * app_process. `input keyevent` starts a Java VM per key, about 1 s on low-end
 * set-top boxes; the injector starts once and then takes a few ms per key. If
 * the injector cannot be installed or started, keys fall back to `input`.
 */
@Singleton
class RemoteController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val connections: DeviceConnections,
) {
    private val lock = Mutex()
    private var shellSession: AdbSession? = null
    private var shell: InteractiveShell? = null

    /** Injector path verified on [injectorSession], or null when that session uses the fallback. */
    private var injectorSession: AdbSession? = null
    private var injectorPath: String? = null

    private val injectorJar: ByteArray by lazy {
        context.assets.open(INJECTOR_ASSET).use { it.readBytes() }
    }

    /** Installs and starts the injector ahead of the first key press. */
    suspend fun prepare() {
        lock.withLock {
            val (_, session) = connections.selectedSession() ?: return@withLock
            shellFor(session)
        }
    }

    suspend fun press(key: RemoteKey, longPress: Boolean = false): AdbResult<Unit> = lock.withLock {
        val (_, session) = connections.selectedSession() ?: return@withLock AdbResult.Err(AdbError.Offline)
        val first = shellFor(session)
        if (first is AdbResult.Err) return@withLock first
        val result = (first as AdbResult.Ok).value.send(keyCommand(session, key, longPress))
        if (result is AdbResult.Ok) return@withLock result
        // A failed write was not delivered, so one retry on a fresh shell cannot double a key press.
        dropShell()
        when (val retry = shellFor(session)) {
            is AdbResult.Ok -> retry.value.send(keyCommand(session, key, longPress))
            is AdbResult.Err -> retry
        }
    }

    /** Text is rare and not latency-sensitive, so it runs as one-shot `input text` commands. */
    suspend fun type(text: String): AdbResult<Unit> = when (val encoded = InputText.encode(text)) {
        is InputText.Result.Unsupported ->
            AdbResult.Err(AdbError.Unsupported("characters ${encoded.characters.joinToString("")} (ASCII only)"))
        is InputText.Result.Ok -> lock.withLock {
            val (_, session) = connections.selectedSession() ?: return@withLock AdbResult.Err(AdbError.Offline)
            var result: AdbResult<Unit> = AdbResult.Ok(Unit)
            for (chunk in encoded.chunks) {
                result = when (val r = session.execute(ShellCommand.of("input", "text", chunk), TEXT_TIMEOUT)) {
                    is AdbResult.Ok -> AdbResult.Ok(Unit)
                    is AdbResult.Err -> r
                }
                if (result is AdbResult.Err) break
            }
            result
        }
    }

    private fun keyCommand(session: AdbSession, key: RemoteKey, longPress: Boolean): ShellCommand =
        if (injectorSession === session && injectorPath != null) {
            ShellCommand.raw((if (longPress) "l " else "k ") + key.keyCode)
        } else if (longPress) {
            ShellCommand.of("input", "keyevent", "--longpress", key.keyCode.toString())
        } else {
            ShellCommand.of("input", "keyevent", key.keyCode.toString())
        }

    private suspend fun shellFor(session: AdbSession): AdbResult<InteractiveShell> {
        val current = shell
        if (current != null && current.isOpen && shellSession === session) return AdbResult.Ok(current)
        dropShell()
        if (injectorSession !== session) {
            injectorSession = session
            injectorPath = installInjector(session)
        }
        val opened = session.openInteractiveShell()
        if (opened !is AdbResult.Ok) return opened
        val path = injectorPath
        if (path != null) {
            // exec replaces the shell, so every later line goes to the injector's stdin.
            val started = opened.value.send(ShellCommand.of("CLASSPATH=$path", "exec", "app_process", "/", INJECTOR_CLASS))
            if (started is AdbResult.Err) {
                opened.value.close()
                return started
            }
        }
        shell = opened.value
        shellSession = session
        return opened
    }

    /** Pushes the injector if missing and checks it can inject. Returns its path, or null to use `input`. */
    private suspend fun installInjector(session: AdbSession): String? {
        val jar = runCatching { injectorJar }.getOrNull() ?: return null
        val path = "$INJECTOR_DIR/nexora-input-${sha256Prefix(jar)}.jar"
        if (checkInjector(session, path)) return path
        val pushed = session.files.push(jar.inputStream(), path)
        if (pushed is AdbResult.Err) return null
        return path.takeIf { checkInjector(session, it) }
    }

    private suspend fun checkInjector(session: AdbSession, path: String): Boolean {
        val check = ShellCommand.of("CLASSPATH=$path", "app_process", "/", INJECTOR_CLASS, "--check")
        val result = session.execute(check, CHECK_TIMEOUT)
        return result is AdbResult.Ok && result.value.stdout.lineSequence().any { it.trim() == "ok" }
    }

    private suspend fun sha256Prefix(bytes: ByteArray): String = withContext(Dispatchers.Default) {
        MessageDigest.getInstance("SHA-256").digest(bytes).take(6).joinToString("") { "%02x".format(it) }
    }

    private fun dropShell() {
        shell?.close()
        shell = null
        shellSession = null
    }

    private companion object {
        const val INJECTOR_ASSET = "inputserver.jar"
        const val INJECTOR_CLASS = "im.wisnu.nexora.inputserver.InputServer"
        const val INJECTOR_DIR = "/data/local/tmp"
        val CHECK_TIMEOUT = 10.seconds
        val TEXT_TIMEOUT = 10.seconds
    }
}
