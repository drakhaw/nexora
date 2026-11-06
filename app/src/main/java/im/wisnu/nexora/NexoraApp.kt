package im.wisnu.nexora

import android.app.Application
import im.wisnu.nexora.core.lifecycle.ForegroundValidator
import im.wisnu.nexora.core.network.NetworkMonitor
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class NexoraApp : Application() {

    @Inject lateinit var foregroundValidator: ForegroundValidator
    @Inject lateinit var networkMonitor: NetworkMonitor

    override fun onCreate() {
        super.onCreate()
        foregroundValidator.install()
        networkMonitor.install()
    }
}
