package com.tap.mood

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.MaterialTheme
import androidx.compose.material.darkColors
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LifecycleStartEffect
import com.tap.mood.doom.ui.Configuration
import com.tap.mood.doom.ui.DoomScreen
import com.tap.mood.doom.ui.settings.SettingsStore
import com.tap.mood.graphics.GraphicsBackendRegistry
import com.tap.mood.viewmodel.MoodViewModel
import com.tap.mood.viewmodel.MoodViewModelFactory
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.android.ActivityKey

@ContributesIntoMap(AppScope::class, binding<Activity>())
@ActivityKey
@Inject
class MainActivity(
    private val viewModelFactory: MoodViewModelFactory,
    private val configuration: Configuration,
    private val settingsStore: SettingsStore,
    private val graphicsBackends: GraphicsBackendRegistry,
) : ComponentActivity() {
    override val defaultViewModelProviderFactory: ViewModelProvider.Factory
        get() = viewModelFactory

    private val moodViewModel by viewModels<MoodViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            var active by remember { mutableStateOf(false) }
            LifecycleStartEffect(Unit) {
                active = true
                onStopOrDispose {
                    moodViewModel.releaseAllKeys()
                    active = false
                }
            }
            MaterialTheme(
                colors =
                    darkColors(
                        primary = Color(0xffb42d23),
                        secondary = Color(0xffd17b43),
                    ),
            ) {
                DoomScreen(
                    controller = moodViewModel,
                    settingsStore = settingsStore,
                    graphicsBackends = graphicsBackends,
                    active = active,
                    configuration = configuration,
                    modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing),
                )
            }
        }
    }
}
