package com.tap.mood.doom.runtime.engine

import com.tap.mood.doom.runtime.host.Host

/** A rendered Doom frame whose backing arrays are borrowed until [Host.onFrame] returns. */
class Frame internal constructor(
    val width: Int,
    val height: Int,
    /** Row-major palette indices, one byte per pixel. */
    val indexedPixels: ByteArray,
    /** Palette entries encoded as `0x00RRGGBB`. */
    val palette: IntArray,
    private val bgraPixels: ByteArray,
) {
    private var expanded = false

    /** Row-major BGRA8888 pixels, expanded on demand for renderers that need them. */
    val pixels: ByteArray
        get() {
            if (!expanded) {
                expandPixels()
                expanded = true
            }
            return bgraPixels
        }

    private fun expandPixels() {
        var sourceIndex = 0
        var destinationIndex = 0
        while (sourceIndex < indexedPixels.size) {
            val color = palette[indexedPixels[sourceIndex].toInt() and 0xff]
            bgraPixels[destinationIndex] = color.toByte()
            bgraPixels[destinationIndex + 1] = (color ushr 8).toByte()
            bgraPixels[destinationIndex + 2] = (color ushr 16).toByte()
            bgraPixels[destinationIndex + 3] = 0xff.toByte()
            sourceIndex++
            destinationIndex += BYTES_PER_PIXEL
        }
    }
}

private const val BYTES_PER_PIXEL = 4
