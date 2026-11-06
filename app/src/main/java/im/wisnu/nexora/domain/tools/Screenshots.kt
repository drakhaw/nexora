package im.wisnu.nexora.domain.tools

import im.wisnu.nexora.core.adb.AdbError
import im.wisnu.nexora.core.adb.AdbResult
import im.wisnu.nexora.core.adb.AdbSession
import im.wisnu.nexora.core.adb.ShellCommand
import im.wisnu.nexora.core.adb.run
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

class Screenshot(val png: ByteArray, val takenAt: Long, val deviceName: String)

/**
 * On-demand capture (SCR-01). Writes the PNG to a temp file on the target and
 * pulls it with ADB sync, which avoids PTY newline mangling of binary stdout
 * on older shells, then deletes the temp file.
 */
class ScreenshotTaker @Inject constructor() {

    suspend fun capture(session: AdbSession, deviceName: String): AdbResult<Screenshot> {
        val taken = System.currentTimeMillis()
        val cap = session.run(ShellCommand.of("screencap", "-p", TMP), 15.seconds)
        if (cap is AdbResult.Err) return cap
        return try {
            val buffer = ByteArrayOutputStream()
            when (val pulled = session.files.pull(TMP, buffer)) {
                is AdbResult.Err -> pulled
                is AdbResult.Ok -> {
                    val png = buffer.toByteArray()
                    if (png.size < 8 || !png.copyOf(8).contentEquals(PNG_MAGIC)) {
                        AdbResult.Err(AdbError.Unsupported("screenshots on this device (no image returned)"))
                    } else {
                        AdbResult.Ok(Screenshot(png, taken, deviceName))
                    }
                }
            }
        } finally {
            session.execute(ShellCommand.of("rm", "-f", TMP))
        }
    }

    private companion object {
        const val TMP = "/data/local/tmp/nexora-screenshot.png"
        val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    }
}
