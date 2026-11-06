package im.wisnu.nexora.core.adb.libadb

import im.wisnu.nexora.core.adb.AdbError
import io.github.muntashirakon.adb.AdbAuthenticationFailedException
import io.github.muntashirakon.adb.AdbPairingRequiredException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Maps libadb and socket exceptions to [AdbError]. Only the exception class
 * name is kept as detail, never its message, so nothing sensitive leaks.
 */
internal fun Throwable.toAdbError(duringPairing: Boolean = false): AdbError =
    rootSpecific().mapAdbError(duringPairing)

/**
 * libadb wraps socket failures in a bare IOException (AdbConnection: `new IOException().initCause(th)`).
 * Walk the cause chain to the first exception more specific than plain IOException.
 */
private fun Throwable.rootSpecific(): Throwable {
    var current: Throwable = this
    val seen = HashSet<Throwable>()
    while (current.javaClass == java.io.IOException::class.java && seen.add(current)) {
        current = current.cause ?: return current
    }
    return current
}

private fun Throwable.mapAdbError(duringPairing: Boolean): AdbError = when (this) {
    is AdbPairingRequiredException -> AdbError.PairingRequired
    is AdbAuthenticationFailedException -> AdbError.Unauthorized
    is UnknownHostException, is NoRouteToHostException -> AdbError.NetworkUnreachable
    is ConnectException -> AdbError.ConnectionRefused
    is SocketTimeoutException, is IdleTimeoutException -> AdbError.Timeout
    is SSLException -> if (duringPairing) AdbError.PairingFailed else AdbError.Unknown("SSLException")
    else -> if (duringPairing) AdbError.PairingFailed else AdbError.Unknown(javaClass.simpleName)
}
