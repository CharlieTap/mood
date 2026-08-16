package com.tap.mood.doom.ui.rendering

import com.tap.mood.doom.ui.settings.AspectRatio
import kotlin.test.Test
import kotlin.test.assertEquals

class ViewportTest {
    @Test
    fun correctedAspectRatioLetterboxesWideContainers() {
        assertEquals(
            Viewport(left = 160f, top = 0f, width = 960f, height = 720f),
            doomViewport(1280f, 720f, 320, 200, AspectRatio.Corrected),
        )
    }

    @Test
    fun rawAspectRatioUsesTheFrameDimensions() {
        assertEquals(
            Viewport(left = 0f, top = 0f, width = 1280f, height = 800f),
            doomViewport(1280f, 800f, 320, 200, AspectRatio.Raw),
        )
    }

    @Test
    fun fillUsesTheWholeContainer() {
        assertEquals(
            Viewport(left = 0f, top = 0f, width = 1000f, height = 700f),
            doomViewport(1000f, 700f, 320, 200, AspectRatio.Fill),
        )
    }
}
