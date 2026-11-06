package im.wisnu.nexora.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import im.wisnu.nexora.data.database.DeviceDao
import im.wisnu.nexora.data.database.DeviceEntity
import im.wisnu.nexora.domain.device.DeviceRepository
import im.wisnu.nexora.domain.device.SavedDevice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomDeviceRepository @Inject constructor(
    private val dao: DeviceDao,
    private val prefs: DataStore<Preferences>,
) : DeviceRepository {

    override val devices: Flow<List<SavedDevice>> = dao.observeAll().map { list -> list.map { it.toDomain() } }

    override val selectedId: Flow<Long?> = prefs.data.map { it[SELECTED_ID] }

    override suspend fun get(id: Long): SavedDevice? = dao.get(id)?.toDomain()

    override suspend fun add(name: String, host: String, connectPort: Int): Long =
        dao.insert(DeviceEntity(name = name.trim(), host = host.trim(), connectPort = connectPort))

    override suspend fun rename(id: Long, name: String) = dao.rename(id, name.trim())

    override suspend fun forget(id: Long) {
        dao.delete(id)
        prefs.edit { if (it[SELECTED_ID] == id) it.remove(SELECTED_ID) }
    }

    override suspend fun select(id: Long?) {
        prefs.edit { if (id == null) it.remove(SELECTED_ID) else it[SELECTED_ID] = id }
    }

    override suspend fun markChecked(id: Long, at: Long) = dao.markChecked(id, at)

    override suspend fun setAutoReconnect(id: Long, enabled: Boolean) = dao.setAutoReconnect(id, enabled)

    private fun DeviceEntity.toDomain() = SavedDevice(id, name, host, connectPort, autoReconnect, lastCheckedAt)

    private companion object {
        val SELECTED_ID = longPreferencesKey("selected_device_id")
    }
}
