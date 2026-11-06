package im.wisnu.nexora.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import im.wisnu.nexora.di.ApplicationScope
import im.wisnu.nexora.domain.device.DeviceConnections
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

enum class LinkType { Local, NotLocal, None }

/**
 * Watches the phone's default network.
 *
 * When it changes (Wi-Fi switch, drop, reconnect), the selected device is
 * revalidated so a stale session is not trusted (acceptance: "Change phone
 * Wi-Fi"). [link] drives the untrusted-network warning (SEC-08).
 */
@Singleton
class NetworkMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val connections: DeviceConnections,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val _link = MutableStateFlow(LinkType.None)
    val link: StateFlow<LinkType> = _link.asStateFlow()

    private var current: Network? = null
    private var pending: Job? = null

    fun install() {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val local = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                _link.value = if (local) LinkType.Local else LinkType.NotLocal
                if (network != current) {
                    val first = current == null
                    current = network
                    if (!first) revalidateSoon()
                }
            }

            override fun onLost(network: Network) {
                if (network == current) {
                    current = null
                    _link.value = LinkType.None
                    revalidateSoon()
                }
            }
        })
    }

    /** Debounced so a burst of callbacks during a Wi-Fi switch runs one validation. */
    private fun revalidateSoon() {
        pending?.cancel()
        pending = scope.launch {
            delay(1_500)
            connections.validateSelected()
        }
    }
}
