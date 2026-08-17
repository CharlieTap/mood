@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@file:Suppress("FunctionName")

package com.tap.mood.renderer.webgpu

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
import com.tap.mood.renderer.Viewport
import com.tap.mood.renderer.withLinearMemoryAddress
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.StringKey
import dev.zacsweers.metro.binding
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLDivElement
import org.w3c.dom.HTMLElement
import kotlin.js.JsAny
import kotlin.js.toJsString

@StringKey(RendererIds.WEB_GPU)
@ContributesIntoMap(AppScope::class, binding<RendererFrontend>())
@Inject
class BrowserWebGpuFrontend : RendererFrontend {
    override val displayName: String = "WebGPU"
    override val autoPriority: Int = 100

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

private val presenterFactory = BrowserFramePresenterFactory(::BrowserWebGpuPresenter)

private class BrowserWebGpuPresenter(
    private val onUnavailable: (Throwable?) -> Unit,
) : BrowserFramePresenter {
    private val canvas =
        (document.createElement("canvas") as HTMLCanvasElement).apply {
            tabIndex = 0
            style.display = "block"
            style.width = "100%"
            style.height = "100%"
            style.outline = "none"
            addEventListener("pointerdown", { focus() })
        }
    override val element: HTMLElement =
        (document.createElement("div") as HTMLDivElement).apply {
            style.width = "100%"
            style.height = "100%"
            style.backgroundColor = "black"
            setAttribute("data-renderer", "initializing-webgpu")
            appendChild(canvas)
        }
    private val driver = BrowserWebGpuDriver(canvas)
    private val renderer = WebGpuRenderer(driver)
    private var unavailableReported = false

    override var settings: DisplaySettings = DisplaySettings()

    override fun submit(frame: Frame) {
        when (driver.status) {
            WEBGPU_READY -> {
                val density = window.devicePixelRatio
                val targetWidth = (element.clientWidth * density).toInt().coerceAtLeast(1)
                val targetHeight = (element.clientHeight * density).toInt().coerceAtLeast(1)
                if (renderer.render(frame, targetWidth, targetHeight, settings)) {
                    element.setAttribute("data-renderer", "webgpu")
                } else {
                    reportUnavailable(IllegalStateException("WebGPU stopped accepting frames"))
                }
            }

            WEBGPU_FAILED -> {
                reportUnavailable(UnsupportedOperationException("WebGPU is unavailable"))
            }
        }
    }

    override fun close() {
        renderer.close()
        canvas.width = 0
        canvas.height = 0
    }

    private fun reportUnavailable(error: Throwable?) {
        if (unavailableReported) return
        unavailableReported = true
        element.setAttribute("data-renderer", "webgpu-failed")
        onUnavailable(error)
    }
}

private class BrowserWebGpuDriver(
    canvas: HTMLCanvasElement,
) : WebGpuDriver {
    private val handle = createWebGpuRenderer(canvas, PALETTE_WEBGPU_SHADER.toJsString())

    val status: Int
        get() = webGpuStatus(handle)

    override val backendLabel: String = "Browser WebGPU"

    override fun configureTarget(
        width: Int,
        height: Int,
    ) = configureWebGpuTarget(handle, width, height)

    override fun configureFrame(
        width: Int,
        height: Int,
    ) = configureWebGpuFrame(handle, width, height)

    override fun uploadIndices(
        pixels: ByteArray,
        width: Int,
        height: Int,
    ) = pixels.withLinearMemoryAddress { pointer ->
        uploadWebGpuIndices(handle, pointer, pixels.size, width, height)
    }

    override fun uploadPalette(palette: ByteArray) =
        palette.withLinearMemoryAddress { pointer ->
            uploadWebGpuPalette(handle, pointer, palette.size)
        }

    override fun updateInterpolation(smooth: Boolean) = updateWebGpuInterpolation(handle, smooth)

    override fun draw(viewport: Viewport): Boolean =
        drawWebGpuFrame(
            renderer = handle,
            left = viewport.left,
            top = viewport.top,
            width = viewport.width,
            height = viewport.height,
        )

    override fun close() = closeWebGpuRenderer(handle)
}

@JsFun(
    """(canvas, shader) => {
        const renderer = {
            canvas,
            status: 0,
            context: null,
            device: null,
            pipeline: null,
            optionsBuffer: null,
            paletteTexture: null,
            frameTexture: null,
            bindGroup: null,
            closed: false,
        };
        (async () => {
            try {
                const requestedRenderer = new URLSearchParams(location.search).get('renderer');
                if (requestedRenderer === 'classic' || requestedRenderer === 'canvas2d') {
                    throw new Error('Classic renderer explicitly requested');
                }
                if (!navigator.gpu) throw new Error('WebGPU is not available');
                const adapter = await navigator.gpu.requestAdapter({ powerPreference: 'high-performance' });
                if (!adapter) throw new Error('No WebGPU adapter is available');
                const device = await adapter.requestDevice();
                if (renderer.closed) {
                    device.destroy();
                    return;
                }
                const context = canvas.getContext('webgpu');
                const format = navigator.gpu.getPreferredCanvasFormat();
                context.configure({ device, format, alphaMode: 'opaque' });
                const module = device.createShaderModule({ label: 'Mood palette shader', code: shader });
                const pipeline = device.createRenderPipeline({
                    label: 'Mood palette pipeline',
                    layout: 'auto',
                    vertex: { module, entryPoint: 'vertexMain' },
                    fragment: { module, entryPoint: 'fragmentMain', targets: [{ format }] },
                    primitive: { topology: 'triangle-list' },
                });
                renderer.context = context;
                renderer.device = device;
                renderer.pipeline = pipeline;
                renderer.optionsBuffer = device.createBuffer({
                    label: 'Mood display options',
                    size: 16,
                    usage: GPUBufferUsage.UNIFORM | GPUBufferUsage.COPY_DST,
                });
                renderer.paletteTexture = device.createTexture({
                    label: 'Mood Doom palette',
                    size: [256, 1],
                    format: 'rgba8unorm',
                    usage: GPUTextureUsage.TEXTURE_BINDING | GPUTextureUsage.COPY_DST,
                });
                device.lost.then(info => {
                    if (!renderer.closed) {
                        renderer.status = -1;
                        console.error('Mood WebGPU device lost: ' + info.message);
                    }
                });
                renderer.status = 1;
                console.info('Mood renderer: WebGPU (R8Uint indexed framebuffer)');
            } catch (error) {
                renderer.status = -1;
                console.warn('Mood WebGPU frontend unavailable: ' + error);
            }
        })();
        return renderer;
    }""",
)
private external fun createWebGpuRenderer(
    canvas: HTMLCanvasElement,
    shader: kotlin.js.JsString,
): JsAny

@JsFun("(renderer) => renderer.status")
private external fun webGpuStatus(renderer: JsAny): Int

@JsFun(
    """(renderer, width, height) => {
        if (renderer.canvas.width !== width) renderer.canvas.width = width;
        if (renderer.canvas.height !== height) renderer.canvas.height = height;
    }""",
)
private external fun configureWebGpuTarget(
    renderer: JsAny,
    width: Int,
    height: Int,
)

@JsFun(
    """(renderer, width, height) => {
        renderer.frameTexture?.destroy();
        renderer.frameTexture = renderer.device.createTexture({
            label: 'Mood Doom framebuffer',
            size: [width, height],
            format: 'r8uint',
            usage: GPUTextureUsage.TEXTURE_BINDING | GPUTextureUsage.COPY_DST,
        });
        renderer.bindGroup = renderer.device.createBindGroup({
            label: 'Mood palette bind group',
            layout: renderer.pipeline.getBindGroupLayout(0),
            entries: [
                { binding: 0, resource: renderer.frameTexture.createView() },
                { binding: 1, resource: renderer.paletteTexture.createView() },
                { binding: 2, resource: { buffer: renderer.optionsBuffer } },
            ],
        });
    }""",
)
private external fun configureWebGpuFrame(
    renderer: JsAny,
    width: Int,
    height: Int,
)

@JsFun(
    """(renderer, pointer, length, width, height) => {
        const pixels = new Uint8Array(wasmExports.memory.buffer, pointer, length);
        renderer.device.queue.writeTexture(
            { texture: renderer.frameTexture },
            pixels,
            { bytesPerRow: width, rowsPerImage: height },
            [width, height],
        );
    }""",
)
private external fun uploadWebGpuIndices(
    renderer: JsAny,
    pointer: Int,
    length: Int,
    width: Int,
    height: Int,
)

@JsFun(
    """(renderer, pointer, length) => {
        const palette = new Uint8Array(wasmExports.memory.buffer, pointer, length);
        renderer.device.queue.writeTexture(
            { texture: renderer.paletteTexture },
            palette,
            { bytesPerRow: length },
            [256, 1],
        );
    }""",
)
private external fun uploadWebGpuPalette(
    renderer: JsAny,
    pointer: Int,
    length: Int,
)

@JsFun(
    """(renderer, smooth) => renderer.device.queue.writeBuffer(
        renderer.optionsBuffer,
        0,
        new Uint32Array([smooth ? 1 : 0, 0, 0, 0]),
    )""",
)
private external fun updateWebGpuInterpolation(
    renderer: JsAny,
    smooth: Boolean,
)

@JsFun(
    """(renderer, left, top, width, height) => {
        if (renderer.status !== 1 || renderer.closed) return false;
        try {
            const encoder = renderer.device.createCommandEncoder({ label: 'Mood frame commands' });
            const pass = encoder.beginRenderPass({
                colorAttachments: [{
                    view: renderer.context.getCurrentTexture().createView(),
                    clearValue: { r: 0, g: 0, b: 0, a: 1 },
                    loadOp: 'clear',
                    storeOp: 'store',
                }],
            });
            pass.setViewport(left, top, Math.max(width, 1), Math.max(height, 1), 0, 1);
            pass.setPipeline(renderer.pipeline);
            pass.setBindGroup(0, renderer.bindGroup);
            pass.draw(3);
            pass.end();
            renderer.device.queue.submit([encoder.finish()]);
            return true;
        } catch (error) {
            renderer.status = -1;
            console.error('Mood WebGPU frontend failed while rendering: ' + error);
            return false;
        }
    }""",
)
private external fun drawWebGpuFrame(
    renderer: JsAny,
    left: Float,
    top: Float,
    width: Float,
    height: Float,
): Boolean

@JsFun(
    """(renderer) => {
        renderer.closed = true;
        renderer.status = -1;
        renderer.frameTexture?.destroy();
        renderer.paletteTexture?.destroy();
        renderer.optionsBuffer?.destroy();
        renderer.device?.destroy();
    }""",
)
private external fun closeWebGpuRenderer(renderer: JsAny)

private const val WEBGPU_READY = 1
private const val WEBGPU_FAILED = -1
