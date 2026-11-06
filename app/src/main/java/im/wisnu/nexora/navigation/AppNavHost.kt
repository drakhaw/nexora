package im.wisnu.nexora.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Monitor
import androidx.compose.material.icons.outlined.SettingsRemote
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import im.wisnu.nexora.feature.apps.AppsScreen
import im.wisnu.nexora.feature.devices.DevicesScreen
import im.wisnu.nexora.feature.tools.ToolsScreen
import im.wisnu.nexora.feature.remote.RemoteScreen
import im.wisnu.nexora.feature.monitoring.MonitorScreen
import kotlinx.serialization.Serializable
import kotlin.reflect.KClass

@Serializable data object DevicesRoute
@Serializable data object RemoteRoute
@Serializable data object AppsRoute
@Serializable data object ToolsRoute
@Serializable data object MonitorRoute

private data class Tab(val route: Any, val type: KClass<*>, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(DevicesRoute, DevicesRoute::class, "Devices", Icons.Outlined.Devices),
    Tab(RemoteRoute, RemoteRoute::class, "Remote", Icons.Outlined.SettingsRemote),
    Tab(AppsRoute, AppsRoute::class, "Apps", Icons.Outlined.Apps),
    Tab(ToolsRoute, ToolsRoute::class, "Tools", Icons.Outlined.Build),
    Tab(MonitorRoute, MonitorRoute::class, "Monitor", Icons.Outlined.Monitor),
)

@Composable
fun AppNavHost() {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEach { tab ->
                    val selected = entry?.destination?.hierarchy?.any { it.hasRoute(tab.type) } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            nav.navigate(tab.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = DevicesRoute, modifier = Modifier.padding(padding)) {
            composable<DevicesRoute> { DevicesScreen() }
            composable<RemoteRoute> { RemoteScreen() }
            composable<AppsRoute> { AppsScreen() }
            composable<ToolsRoute> { ToolsScreen() }
            composable<MonitorRoute> { MonitorScreen() }
        }
    }
}
