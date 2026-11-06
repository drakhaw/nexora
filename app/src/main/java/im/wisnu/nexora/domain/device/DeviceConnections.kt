package im.wisnu.nexora.domain.device

import im.wisnu.nexora.core.adb.AdbClient
import im.wisnu.nexora.core.adb.AdbError
import im.wisnu.nexora.core.adb.AdbResult
import im.wisnu.nexora.core.adb.AdbSession
import im.wisnu.nexora.core.adb.ConnectionState
import im.wisnu.nexora.core.adb.toConnectionState
import im.wisnu.nexora.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns one [AdbSession] per saved device and its [ConnectionState].
 *
 * State is keyed by device id, so a slow result for one device can never
 * overwrite another device's state (CON-12 stale-result rule). At most one
 * connect/validate job runs per device; extra requests join the running one.
 */
@Singleton
class DeviceConnections @Inject constructor(
    private val adb: AdbClient,
    private val devices: DeviceRepository,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val _states = MutableStateFlow<Map<Long, ConnectionState>>(emptyMap())
    val states: StateFlow<Map<Long, ConnectionState>> = _states.asStateFlow()

    private val lock = Mutex()
    private val sessions = mutableMapOf<Long, AdbSession>()
    private val jobs = mutableMapOf<Long, Job>()

    /** The selected device and its state, for feature screens. */
    val selected: Flow<SelectedDevice> = combine(devices.devices, devices.selectedId, _states) { list, id, states ->
        val device = list.firstOrNull { it.id == id }
        SelectedDevice(device, device?.let { states[it.id] } ?: ConnectionState.Disconnected)
    }.distinctUntilChanged()

    /** Session of the selected device, or null when nothing is selected or it is not connected. */
    suspend fun selectedSession(): Pair<SavedDevice, AdbSession>? {
        val id = devices.selectedId.first() ?: return null
        val device = devices.get(id) ?: return null
        return session(id)?.let { device to it }
    }

    fun stateOf(id: Long): ConnectionState = _states.value[id] ?: ConnectionState.Disconnected

    suspend fun session(id: Long): AdbSession? = lock.withLock { sessions[id]?.takeIf { it.isOpen } }

    /** User-initiated connect. */
    fun connect(id: Long): Job = launchExclusive(id) { doConnect(id) }

    /** Foreground validation of the selected device (CON-12). No-op when nothing is selected. */
    suspend fun validateSelected(): Job? {
        val id = devices.selectedId.first() ?: return null
        return launchExclusive(id) { doValidate(id) }
    }

    suspend fun disconnect(id: Long) {
        synchronized(jobs) { jobs.remove(id) }?.cancel()
        dropSession(id)
        setState(id, ConnectionState.Disconnected)
    }

    private suspend fun doValidate(id: Long) {
        val device = devices.get(id) ?: return
        val existing = lock.withLock { sessions[id] }
        if (existing != null && existing.isOpen) {
            setState(id, ConnectionState.Validating)
            if (existing.ping() is AdbResult.Ok) {
                devices.markChecked(id, System.currentTimeMillis())
                setState(id, ConnectionState.Connected)
                return
            }
            dropSession(id)
        }
        // Wi-Fi being up is not proof of reachability; only an ADB round trip counts.
        if (device.autoReconnect) {
            doConnect(id)
        } else {
            setState(id, ConnectionState.Disconnected)
        }
    }

    private suspend fun doConnect(id: Long) {
        val device = devices.get(id) ?: return
        dropSession(id)
        setState(id, ConnectionState.Connecting)
        val result = adb.connect(device.endpoint, onAwaitingApproval = {
            setState(id, ConnectionState.AwaitingApproval)
        })
        when (result) {
            is AdbResult.Ok -> {
                lock.withLock { sessions[id] = result.value }
                devices.markChecked(id, System.currentTimeMillis())
                setState(id, ConnectionState.Connected)
            }
            is AdbResult.Err -> setState(id, result.error.toConnectionState())
        }
    }

    private suspend fun dropSession(id: Long) {
        lock.withLock { sessions.remove(id) }?.disconnect()
    }

    private fun launchExclusive(id: Long, block: suspend () -> Unit): Job {
        // Check-and-put must not suspend, so jobs is guarded by synchronized, not the Mutex.
        synchronized(jobs) {
            jobs[id]?.takeIf { it.isActive }?.let { return it }
            val job = scope.launch {
                try {
                    block()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    setState(id, ConnectionState.Error(AdbError.Unknown(e.javaClass.simpleName)))
                }
            }
            jobs[id] = job
            job.invokeOnCompletion { synchronized(jobs) { if (jobs[id] === job) jobs.remove(id) } }
            return job
        }
    }

    private fun setState(id: Long, state: ConnectionState) {
        _states.update { it + (id to state) }
    }
}
