package com.tap.mood.doom.ui.settings

import com.tap.mood.doom.runtime.settings.EngineSettings

data class Settings(
    val display: DisplaySettings = DisplaySettings(),
    val engine: EngineSettings = EngineSettings(),
)
