package com.tap.mood.graphics

import kotlin.jvm.JvmInline

enum class AspectRatio {
    Corrected,
    Raw,
    Fill,
}

@JvmInline
value class UpscalerId(
    val value: String,
)

object UpscalerIds {
    const val NEAREST = "nearest"
    const val BILINEAR = "bilinear"
    const val FSR1 = "fsr1"
    const val NEURAL = "neural"
}

@JvmInline
value class DisplayEffectId(
    val value: String,
)

object DisplayEffectIds {
    const val ORIGINAL = "original"
    const val CRT = "crt"
    const val ENHANCED = "enhanced"
}

data class DisplaySettings(
    val aspectRatio: AspectRatio = AspectRatio.Corrected,
    val upscaler: UpscalerId = UpscalerId(UpscalerIds.FSR1),
    val effect: DisplayEffectId = DisplayEffectId(DisplayEffectIds.CRT),
    val backend: GraphicsBackendId = GraphicsBackendId(GraphicsBackendIds.WEB_GPU),
)
