package im.wisnu.nexora.core.adb.libadb

import im.wisnu.nexora.core.adb.AdbClient
import im.wisnu.nexora.core.adb.AdbEndpoint
import im.wisnu.nexora.core.adb.AdbError
import im.wisnu.nexora.core.adb.AdbResult
import im.wisnu.nexora.core.adb.AdbSession
import im.wisnu.nexora.core.security.HostIdentityStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration

/** [AdbClient] backed by libadb-android. The only place outside this package that sees libadb is DI. */
@Singleton
class LibAdbClient @Inject constructor(
    private val identityStore: HostIdentityStore,
) : AdbClient {

    override suspend fun pair(
        endpoint: AdbEndpoint,
        pairingCode: String,
        timeout: Duration,
    ): AdbResult<Unit> {
        if (pairingCode.isBlank()) return AdbResult.Err(AdbError.PairingFailed)
        val manager = LibAdbConnectionManager(identityStore.get(), timeout.inWholeMilliseconds)
        return adbCall(duringPairing = true) {
            val paired = BoundedBlocking.run(timeout, abandon = { manager.close() }) {
                try {
                    manager.pair(endpoint.host, endpoint.port, pairingCode)
                } finally {
                    runCatching { manager.close() }
                }
            }
            if (paired) AdbResult.Ok(Unit) else AdbResult.Err(AdbError.PairingFailed)
        }
    }

    override suspend fun connect(endpoint: AdbEndpoint, timeout: Duration): AdbResult<AdbSession> {
        val manager = LibAdbConnectionManager(identityStore.get(), timeout.inWholeMilliseconds)
        return adbCall {
            val connected = BoundedBlocking.run(timeout, abandon = { manager.close() }) {
                manager.connect(endpoint.host, endpoint.port)
            }
            if (connected) {
                AdbResult.Ok(LibAdbSession(endpoint, manager))
            } else {
                runCatching { manager.close() }
                AdbResult.Err(AdbError.Timeout)
            }
        }
    }
}

/** Converts exceptions to [AdbResult.Err]; caller cancellation still propagates. */
internal suspend fun <T> adbCall(
    duringPairing: Boolean = false,
    block: suspend () -> AdbResult<T>,
): AdbResult<T> = try {
    block()
} catch (e: TimeoutCancellationException) {
    AdbResult.Err(AdbError.Timeout)
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    AdbResult.Err(e.toAdbError(duringPairing))
}
