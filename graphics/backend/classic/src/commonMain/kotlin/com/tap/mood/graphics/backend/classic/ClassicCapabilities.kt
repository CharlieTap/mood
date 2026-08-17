package com.tap.mood.graphics.backend.classic

import com.tap.mood.graphics.DisplayEffectId
import com.tap.mood.graphics.DisplayEffectIds
import com.tap.mood.graphics.DisplayEffectOption
import com.tap.mood.graphics.GraphicsCapabilities
import com.tap.mood.graphics.UpscalerId
import com.tap.mood.graphics.UpscalerIds
import com.tap.mood.graphics.UpscalerOption

internal val classicCapabilities =
    GraphicsCapabilities(
        upscalers =
            listOf(
                UpscalerOption(UpscalerId(UpscalerIds.NEAREST), "Pixel Perfect"),
                UpscalerOption(UpscalerId(UpscalerIds.BILINEAR), "Smooth"),
            ),
        effects = listOf(DisplayEffectOption(DisplayEffectId(DisplayEffectIds.ORIGINAL), "Original")),
    )
