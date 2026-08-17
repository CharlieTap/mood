@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@file:Suppress("FunctionName")

package com.tap.mood.graphics.backend.webgpu

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.tap.mood.doom.runtime.engine.Frame
import com.tap.mood.doom.runtime.instance.InstanceController
import com.tap.mood.graphics.BrowserFramePresenter
import com.tap.mood.graphics.BrowserFramePresenterFactory
import com.tap.mood.graphics.BrowserGraphicsContent
import com.tap.mood.graphics.DisplaySettings
import com.tap.mood.graphics.GraphicsBackend
import com.tap.mood.graphics.GraphicsBackendIds
import com.tap.mood.graphics.Viewport
import com.tap.mood.graphics.withLinearMemoryAddress
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

@StringKey(GraphicsBackendIds.WEB_GPU)
@ContributesIntoMap(AppScope::class, binding<GraphicsBackend>())
@Inject
class BrowserWebGpuBackend(
    private val features: WebGpuFeatureRegistry,
) : GraphicsBackend {
    override val displayName = "WebGPU"
    override val fallbackPriority = 100
    override val capabilities = features.capabilities

    @Composable
    override fun Content(
        controller: InstanceController,
        settings: DisplaySettings,
        onUnavailable: (Throwable?) -> Unit,
        modifier: Modifier,
    ) = BrowserGraphicsContent(
        controller = controller,
        settings = settings,
        onUnavailable = onUnavailable,
        presenterFactory = BrowserFramePresenterFactory { BrowserWebGpuPresenter(it, features) },
        modifier = modifier,
    )
}

private class BrowserWebGpuPresenter(
    private val onUnavailable: (Throwable?) -> Unit,
    features: WebGpuFeatureRegistry,
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
    private val driver = BrowserWebGpuDriver(canvas, features)
    private val renderer = WebGpuRenderer(driver, features)
    private var unavailableReported = false

    override var settings = DisplaySettings()

    override fun submit(frame: Frame) {
        when (driver.status) {
            WEBGPU_READY -> {
                val density = window.devicePixelRatio
                val width = (element.clientWidth * density).toInt().coerceAtLeast(1)
                val height = (element.clientHeight * density).toInt().coerceAtLeast(1)
                if (renderer.render(frame, width, height, settings)) {
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
    features: WebGpuFeatureRegistry,
) : WebGpuDriver {
    private val intermediateUpscaler = features.intermediateUpscaler
    private val handle =
        createWebGpuRenderer(
            canvas,
            features.presentationShader().toJsString(),
            (intermediateUpscaler?.implementation?.intermediateShader ?: "").toJsString(),
            intermediateUpscaler?.shaderMode ?: -1,
        )

    init {
        features.upscalers.forEach { upscaler ->
            (upscaler.implementation as? BrowserWebGpuUpscaler)?.install(handle, upscaler.shaderMode)
        }
    }

    val status: Int get() = webGpuStatus(handle)
    override val backendLabel = "Browser WebGPU"

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
    ) = pixels.withLinearMemoryAddress { uploadWebGpuIndices(handle, it, pixels.size, width, height) }

    override fun uploadPalette(palette: ByteArray) = palette.withLinearMemoryAddress { uploadWebGpuPalette(handle, it, palette.size) }

    override fun updatePipeline(pipeline: WebGpuPipeline) =
        updateWebGpuPipeline(handle, pipeline.effect.shaderMode, pipeline.upscaler.shaderMode)

    override fun draw(viewport: Viewport): Boolean = drawWebGpuFrame(handle, viewport.left, viewport.top, viewport.width, viewport.height)

    override fun close() = closeWebGpuRenderer(handle)
}

@JsFun(
    """(canvas, presentationShader, intermediateShader, intermediateMode) => {
        const renderer = {
            canvas, status: 0, context: null, device: null,
            presentationPipeline: null, fsrPipeline: null,
            optionsBuffer: null, paletteTexture: null, frameTexture: null,
            upscaledTexture: null, presentationBindGroup: null, fsrBindGroup: null,
            sourceWidth: 0, sourceHeight: 0, outputWidth: 1, outputHeight: 1,
            upscaledWidth: 1, upscaledHeight: 1,
            effect: 0, upscaler: 0, intermediateMode, closed: false,
        };
        renderer.reportShaderMessages = (name, module) => module.getCompilationInfo?.().then(info => {
            for (const message of info.messages) {
                console[message.type === 'error' ? 'error' : 'warn'](
                    'Mood ' + name + ' shader ' + message.type + ' at ' + message.lineNum + ':' +
                        message.linePos + ': ' + message.message,
                );
            }
        });
        renderer.writeOptions = () => renderer.device.queue.writeBuffer(
            renderer.optionsBuffer, 0,
            new Uint32Array([
                renderer.effect,
                renderer.neural?.mode === renderer.upscaler && !renderer.neural.outputReady ? 0 : renderer.upscaler,
                renderer.sourceWidth, renderer.sourceHeight,
                renderer.outputWidth, renderer.outputHeight, 0, 0,
            ]),
        );
        renderer.rebuildBindGroups = () => {
            if (!renderer.frameTexture || !renderer.upscaledTexture) return;
            renderer.presentationBindGroup = renderer.device.createBindGroup({
                label: 'Mood presentation bind group',
                layout: renderer.presentationPipeline.getBindGroupLayout(0),
                entries: [
                    { binding: 0, resource: renderer.frameTexture.createView() },
                    { binding: 1, resource: renderer.paletteTexture.createView() },
                    { binding: 2, resource: { buffer: renderer.optionsBuffer } },
                    { binding: 3, resource: renderer.upscaledTexture.createView() },
                ],
            });
            renderer.fsrBindGroup = renderer.device.createBindGroup({
                label: 'Mood FSR 1 bind group',
                layout: renderer.fsrPipeline.getBindGroupLayout(0),
                entries: [
                    { binding: 0, resource: renderer.frameTexture.createView() },
                    { binding: 1, resource: renderer.paletteTexture.createView() },
                    { binding: 2, resource: { buffer: renderer.optionsBuffer } },
                ],
            });
            renderer.neural?.rebuildOutputBindGroup(renderer);
        };
        renderer.ensureUpscaledTexture = (width, height) => {
            if (renderer.upscaledWidth === width && renderer.upscaledHeight === height && renderer.upscaledTexture) return;
            renderer.upscaledWidth = width;
            renderer.upscaledHeight = height;
            renderer.upscaledTexture?.destroy();
            renderer.upscaledTexture = renderer.device.createTexture({
                label: 'Mood FSR 1 EASU output', size: [width, height], format: 'rgba8unorm',
                usage: GPUTextureUsage.TEXTURE_BINDING | GPUTextureUsage.RENDER_ATTACHMENT,
            });
            renderer.rebuildBindGroups();
        };
        (async () => {
            try {
                if (!navigator.gpu) throw new Error('WebGPU is not available');
                const adapter = await navigator.gpu.requestAdapter({ powerPreference: 'high-performance' });
                if (!adapter) throw new Error('No WebGPU adapter is available');
                const device = await adapter.requestDevice();
                if (renderer.closed) { device.destroy(); return; }
                const context = canvas.getContext('webgpu');
                const format = navigator.gpu.getPreferredCanvasFormat();
                context.configure({ device, format, alphaMode: 'opaque' });
                const presentationModule = device.createShaderModule({ label: 'Mood presentation shader', code: presentationShader });
                const fsrModule = device.createShaderModule({ label: 'Mood intermediate upscaler shader', code: intermediateShader });
                renderer.reportShaderMessages('presentation', presentationModule);
                renderer.reportShaderMessages('intermediate upscaler', fsrModule);
                renderer.device = device;
                renderer.context = context;
                renderer.presentationPipeline = device.createRenderPipeline({
                    label: 'Mood presentation pipeline', layout: 'auto',
                    vertex: { module: presentationModule, entryPoint: 'vertexMain' },
                    fragment: { module: presentationModule, entryPoint: 'fragmentMain', targets: [{ format }] },
                    primitive: { topology: 'triangle-list' },
                });
                renderer.fsrPipeline = device.createRenderPipeline({
                    label: 'Mood FSR 1 EASU pipeline', layout: 'auto',
                    vertex: { module: fsrModule, entryPoint: 'vertexMain' },
                    fragment: { module: fsrModule, entryPoint: 'fragmentMain', targets: [{ format: 'rgba8unorm' }] },
                    primitive: { topology: 'triangle-list' },
                });
                renderer.optionsBuffer = device.createBuffer({
                    label: 'Mood display options', size: 32,
                    usage: GPUBufferUsage.UNIFORM | GPUBufferUsage.COPY_DST,
                });
                renderer.paletteTexture = device.createTexture({
                    label: 'Mood Doom palette', size: [256, 1], format: 'rgba8unorm',
                    usage: GPUTextureUsage.TEXTURE_BINDING | GPUTextureUsage.COPY_DST,
                });
                renderer.upscaledTexture = device.createTexture({
                    label: 'Mood FSR fallback', size: [1, 1], format: 'rgba8unorm',
                    usage: GPUTextureUsage.TEXTURE_BINDING | GPUTextureUsage.RENDER_ATTACHMENT,
                });
                device.lost.then(info => {
                    if (!renderer.closed) { renderer.status = -1; console.error('Mood WebGPU device lost: ' + info.message); }
                });
                renderer.status = 1;
                console.info('Mood graphics backend: WebGPU');
            } catch (error) {
                renderer.status = -1;
                console.warn('Mood WebGPU backend unavailable: ' + error);
            }
        })();
        return renderer;
    }""",
)
private external fun createWebGpuRenderer(
    canvas: HTMLCanvasElement,
    presentationShader: kotlin.js.JsString,
    intermediateShader: kotlin.js.JsString,
    intermediateMode: Int,
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
    renderer.sourceWidth = width;
    renderer.sourceHeight = height;
    renderer.frameTexture = renderer.device.createTexture({
        label: 'Mood Doom framebuffer', size: [width, height], format: 'r8uint',
        usage: GPUTextureUsage.TEXTURE_BINDING | GPUTextureUsage.COPY_DST,
    });
    renderer.rebuildBindGroups();
    renderer.neural?.configureFrame(renderer, width, height);
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
        { texture: renderer.frameTexture }, pixels,
        { bytesPerRow: width, rowsPerImage: height }, [width, height],
    );
    if (renderer.neural?.mode === renderer.upscaler) renderer.neural.captureIndices(pixels);
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
        { texture: renderer.paletteTexture }, palette,
        { bytesPerRow: length }, [256, 1],
    );
    renderer.neural?.capturePalette(palette);
}""",
)
private external fun uploadWebGpuPalette(
    renderer: JsAny,
    pointer: Int,
    length: Int,
)

@JsFun(
    """(renderer, effect, upscaler) => {
    renderer.effect = effect;
    renderer.upscaler = upscaler;
    if (renderer.neural?.mode === upscaler) renderer.neural.initialize(renderer);
}""",
)
private external fun updateWebGpuPipeline(
    renderer: JsAny,
    effect: Int,
    upscaler: Int,
)

@JsFun(
    """(renderer, left, top, width, height) => {
    if (renderer.status !== 1 || renderer.closed) return false;
    try {
        const outputWidth = Math.max(1, Math.round(width));
        const outputHeight = Math.max(1, Math.round(height));
        renderer.outputWidth = outputWidth;
        renderer.outputHeight = outputHeight;
        const neuralActive = renderer.neural?.mode === renderer.upscaler;
        if (neuralActive) {
            renderer.ensureUpscaledTexture(renderer.sourceWidth * 3, renderer.sourceHeight * 3);
        } else if (renderer.upscaler === renderer.intermediateMode) {
            renderer.ensureUpscaledTexture(outputWidth, outputHeight);
        }
        const encoder = renderer.device.createCommandEncoder({ label: 'Mood frame commands' });
        if (neuralActive) {
            renderer.neural.encode(renderer, encoder);
        } else if (renderer.upscaler === renderer.intermediateMode) {
            const fsrPass = encoder.beginRenderPass({ colorAttachments: [{
                view: renderer.upscaledTexture.createView(), clearValue: { r: 0, g: 0, b: 0, a: 1 },
                loadOp: 'clear', storeOp: 'store',
            }] });
            fsrPass.setPipeline(renderer.fsrPipeline);
            fsrPass.setBindGroup(0, renderer.fsrBindGroup);
            fsrPass.draw(3);
            fsrPass.end();
        }
        renderer.writeOptions();
        const pass = encoder.beginRenderPass({ colorAttachments: [{
            view: renderer.context.getCurrentTexture().createView(), clearValue: { r: 0, g: 0, b: 0, a: 1 },
            loadOp: 'clear', storeOp: 'store',
        }] });
        pass.setViewport(left, top, Math.max(width, 1), Math.max(height, 1), 0, 1);
        pass.setPipeline(renderer.presentationPipeline);
        pass.setBindGroup(0, renderer.presentationBindGroup);
        pass.draw(3);
        pass.end();
        renderer.device.queue.submit([encoder.finish()]);
        if (neuralActive) renderer.neural.scheduleWebNN(renderer);
        return true;
    } catch (error) {
        renderer.status = -1;
        console.error('Mood WebGPU backend failed while rendering: ' + error);
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
    renderer.neural?.close();
    renderer.frameTexture?.destroy();
    renderer.upscaledTexture?.destroy();
    renderer.paletteTexture?.destroy();
    renderer.optionsBuffer?.destroy();
    renderer.device?.destroy();
}""",
)
private external fun closeWebGpuRenderer(renderer: JsAny)

private const val WEBGPU_READY = 1
private const val WEBGPU_FAILED = -1
