package com.tap.mood.doom.ui.settings

import com.tap.mood.doom.runtime.settings.EngineSettings
import com.tap.mood.renderer.DisplaySettings

data class Settings(
    val display: DisplaySettings = DisplaySettings(),
    val engine: EngineSettings = EngineSettings(),
)
