@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@file:Suppress("FunctionName")

package com.tap.mood.renderer.classic

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.tap.mood.doom.runtime.engine.Frame
import com.tap.mood.doom.runtime.instance.InstanceController
import com.tap.mood.renderer.BrowserFramePresenter
import com.tap.mood.renderer.BrowserFramePresenterFactory
import com.tap.mood.renderer.BrowserRendererContent
import com.tap.mood.renderer.DisplaySettings
import com.tap.mood.renderer.RendererFrontend
import com.tap.mood.renderer.RendererIds
import com.tap.mood.renderer.Scaling
import com.tap.mood.renderer.doomViewport
import com.tap.mood.renderer.withLinearMemoryAddress
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.StringKey
import dev.zacsweers.metro.binding
import kotlinx.browser.document
import kotlinx.browser.window
import org.khronos.webgl.Int8Array
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLDivElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.ImageData
import org.w3c.dom.events.Event

@StringKey(RendererIds.CLASSIC)
@ContributesIntoMap(AppScope::class, binding<RendererFrontend>())
@Inject
class BrowserClassicFrontend : RendererFrontend {
    override val displayName: String = "Classic (Canvas 2D)"
    override val autoPriority: Int = 0

    @Composable
    override fun Content(
        controller: InstanceController,
        settings: DisplaySettings,
        onUnavailable: (Throwable?) -> Unit,
        modifier: Modifier,
    ) = BrowserRendererContent(
        controller = controller,
        settings = settings,
        onUnavailable = onUnavailable,
        presenterFactory = presenterFactory,
        modifier = modifier,
    )
}

private val presenterFactory = BrowserFramePresenterFactory { BrowserClassicPresenter() }

private class BrowserClassicPresenter : BrowserFramePresenter {
    private val canvas =
        (document.createElement("canvas") as HTMLCanvasElement).apply {
            tabIndex = 0
            style.display = "block"
            style.outline = "none"
            addEventListener("pointerdown", { focus() })
        }
    override val element: HTMLElement =
        (document.createElement("div") as HTMLDivElement).apply {
            style.width = "100%"
            style.height = "100%"
            style.display = "flex"
            style.alignItems = "center"
            style.justifyContent = "center"
            style.backgroundColor = "black"
            setAttribute("data-renderer", "classic")
            appendChild(canvas)
        }
    private val context = canvas.getContext("2d") as CanvasRenderingContext2D
    private val resizeListener: (Event) -> Unit = { updateLayout() }
    private var imageData: ImageData? = null
    private var pixelBuffer: Int8Array? = null
    private var width = 0
    private var height = 0

    init {
        window.addEventListener("resize", resizeListener)
    }

    override var settings: DisplaySettings = DisplaySettings()
        set(value) {
            field = value
            updateLayout()
        }

    override fun submit(frame: Frame) {
        val target =
            imageData?.takeIf { width == frame.width && height == frame.height }
                ?: createImageData(frame.width, frame.height)
        if (canvas.style.width.isEmpty()) updateLayout()
        frame.indexedPixels.withLinearMemoryAddress { pointer ->
            frame.rgbaPalette.withLinearMemoryAddress { palettePointer ->
                expandIndexedFrame(
                    pointer,
                    frame.indexedPixels.size,
                    palettePointer,
                    requireNotNull(pixelBuffer),
                )
            }
        }
        context.putImageData(target, 0.0, 0.0)
    }

    override fun close() {
        window.removeEventListener("resize", resizeListener)
        imageData = null
        pixelBuffer = null
        canvas.width = 0
        canvas.height = 0
    }

    private fun createImageData(
        width: Int,
        height: Int,
    ): ImageData {
        this.width = width
        this.height = height
        canvas.width = width
        canvas.height = height
        return ImageData(width, height).also { nextImage ->
            imageData = nextImage
            pixelBuffer = Int8Array(nextImage.data.buffer)
            updateLayout()
        }
    }

    private fun updateLayout() {
        if (width == 0 || height == 0 || element.clientWidth == 0 || element.clientHeight == 0) return
        val viewport =
            doomViewport(
                containerWidth = element.clientWidth.toFloat(),
                containerHeight = element.clientHeight.toFloat(),
                frameWidth = width,
                frameHeight = height,
                aspectRatio = settings.aspectRatio,
            )
        canvas.style.width = "${viewport.width}px"
        canvas.style.height = "${viewport.height}px"
        canvas.style.setProperty(
            "image-rendering",
            if (settings.scaling == Scaling.Smooth) "auto" else "pixelated",
        )
    }
}

private fun expandIndexedFrame(
    sourcePointer: Int,
    sourceLength: Int,
    palettePointer: Int,
    target: Int8Array,
) = expandIndexedFrameInJavaScript(sourcePointer, sourceLength, palettePointer, target)

@JsFun(
    """(sourcePointer, sourceLength, palettePointer, target) => {
        const source = new Uint8Array(wasmExports.memory.buffer, sourcePointer, sourceLength);
        const palette = new Uint8Array(wasmExports.memory.buffer, palettePointer, 1024);
        for (let sourceIndex = 0, targetIndex = 0; sourceIndex < source.length; sourceIndex++) {
            const paletteIndex = source[sourceIndex] * 4;
            target[targetIndex++] = palette[paletteIndex];
            target[targetIndex++] = palette[paletteIndex + 1];
            target[targetIndex++] = palette[paletteIndex + 2];
            target[targetIndex++] = 255;
        }
    }""",
)
private external fun expandIndexedFrameInJavaScript(
    sourcePointer: Int,
    sourceLength: Int,
    palettePointer: Int,
    target: Int8Array,
)
