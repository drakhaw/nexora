package im.wisnu.nexora.core.adb

import kotlin.time.Duration

/**
 * Runs [command] and returns stdout, or [AdbError.ShellFailure] when the exit
 * code is non-zero. With shell v1 there is no exit code, so callers that need
 * certainty must also check the output.
 */
suspend fun AdbSession.run(
    command: ShellCommand,
    timeout: Duration = AdbClient.DEFAULT_TIMEOUT,
): AdbResult<String> = when (val r = execute(command, timeout)) {
    is AdbResult.Err -> r
    is AdbResult.Ok -> {
        val code = r.value.exitCode
        if (code == null || code == 0) {
            AdbResult.Ok(r.value.stdout)
        } else {
            val reason = (r.value.stderr?.takeIf { it.isNotBlank() } ?: r.value.stdout)
                .lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(200)
            AdbResult.Err(AdbError.ShellFailure("exit $code" + (reason?.let { ": $it" } ?: "")))
        }
    }
}
