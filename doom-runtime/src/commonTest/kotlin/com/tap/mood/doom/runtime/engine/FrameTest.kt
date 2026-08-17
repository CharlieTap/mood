package com.tap.mood.doom.runtime.engine

import kotlin.test.Test
import kotlin.test.assertContentEquals

class FrameTest {
    @Test
    fun expandsBgraPixelsOnDemand() {
        val palette = IntArray(256)
        palette[1] = 0x00112233
        palette[2] = 0x00445566
        val frame =
            Frame(
                width = 3,
                height = 1,
                indexedPixels = byteArrayOf(1, 2, 1),
                palette = palette,
                rgbaPalette = ByteArray(256 * 4),
                paletteRevision = 1,
                bgraPixels = ByteArray(12),
            )

        assertContentEquals(
            byteArrayOf(0x33, 0x22, 0x11, -1, 0x66, 0x55, 0x44, -1, 0x33, 0x22, 0x11, -1),
            frame.pixels,
        )
    }
}
