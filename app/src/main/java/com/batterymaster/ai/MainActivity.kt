package com.batterymaster.ai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.batterymaster.ai.background.MonitoringService
import com.batterymaster.ai.data.prefs.AppSettings
import com.batterymaster.ai.data.prefs.SettingsRepository
import com.batterymaster.ai.ui.navigation.AppRoot
import com.batterymaster.ai.ui.theme.BatteryMasterTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var settingsRepository: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Resume user-enabled monitoring while we're in the foreground (always permitted).
        lifecycleScope.launch {
            if (settingsRepository.current().monitoringEnabled) {
                runCatching { MonitoringService.start(this@MainActivity) }
            }
        }
        setContent {
            val settings by settingsRepository.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
            BatteryMasterTheme(themeMode = settings.themeMode, dynamicColor = settings.dynamicColor) {
                AppRoot()
            }
        }
    }
}
