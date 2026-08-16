package com.tap.mood.doom.ui.settings

enum class AspectRatio {
    Corrected,
    Raw,
    Fill,
}

enum class Scaling {
    Classic,
    Smooth,
}

data class DisplaySettings(
    val aspectRatio: AspectRatio = AspectRatio.Corrected,
    val scaling: Scaling = Scaling.Classic,
)
