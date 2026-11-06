package im.wisnu.nexora

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import im.wisnu.nexora.feature.splash.NexoraSplash
import im.wisnu.nexora.navigation.AppNavHost
import im.wisnu.nexora.ui.theme.NexoraTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NexoraTheme {
                // Branded splash once per process: a new activity in a live process (Back, then the
                // launcher icon) skips it, as do rotation and process restore.
                var showSplash by rememberSaveable { mutableStateOf(!splashShown && savedInstanceState == null) }
                Crossfade(showSplash, animationSpec = tween(400), label = "splash") { splash ->
                    if (splash) {
                        NexoraSplash(onFinished = {
                            splashShown = true
                            showSplash = false
                        })
                    } else {
                        AppNavHost()
                    }
                }
            }
        }
    }

    private companion object {
        /** Process-scoped: cleared only when the process dies (e.g. swiped away from Recents). */
        var splashShown = false
    }
}
