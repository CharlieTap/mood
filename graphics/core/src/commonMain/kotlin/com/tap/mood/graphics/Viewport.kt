package com.tap.mood.graphics

data class Viewport(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
)

fun doomViewport(
    containerWidth: Float,
    containerHeight: Float,
    frameWidth: Int,
    frameHeight: Int,
    aspectRatio: AspectRatio,
): Viewport {
    if (containerWidth <= 0f || containerHeight <= 0f || frameWidth <= 0 || frameHeight <= 0) {
        return Viewport(0f, 0f, 0f, 0f)
    }
    val contentAspectRatio =
        when (aspectRatio) {
            AspectRatio.Corrected -> 4f / 3f
            AspectRatio.Raw -> frameWidth.toFloat() / frameHeight
            AspectRatio.Fill -> containerWidth / containerHeight
        }
    val targetWidth: Float
    val targetHeight: Float
    if (containerWidth / containerHeight > contentAspectRatio) {
        targetHeight = containerHeight
        targetWidth = targetHeight * contentAspectRatio
    } else {
        targetWidth = containerWidth
        targetHeight = targetWidth / contentAspectRatio
    }
    return Viewport(
        left = (containerWidth - targetWidth) / 2f,
        top = (containerHeight - targetHeight) / 2f,
        width = targetWidth,
        height = targetHeight,
    )
}
