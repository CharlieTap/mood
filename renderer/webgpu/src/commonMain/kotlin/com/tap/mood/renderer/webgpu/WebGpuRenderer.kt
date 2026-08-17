package com.tap.mood.renderer.webgpu

import com.tap.mood.doom.runtime.engine.Frame
import com.tap.mood.renderer.DisplaySettings
import com.tap.mood.renderer.Scaling
import com.tap.mood.renderer.Viewport
import com.tap.mood.renderer.doomViewport

internal class WebGpuRenderer(
    private val driver: WebGpuDriver,
) : AutoCloseable {
    private var configuredFrameWidth = 0
    private var configuredFrameHeight = 0
    private var uploadedPaletteRevision = Int.MIN_VALUE
    private var smoothScalingEnabled: Boolean? = null

    val backendLabel: String
        get() = driver.backendLabel

    fun render(
        frame: Frame,
        targetWidth: Int,
        targetHeight: Int,
        settings: DisplaySettings,
    ): Boolean {
        if (targetWidth <= 0 || targetHeight <= 0) return false
        driver.configureTarget(targetWidth, targetHeight)

        if (configuredFrameWidth != frame.width || configuredFrameHeight != frame.height) {
            driver.configureFrame(frame.width, frame.height)
            configuredFrameWidth = frame.width
            configuredFrameHeight = frame.height
        }

        driver.uploadIndices(frame.indexedPixels, frame.width, frame.height)
        if (uploadedPaletteRevision != frame.paletteRevision) {
            driver.uploadPalette(frame.rgbaPalette)
            uploadedPaletteRevision = frame.paletteRevision
        }

        val smoothScaling = settings.scaling == Scaling.Smooth
        if (smoothScalingEnabled != smoothScaling) {
            driver.updateInterpolation(smoothScaling)
            smoothScalingEnabled = smoothScaling
        }

        return driver.draw(
            doomViewport(
                containerWidth = targetWidth.toFloat(),
                containerHeight = targetHeight.toFloat(),
                frameWidth = frame.width,
                frameHeight = frame.height,
                aspectRatio = settings.aspectRatio,
            ),
        )
    }

    override fun close() = driver.close()
}

internal interface WebGpuDriver : AutoCloseable {
    val backendLabel: String

    fun configureTarget(
        width: Int,
        height: Int,
    )

    fun configureFrame(
        width: Int,
        height: Int,
    )

    fun uploadIndices(
        pixels: ByteArray,
        width: Int,
        height: Int,
    )

    fun uploadPalette(palette: ByteArray)

    fun updateInterpolation(smooth: Boolean)

    fun draw(viewport: Viewport): Boolean
}
