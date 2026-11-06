package im.wisnu.nexora.core.adb.libadb

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** A transfer made no progress for longer than its idle timeout. */
internal class IdleTimeoutException : IOException("no progress before idle timeout")

/**
 * Runs blocking libadb calls with deadlines.
 *
 * libadb-android opens sockets without a connect timeout and some reads can
 * block indefinitely (upstream issues #33, #35, #36). Thread interruption does
 * not unblock socket I/O, so on timeout, failure or cancellation the caller
 * returns at once and [abandon] runs to close the underlying connection or
 * stream, both now and again when the stuck call finally returns.
 */
internal object BoundedBlocking {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Fails with a timeout if [block] takes longer than [timeout] in total. */
    suspend fun <T> run(timeout: Duration, abandon: () -> Unit, block: () -> T): T {
        val call = scope.async { block() }
        try {
            return withTimeout(timeout) { call.await() }
        } catch (e: Throwable) {
            abandonOnFailure(call, abandon)
            throw e
        }
    }

    /**
     * For long transfers: fails with [IdleTimeoutException] only when [block]
     * stops calling `touch()` for longer than [idle].
     */
    suspend fun <T> runIdle(idle: Duration, abandon: () -> Unit, block: (touch: () -> Unit) -> T): T {
        val last = AtomicLong(System.nanoTime())
        val call = scope.async { block { last.set(System.nanoTime()) } }
        try {
            while (!call.isCompleted) {
                if (System.nanoTime() - last.get() > idle.inWholeNanoseconds) throw IdleTimeoutException()
                withTimeoutOrNull(POLL) { call.join() }
            }
            return call.await()
        } catch (e: Throwable) {
            abandonOnFailure(call, abandon)
            throw e
        }
    }

    private fun abandonOnFailure(call: Deferred<*>, abandon: () -> Unit) {
        if (call.isCompleted && !call.isCancelled) {
            // The block itself failed; still release the stream it used.
            runCatching(abandon)
            return
        }
        call.cancel()
        call.invokeOnCompletion { runCatching(abandon) }
        scope.launch { runCatching(abandon) }
    }

    private val POLL = 250.milliseconds
}
