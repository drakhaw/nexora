package im.wisnu.nexora.domain.device

import im.wisnu.nexora.core.adb.AdbEndpoint
import kotlinx.coroutines.flow.Flow

/** A trusted, saved target (CON-05). The pairing port is never stored: it changes per pairing session. */
data class SavedDevice(
    val id: Long,
    val name: String,
    val host: String,
    val connectPort: Int,
    val autoReconnect: Boolean,
    val lastCheckedAt: Long?,
) {
    val endpoint: AdbEndpoint get() = AdbEndpoint(host, connectPort)
}

data class SelectedDevice(val device: SavedDevice?, val state: im.wisnu.nexora.core.adb.ConnectionState)

interface DeviceRepository {
    val devices: Flow<List<SavedDevice>>
    val selectedId: Flow<Long?>

    suspend fun get(id: Long): SavedDevice?
    suspend fun add(name: String, host: String, connectPort: Int): Long
    suspend fun rename(id: Long, name: String)
    suspend fun forget(id: Long)
    suspend fun select(id: Long?)
    suspend fun markChecked(id: Long, at: Long)
    suspend fun setAutoReconnect(id: Long, enabled: Boolean)
}
