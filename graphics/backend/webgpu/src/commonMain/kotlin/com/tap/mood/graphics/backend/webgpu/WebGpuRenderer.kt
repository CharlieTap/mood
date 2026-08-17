package com.tap.mood.graphics.backend.webgpu

import com.tap.mood.doom.runtime.engine.Frame
import com.tap.mood.graphics.DisplaySettings
import com.tap.mood.graphics.Viewport
import com.tap.mood.graphics.doomViewport

internal class WebGpuRenderer(
    private val driver: WebGpuDriver,
    private val features: WebGpuFeatureRegistry,
) : AutoCloseable {
    private var configuredFrameWidth = 0
    private var configuredFrameHeight = 0
    private var uploadedPaletteRevision = Int.MIN_VALUE
    private var pipeline: WebGpuPipeline? = null

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

        val nextPipeline =
            WebGpuPipeline(
                upscaler = features.upscaler(settings.upscaler),
                effect = features.effect(settings.effect),
            )
        if (pipeline != nextPipeline) {
            driver.updatePipeline(nextPipeline)
            pipeline = nextPipeline
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

    fun updatePipeline(pipeline: WebGpuPipeline)

    fun draw(viewport: Viewport): Boolean
}

internal data class WebGpuPipeline(
    val upscaler: RegisteredWebGpuUpscaler,
    val effect: RegisteredWebGpuDisplayEffect,
)
