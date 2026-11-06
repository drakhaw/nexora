package im.wisnu.nexora.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import im.wisnu.nexora.core.adb.AdbClient
import im.wisnu.nexora.core.adb.libadb.LibAdbClient
import im.wisnu.nexora.data.database.AppDatabase
import im.wisnu.nexora.data.database.DeviceDao
import im.wisnu.nexora.data.repository.RoomDeviceRepository
import im.wisnu.nexora.domain.device.DeviceRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    @ApplicationScope
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "nexora.db").build()

    @Provides
    fun deviceDao(db: AppDatabase): DeviceDao = db.deviceDao()

    @Provides
    @Singleton
    fun settings(@ApplicationContext context: Context): DataStore<Preferences> = context.settingsStore
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BindingsModule {

    /** The single place that chooses the ADB implementation. Swap here to replace libadb-android. */
    @Binds
    abstract fun adbClient(impl: LibAdbClient): AdbClient

    @Binds
    abstract fun deviceRepository(impl: RoomDeviceRepository): DeviceRepository
}
