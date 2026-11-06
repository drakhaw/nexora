package im.wisnu.nexora.core.adb

data class AdbEndpoint(val host: String, val port: Int) {
    init {
        require(host.isNotBlank()) { "host is blank" }
        require(port in 1..65535) { "port out of range" }
    }

    override fun toString(): String = "$host:$port"
}

sealed interface AdbResult<out T> {
    data class Ok<out T>(val value: T) : AdbResult<T>
    data class Err(val error: AdbError) : AdbResult<Nothing>
}

inline fun <T, R> AdbResult<T>.map(transform: (T) -> R): AdbResult<R> = when (this) {
    is AdbResult.Ok -> AdbResult.Ok(transform(value))
    is AdbResult.Err -> this
}

fun <T> AdbResult<T>.getOrNull(): T? = (this as? AdbResult.Ok)?.value

/**
 * Structured ADB failures (design §8). [detail] is diagnostic only and must
 * never contain pairing codes, key material, or shell output.
 */
sealed interface AdbError {
    data object NetworkUnreachable : AdbError
    data object ConnectionRefused : AdbError
    data object Timeout : AdbError
    data object PairingFailed : AdbError
    data object PairingRequired : AdbError
    data object Unauthorized : AdbError
    data object Offline : AdbError
    data class Unsupported(val feature: String) : AdbError
    data class PermissionDenied(val path: String?) : AdbError
    data class ShellFailure(val detail: String) : AdbError
    data class TransferFailure(val detail: String) : AdbError
    data class Unknown(val detail: String) : AdbError
}

/**
 * Shell output. Shell v1 cannot separate stderr or report an exit code, so
 * both are null when the transport is v1.
 */
data class ShellResult(
    val stdout: String,
    val stderr: String?,
    val exitCode: Int?,
)
