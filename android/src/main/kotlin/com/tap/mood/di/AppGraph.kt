package com.tap.mood.di

import android.app.Application
import com.tap.mood.doom.ui.Configuration
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metrox.android.MetroAppComponentProviders
import dev.zacsweers.metrox.viewmodel.ViewModelGraph

@DependencyGraph(AppScope::class)
interface AppGraph :
    ViewModelGraph,
    MetroAppComponentProviders {
    @Provides
    fun provideDoomUiConfiguration(): Configuration =
        Configuration(
            showVirtualControls = true,
            showKeyboardControls = false,
        )

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(
            @Provides application: Application,
        ): AppGraph
    }
}
