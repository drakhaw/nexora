package im.wisnu.nexora.core.lifecycle

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import im.wisnu.nexora.di.ApplicationScope
import im.wisnu.nexora.domain.device.DeviceConnections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Validates the selected device on every process background-to-foreground transition (CON-12). */
@Singleton
class ForegroundValidator @Inject constructor(
    private val connections: DeviceConnections,
    @ApplicationScope private val scope: CoroutineScope,
) : DefaultLifecycleObserver {

    fun install() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        scope.launch { connections.validateSelected() }
    }
}
