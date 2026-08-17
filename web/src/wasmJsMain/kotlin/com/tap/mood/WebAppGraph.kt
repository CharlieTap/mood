package com.tap.mood

import com.tap.mood.doom.runtime.instance.DefaultInstanceController
import com.tap.mood.doom.ui.Configuration
import com.tap.mood.doom.ui.settings.SettingsStore
import com.tap.mood.renderer.RendererRegistry
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provides

@DependencyGraph(AppScope::class)
internal interface WebAppGraph {
    val application: WebApplication

    @Provides
    fun provideDoomUiConfiguration(): Configuration =
        Configuration(
            showVirtualControls = false,
            showKeyboardControls = true,
        )
}

@Inject
internal class WebApplication(
    val controller: DefaultInstanceController,
    val settingsStore: SettingsStore,
    val rendererRegistry: RendererRegistry,
    val uiConfiguration: Configuration,
) : AutoCloseable {
    override fun close() = controller.close()
}
