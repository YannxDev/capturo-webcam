package io.github.yannick.capturowebcam

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yannick.capturowebcam.data.SettingsStore
import io.github.yannick.capturowebcam.data.WebcamBus
import io.github.yannick.capturowebcam.ui.CapturoTheme
import io.github.yannick.capturowebcam.ui.WebcamScreen

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SettingsStore.init(this)
        enableEdgeToEdge()
        setContent {
            var themeMode by remember { mutableIntStateOf(SettingsStore.theme()) }
            var accentIndex by remember { mutableIntStateOf(SettingsStore.accent()) }
            val running by WebcamBus.running.collectAsStateWithLifecycle()

            // Garde l'écran allumé pendant la diffusion : le téléphone reste
            // visible et la caméra ne se met pas en veille.
            LaunchedEffect(running) {
                if (running) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }

            CapturoTheme(themeMode = themeMode, accentIndex = accentIndex) {
                WebcamScreen(
                    themeMode = themeMode,
                    accentIndex = accentIndex,
                    onThemeModeChange = { mode ->
                        themeMode = mode
                        SettingsStore.setTheme(mode)
                    },
                    onAccentChange = { index ->
                        accentIndex = index
                        SettingsStore.setAccent(index)
                    },
                )
            }
        }
    }
}
