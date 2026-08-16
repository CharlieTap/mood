@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class, kotlin.wasm.unsafe.UnsafeWasmMemoryApi::class)
@file:Suppress("FunctionName")

package com.tap.mood.doom.ui.rendering

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.HtmlElementView
import com.tap.mood.doom.runtime.engine.Frame
import com.tap.mood.doom.runtime.input.InputMode
import com.tap.mood.doom.runtime.input.Key
import com.tap.mood.doom.runtime.instance.InstanceController
import com.tap.mood.doom.runtime.instance.InstanceState
import com.tap.mood.doom.ui.settings.DisplaySettings
import com.tap.mood.doom.ui.settings.Scaling
import kotlinx.browser.document
import kotlinx.browser.window
import org.khronos.webgl.Int32Array
import org.khronos.webgl.Int8Array
import org.khronos.webgl.toInt32Array
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLDivElement
import org.w3c.dom.ImageData
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import kotlin.wasm.unsafe.Pointer
import kotlin.wasm.unsafe.withScopedMemoryAllocator

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal actual fun Surface(
    controller: InstanceController,
    settings: DisplaySettings,
    modifier: Modifier,
) {
    val renderer = remember { WebDoomRenderer() }
    val activeKeys = remember { mutableMapOf<String, List<Key>>() }

    DisposableEffect(controller, renderer) {
        val sink: (Frame) -> Unit = renderer::submit
        val keyDownListener: (Event) -> Unit = { event ->
            val keyboardEvent = event as KeyboardEvent
            val state = controller.state.value
            val mapped =
                if (
                    state.status == InstanceState.Status.Running &&
                    (state.inputMode != InputMode.TextEntry || keyboardEvent.key == "Escape")
                ) {
                    keyboardEvent.toDoomKeys()
                } else {
                    null
                }
            if (mapped != null) {
                keyboardEvent.preventDefault()
                if (activeKeys.put(keyboardEvent.code, mapped) == null) {
                    mapped.forEach { key -> controller.setKeyPressed(key, true) }
                }
            }
        }
        val keyUpListener: (Event) -> Unit = { event ->
            val keyboardEvent = event as KeyboardEvent
            activeKeys.remove(keyboardEvent.code)?.let { mapped ->
                keyboardEvent.preventDefault()
                mapped.asReversed().forEach { key -> controller.setKeyPressed(key, false) }
            }
        }
        val blurListener: (Event) -> Unit = {
            activeKeys.clear()
            controller.releaseAllKeys()
        }

        controller.setFrameSink(sink)
        window.addEventListener("keydown", keyDownListener)
        window.addEventListener("keyup", keyUpListener)
        window.addEventListener("blur", blurListener)
        onDispose {
            controller.clearFrameSink(sink)
            window.removeEventListener("keydown", keyDownListener)
            window.removeEventListener("keyup", keyUpListener)
            window.removeEventListener("blur", blurListener)
            activeKeys.clear()
            controller.releaseAllKeys()
        }
    }

    HtmlElementView(
        factory = { renderer.element },
        update = { renderer.settings = settings },
        onRelease = { renderer.close() },
        modifier = modifier,
    )
}

private class WebDoomRenderer {
    private val canvas =
        (document.createElement("canvas") as HTMLCanvasElement).apply {
            tabIndex = 0
            style.display = "block"
            style.outline = "none"
            addEventListener("pointerdown", { focus() })
        }
    val element =
        (document.createElement("div") as HTMLDivElement).apply {
            style.width = "100%"
            style.height = "100%"
            style.display = "flex"
            style.alignItems = "center"
            style.justifyContent = "center"
            style.backgroundColor = "black"
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

    var settings: DisplaySettings = DisplaySettings()
        set(value) {
            field = value
            updateLayout()
        }

    fun submit(frame: Frame) {
        val target =
            imageData?.takeIf { width == frame.width && height == frame.height }
                ?: createImageData(frame.width, frame.height)
        if (canvas.style.width.isEmpty()) updateLayout()
        frame.indexedPixels.withLinearMemory { pointer ->
            expandIndexedFrame(
                pointer.address.toInt(),
                frame.indexedPixels.size,
                frame.palette.toInt32Array(),
                requireNotNull(pixelBuffer),
            )
        }
        context.putImageData(target, 0.0, 0.0)
    }

    fun close() {
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
    palette: Int32Array,
    target: Int8Array,
) = expandIndexedFrameInJavaScript(sourcePointer, sourceLength, palette, target)

@JsFun(
    """(sourcePointer, sourceLength, palette, target) => {
        const source = new Uint8Array(wasmExports.memory.buffer, sourcePointer, sourceLength);
        for (let sourceIndex = 0, targetIndex = 0; sourceIndex < source.length; sourceIndex++) {
            const color = palette[source[sourceIndex]];
            target[targetIndex++] = color >>> 16;
            target[targetIndex++] = color >>> 8;
            target[targetIndex++] = color;
            target[targetIndex++] = 255;
        }
    }""",
)
private external fun expandIndexedFrameInJavaScript(
    sourcePointer: Int,
    sourceLength: Int,
    palette: Int32Array,
    target: Int8Array,
)

private inline fun <T> ByteArray.withLinearMemory(block: (Pointer) -> T): T =
    withScopedMemoryAllocator { allocator ->
        val pointer = allocator.allocate(size)
        var index = 0
        while (index + Int.SIZE_BYTES <= size) {
            val value =
                (this[index].toInt() and 0xff) or
                    ((this[index + 1].toInt() and 0xff) shl 8) or
                    ((this[index + 2].toInt() and 0xff) shl 16) or
                    ((this[index + 3].toInt() and 0xff) shl 24)
            (pointer + index).storeInt(value)
            index += Int.SIZE_BYTES
        }
        while (index < size) {
            (pointer + index).storeByte(this[index++])
        }
        block(pointer)
    }

private fun KeyboardEvent.toDoomKeys(): List<Key>? =
    when (key) {
        "ArrowLeft" -> {
            listOf(Key.LEFT)
        }

        "ArrowRight" -> {
            listOf(Key.RIGHT)
        }

        "ArrowUp" -> {
            listOf(Key.UP)
        }

        "ArrowDown" -> {
            listOf(Key.DOWN)
        }

        "," -> {
            listOf(Key.STRAFE_LEFT)
        }

        "." -> {
            listOf(Key.STRAFE_RIGHT)
        }

        "Control" -> {
            listOf(Key.FIRE)
        }

        " " -> {
            listOf(Key.USE)
        }

        "Shift" -> {
            listOf(Key.SHIFT)
        }

        "Tab" -> {
            listOf(Key.TAB)
        }

        "Escape" -> {
            listOf(Key.ESCAPE)
        }

        "Enter" -> {
            listOf(Key.ENTER)
        }

        "Backspace" -> {
            listOf(Key.BACKSPACE)
        }

        "Alt" -> {
            listOf(Key.ALT)
        }

        else -> {
            key
                .singleOrNull()
                ?.takeIf { character -> character.code in PRINTABLE_ASCII }
                ?.lowercaseChar()
                ?.let(Key::character)
                ?.let(::listOf)
        }
    }

private val PRINTABLE_ASCII = 32..126
