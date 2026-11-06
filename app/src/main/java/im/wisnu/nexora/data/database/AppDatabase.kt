package im.wisnu.nexora.data.database

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Insert
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "devices")
data class DeviceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val host: String,
    val connectPort: Int,
    val autoReconnect: Boolean = true,
    val lastCheckedAt: Long? = null,
)

@Dao
interface DeviceDao {
    @Query("SELECT * FROM devices ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<DeviceEntity>>

    @Query("SELECT * FROM devices WHERE id = :id")
    suspend fun get(id: Long): DeviceEntity?

    @Insert
    suspend fun insert(device: DeviceEntity): Long

    @Query("UPDATE devices SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("UPDATE devices SET lastCheckedAt = :at WHERE id = :id")
    suspend fun markChecked(id: Long, at: Long)

    @Query("UPDATE devices SET autoReconnect = :enabled WHERE id = :id")
    suspend fun setAutoReconnect(id: Long, enabled: Boolean)

    @Query("DELETE FROM devices WHERE id = :id")
    suspend fun delete(id: Long)
}

@Database(entities = [DeviceEntity::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun deviceDao(): DeviceDao
}
