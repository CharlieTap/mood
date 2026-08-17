package com.tap.mood.graphics.backend.webgpu

import android.util.Log
import android.view.Surface
import androidx.webgpu.BackendType
import androidx.webgpu.BufferUsage
import androidx.webgpu.DeviceLostCallback
import androidx.webgpu.FeatureLevel
import androidx.webgpu.GPU
import androidx.webgpu.GPUAdapter
import androidx.webgpu.GPUBindGroup
import androidx.webgpu.GPUBindGroupDescriptor
import androidx.webgpu.GPUBindGroupEntry
import androidx.webgpu.GPUBuffer
import androidx.webgpu.GPUBufferDescriptor
import androidx.webgpu.GPUColor
import androidx.webgpu.GPUColorTargetState
import androidx.webgpu.GPUDevice
import androidx.webgpu.GPUDeviceDescriptor
import androidx.webgpu.GPUExtent3D
import androidx.webgpu.GPUFragmentState
import androidx.webgpu.GPUInstance
import androidx.webgpu.GPUPrimitiveState
import androidx.webgpu.GPURenderPassColorAttachment
import androidx.webgpu.GPURenderPassDescriptor
import androidx.webgpu.GPURenderPipeline
import androidx.webgpu.GPURenderPipelineDescriptor
import androidx.webgpu.GPURequestAdapterOptions
import androidx.webgpu.GPURequestCallback
import androidx.webgpu.GPUShaderModule
import androidx.webgpu.GPUShaderModuleDescriptor
import androidx.webgpu.GPUShaderSourceWGSL
import androidx.webgpu.GPUSurface
import androidx.webgpu.GPUSurfaceConfiguration
import androidx.webgpu.GPUSurfaceDescriptor
import androidx.webgpu.GPUSurfaceSourceAndroidNativeWindow
import androidx.webgpu.GPUTexelCopyBufferLayout
import androidx.webgpu.GPUTexelCopyTextureInfo
import androidx.webgpu.GPUTexture
import androidx.webgpu.GPUTextureDescriptor
import androidx.webgpu.GPUTextureView
import androidx.webgpu.GPUVertexState
import androidx.webgpu.LoadOp
import androidx.webgpu.PresentMode
import androidx.webgpu.PrimitiveTopology
import androidx.webgpu.StoreOp
import androidx.webgpu.SurfaceGetCurrentTextureStatus
import androidx.webgpu.TextureFormat
import androidx.webgpu.TextureUsage
import androidx.webgpu.UncapturedErrorCallback
import androidx.webgpu.helper.Util
import androidx.webgpu.helper.initLibrary
import com.tap.mood.graphics.Viewport
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import kotlin.math.max
import kotlin.math.roundToInt

internal class AndroidWebGpuDriver private constructor(
    private var androidSurface: Surface?,
    private val webGpu: AndroidWebGpuContext,
    private val presentationShaderModule: GPUShaderModule,
    private val presentationPipeline: GPURenderPipeline,
    private val fsrShaderModule: GPUShaderModule,
    private val fsrPipeline: GPURenderPipeline,
    private val optionsBuffer: GPUBuffer,
    private val paletteTexture: GPUTexture,
    private val paletteView: GPUTextureView,
    private var upscaledTexture: GPUTexture,
    private var upscaledView: GPUTextureView,
    private val surfaceFormat: Int,
    private val presentMode: Int,
    override val backendLabel: String,
    initialPipeline: WebGpuPipeline,
) : WebGpuDriver {
    private val device = webGpu.device
    private val paletteUpload = ByteBuffer.allocateDirect(PALETTE_BYTE_COUNT)
    private val optionsUpload = ByteBuffer.allocateDirect(OPTIONS_BYTE_COUNT).order(ByteOrder.nativeOrder())
    private var frameUpload = ByteBuffer.allocateDirect(0)
    private var frameTexture: GPUTexture? = null
    private var frameView: GPUTextureView? = null
    private var presentationBindGroup: GPUBindGroup? = null
    private var fsrBindGroup: GPUBindGroup? = null
    private var sourceWidth = 0
    private var sourceHeight = 0
    private var outputWidth = 1
    private var outputHeight = 1
    private var upscaledWidth = 1
    private var upscaledHeight = 1
    private var pipeline = initialPipeline
    private var targetWidth = 0
    private var targetHeight = 0
    private var closed = false
    private var resourcesClosed = false
    private val completionCallbacks = ConcurrentLinkedQueue<Runnable>()
    private val framesAwaitingFence = ArrayList<SubmittedFrameResources>(COMPLETION_BATCH_SIZE)
    private val pendingFrameCount = AtomicInteger()
    private val submissionError = AtomicReference<Exception?>(null)
    private val completionExecutor = Executor { callback -> completionCallbacks.add(callback) }

    @Synchronized
    override fun configureTarget(
        width: Int,
        height: Int,
    ) {
        val surface = webGpu.surface ?: return
        if (closed || width <= 0 || height <= 0 || (width == targetWidth && height == targetHeight)) return
        surface.configure(
            GPUSurfaceConfiguration(
                device = device,
                width = width,
                height = height,
                format = surfaceFormat,
                presentMode = presentMode,
            ),
        )
        targetWidth = width
        targetHeight = height
    }

    @Synchronized
    fun attachSurface(
        nextSurface: Surface,
        width: Int,
        height: Int,
    ): Boolean =
        runCatching {
            if (closed || !nextSurface.isValid) return false
            if (androidSurface == null) {
                webGpu.attachSurface(nextSurface)
                androidSurface = nextSurface
                targetWidth = 0
                targetHeight = 0
            }
            configureTarget(width, height)
            true
        }.getOrElse { error ->
            Log.e(TAG, "Unable to attach Android WebGPU surface", error)
            false
        }

    @Synchronized
    fun detachSurface() {
        if (closed || androidSurface == null) return
        runCatching { awaitSubmittedWork() }
            .onFailure { error -> Log.w(TAG, "WebGPU completion failed while detaching surface", error) }
        runCatching { webGpu.detachSurface() }
        androidSurface = null
        targetWidth = 0
        targetHeight = 0
    }

    @Synchronized
    override fun configureFrame(
        width: Int,
        height: Int,
    ) {
        presentationBindGroup?.close()
        presentationBindGroup = null
        fsrBindGroup?.close()
        fsrBindGroup = null
        frameView?.close()
        frameTexture?.close()
        frameTexture =
            device.createTexture(
                GPUTextureDescriptor(
                    usage = TextureUsage.TextureBinding or TextureUsage.CopyDst,
                    size = GPUExtent3D(width, height),
                    label = "Mood Doom framebuffer",
                    format = TextureFormat.R8Uint,
                ),
            )
        frameView = requireNotNull(frameTexture).createView()
        sourceWidth = width
        sourceHeight = height
        rebuildBindGroups()
        frameUpload = ByteBuffer.allocateDirect(width * height)
    }

    private fun rebuildBindGroups() {
        val currentFrameView = frameView ?: return
        presentationBindGroup?.close()
        fsrBindGroup?.close()
        presentationPipeline.getBindGroupLayout(0).use { layout ->
            presentationBindGroup =
                device.createBindGroup(
                    GPUBindGroupDescriptor(
                        layout = layout,
                        label = "Mood presentation bind group",
                        entries =
                            arrayOf(
                                GPUBindGroupEntry(binding = 0, textureView = currentFrameView),
                                GPUBindGroupEntry(binding = 1, textureView = paletteView),
                                GPUBindGroupEntry(binding = 2, buffer = optionsBuffer, size = OPTIONS_BYTE_COUNT.toLong()),
                                GPUBindGroupEntry(binding = 3, textureView = upscaledView),
                            ),
                    ),
                )
        }
        fsrPipeline.getBindGroupLayout(0).use { layout ->
            fsrBindGroup =
                device.createBindGroup(
                    GPUBindGroupDescriptor(
                        layout = layout,
                        label = "Mood FSR 1 bind group",
                        entries =
                            arrayOf(
                                GPUBindGroupEntry(binding = 0, textureView = currentFrameView),
                                GPUBindGroupEntry(binding = 1, textureView = paletteView),
                                GPUBindGroupEntry(binding = 2, buffer = optionsBuffer, size = OPTIONS_BYTE_COUNT.toLong()),
                            ),
                    ),
                )
        }
    }

    @Synchronized
    override fun uploadIndices(
        pixels: ByteArray,
        width: Int,
        height: Int,
    ) {
        frameUpload.clear()
        frameUpload.put(pixels)
        frameUpload.flip()
        device.queue.writeTexture(
            destination = GPUTexelCopyTextureInfo(requireNotNull(frameTexture)),
            data = frameUpload,
            writeSize = GPUExtent3D(width, height),
            dataLayout = GPUTexelCopyBufferLayout(bytesPerRow = width, rowsPerImage = height),
        )
    }

    @Synchronized
    override fun uploadPalette(palette: ByteArray) {
        paletteUpload.clear()
        paletteUpload.put(palette)
        paletteUpload.flip()
        device.queue.writeTexture(
            destination = GPUTexelCopyTextureInfo(paletteTexture),
            data = paletteUpload,
            writeSize = GPUExtent3D(PALETTE_COLOR_COUNT, 1),
            dataLayout = GPUTexelCopyBufferLayout(bytesPerRow = PALETTE_BYTE_COUNT, rowsPerImage = 1),
        )
    }

    @Synchronized
    override fun updatePipeline(pipeline: WebGpuPipeline) {
        this.pipeline = pipeline
    }

    private fun writeOptions() {
        optionsUpload.clear()
        optionsUpload.putInt(pipeline.effect.shaderMode)
        optionsUpload.putInt(pipeline.upscaler.shaderMode)
        optionsUpload.putInt(sourceWidth)
        optionsUpload.putInt(sourceHeight)
        optionsUpload.putInt(outputWidth)
        optionsUpload.putInt(outputHeight)
        while (optionsUpload.position() < OPTIONS_BYTE_COUNT) optionsUpload.put(0)
        optionsUpload.flip()
        device.queue.writeBuffer(optionsBuffer, 0, optionsUpload)
    }

    private fun ensureUpscaledTexture(
        width: Int,
        height: Int,
    ) {
        if (width == upscaledWidth && height == upscaledHeight) return
        if (framesAwaitingFence.isNotEmpty() || pendingFrameCount.get() > 0) awaitSubmittedWork()
        presentationBindGroup?.close()
        presentationBindGroup = null
        upscaledView.close()
        upscaledTexture.close()
        upscaledWidth = width
        upscaledHeight = height
        upscaledTexture =
            device.createTexture(
                GPUTextureDescriptor(
                    usage = TextureUsage.TextureBinding or TextureUsage.RenderAttachment,
                    size = GPUExtent3D(width, height),
                    label = "Mood FSR 1 EASU output",
                    format = TextureFormat.RGBA8Unorm,
                ),
            )
        upscaledView = upscaledTexture.createView()
        rebuildBindGroups()
    }

    @Synchronized
    override fun draw(viewport: Viewport): Boolean =
        runCatching { renderFrame(viewport) }.getOrElse { error ->
            Log.e(TAG, "Android WebGPU backend failed while rendering", error)
            false
        }

    private fun renderFrame(viewport: Viewport): Boolean {
        if (framesAwaitingFence.isEmpty()) pumpEvents()
        outputWidth = max(viewport.width.roundToInt(), 1)
        outputHeight = max(viewport.height.roundToInt(), 1)
        if (pipeline.upscaler.requiresIntermediateTexture) {
            ensureUpscaledTexture(outputWidth, outputHeight)
        }
        writeOptions()
        var submittedFrame: SubmittedFrameResources? = null
        try {
            if (androidSurface?.isValid != true) return false
            val surface = webGpu.surface ?: return false
            var surfaceTexture = surface.getCurrentTexture()
            if (
                surfaceTexture.status == SurfaceGetCurrentTextureStatus.Outdated ||
                surfaceTexture.status == SurfaceGetCurrentTextureStatus.Lost
            ) {
                val reconfigureWidth = targetWidth
                val reconfigureHeight = targetHeight
                targetWidth = 0
                targetHeight = 0
                configureTarget(max(reconfigureWidth, 1), max(reconfigureHeight, 1))
                surfaceTexture = surface.getCurrentTexture()
            }
            check(
                surfaceTexture.status == SurfaceGetCurrentTextureStatus.SuccessOptimal ||
                    surfaceTexture.status == SurfaceGetCurrentTextureStatus.SuccessSuboptimal,
            ) { "Unable to acquire WebGPU surface texture: ${surfaceTexture.status}" }

            val outputTexture = surfaceTexture.texture
            val outputView = outputTexture.createView()
            val encoder = device.createCommandEncoder()
            val fsrPass =
                if (pipeline.upscaler.requiresIntermediateTexture) {
                    encoder
                        .beginRenderPass(
                            GPURenderPassDescriptor(
                                label = "Mood FSR 1 EASU pass",
                                colorAttachments =
                                    arrayOf(
                                        GPURenderPassColorAttachment(
                                            clearValue = GPUColor(0.0, 0.0, 0.0, 1.0),
                                            view = upscaledView,
                                            loadOp = LoadOp.Clear,
                                            storeOp = StoreOp.Store,
                                        ),
                                    ),
                            ),
                        ).also { pass ->
                            pass.setPipeline(fsrPipeline)
                            pass.setBindGroup(0, requireNotNull(fsrBindGroup))
                            pass.draw(3)
                            pass.end()
                        }
                } else {
                    null
                }
            val pass =
                encoder.beginRenderPass(
                    GPURenderPassDescriptor(
                        label = "Mood frame pass",
                        colorAttachments =
                            arrayOf(
                                GPURenderPassColorAttachment(
                                    clearValue = GPUColor(0.0, 0.0, 0.0, 1.0),
                                    view = outputView,
                                    loadOp = LoadOp.Clear,
                                    storeOp = StoreOp.Store,
                                ),
                            ),
                    ),
                )
            pass.setViewport(
                viewport.left,
                viewport.top,
                max(viewport.width, 1f),
                max(viewport.height, 1f),
                0f,
                1f,
            )
            pass.setPipeline(presentationPipeline)
            pass.setBindGroup(0, requireNotNull(presentationBindGroup))
            pass.draw(3)
            pass.end()
            val commands = encoder.finish()
            val resources = arrayListOf<AutoCloseable>(commands, pass, encoder, outputView, outputTexture)
            fsrPass?.let(resources::add)
            val frameResources = SubmittedFrameResources(*resources.toTypedArray())
            submittedFrame = frameResources
            if (androidSurface?.isValid != true) {
                return false
            }
            device.queue.submit(arrayOf(commands))
            surface.present()
            retainSubmittedFrame(frameResources)
            submittedFrame = null
            return true
        } finally {
            submittedFrame?.close()
        }
    }

    private fun retainSubmittedFrame(resources: SubmittedFrameResources) {
        framesAwaitingFence += resources
        if (framesAwaitingFence.size >= COMPLETION_BATCH_SIZE) flushCompletionBatch()
    }

    private fun flushCompletionBatch() {
        if (framesAwaitingFence.isEmpty()) return
        val resources = framesAwaitingFence.toTypedArray()
        framesAwaitingFence.clear()
        pendingFrameCount.addAndGet(resources.size)
        device.queue.onSubmittedWorkDone(
            completionExecutor,
            object : GPURequestCallback<Unit> {
                override fun onResult(result: Unit) {
                    resources.forEach(SubmittedFrameResources::close)
                    pendingFrameCount.addAndGet(-resources.size)
                }

                override fun onError(exception: Exception) {
                    submissionError.compareAndSet(null, exception)
                    resources.forEach(SubmittedFrameResources::close)
                    pendingFrameCount.addAndGet(-resources.size)
                }
            },
        )
    }

    private fun pumpEvents() {
        webGpu.instance.processEvents()
        while (true) completionCallbacks.poll()?.run() ?: break
        submissionError.getAndSet(null)?.let { error -> throw error }
    }

    private fun awaitSubmittedWork() {
        flushCompletionBatch()
        val completed = AtomicBoolean(false)
        val failure = AtomicReference<Exception?>(null)
        device.queue.onSubmittedWorkDone(
            DIRECT_EXECUTOR,
            object : GPURequestCallback<Unit> {
                override fun onResult(result: Unit) {
                    completed.set(true)
                }

                override fun onError(exception: Exception) {
                    failure.set(exception)
                    completed.set(true)
                }
            },
        )
        while (!completed.get()) {
            pumpEvents()
            if (!completed.get()) LockSupport.parkNanos(COMPLETION_POLL_NANOS)
        }
        pumpEvents()
        failure.get()?.let { error -> throw error }
        check(pendingFrameCount.get() == 0) {
            "WebGPU queue became idle with frame resources still retained"
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        runCatching(::awaitSubmittedWork)
            .onFailure { error -> Log.w(TAG, "WebGPU completion failed during teardown", error) }
        closeResources()
    }

    @Synchronized
    private fun closeResources() {
        if (resourcesClosed) return
        resourcesClosed = true
        presentationBindGroup?.close()
        fsrBindGroup?.close()
        frameView?.close()
        frameTexture?.close()
        paletteView.close()
        paletteTexture.close()
        upscaledView.close()
        upscaledTexture.close()
        optionsBuffer.close()
        fsrPipeline.close()
        presentationPipeline.close()
        fsrShaderModule.close()
        presentationShaderModule.close()
        webGpu.close()
    }

    internal companion object {
        private const val TAG = "MoodWebGPU"
        private const val PALETTE_COLOR_COUNT = 256
        private const val PALETTE_BYTE_COUNT = PALETTE_COLOR_COUNT * 4
        private const val OPTIONS_BYTE_COUNT = 32
        private const val COMPLETION_BATCH_SIZE = 4
        private const val COMPLETION_POLL_NANOS = 1_000_000L
        private val DIRECT_EXECUTOR = Executor(Runnable::run)

        fun create(
            surface: Surface,
            width: Int,
            height: Int,
            features: WebGpuFeatureRegistry,
        ): AndroidWebGpuDriver {
            val webGpu = AndroidWebGpuContext.create(surface)
            val device = webGpu.device
            val surfaceCapabilities = requireNotNull(webGpu.surface).getCapabilities(webGpu.adapter)
            val surfaceFormat =
                surfaceCapabilities.formats.firstOrNull()
                    ?: error("Android WebGPU surface exposes no renderable texture formats")
            val presentMode =
                when {
                    PresentMode.Fifo in surfaceCapabilities.presentModes -> PresentMode.Fifo
                    else -> surfaceCapabilities.presentModes.firstOrNull() ?: PresentMode.Undefined
                }
            val presentationModule =
                device.createShaderModule(
                    GPUShaderModuleDescriptor(
                        label = "Mood presentation shader",
                        shaderSourceWGSL = GPUShaderSourceWGSL(features.presentationShader()),
                    ),
                )
            val pipeline =
                device.createRenderPipeline(
                    GPURenderPipelineDescriptor(
                        label = "Mood presentation pipeline",
                        vertex = GPUVertexState(presentationModule, "vertexMain"),
                        primitive = GPUPrimitiveState(PrimitiveTopology.TriangleList),
                        fragment =
                            GPUFragmentState(
                                module = presentationModule,
                                entryPoint = "fragmentMain",
                                targets = arrayOf(GPUColorTargetState(surfaceFormat)),
                            ),
                    ),
                )
            val intermediateUpscaler =
                requireNotNull(features.intermediateUpscaler) {
                    "Android WebGPU requires exactly one intermediate upscaler"
                }
            val fsrModule =
                device.createShaderModule(
                    GPUShaderModuleDescriptor(
                        label = "Mood intermediate upscaler shader",
                        shaderSourceWGSL =
                            GPUShaderSourceWGSL(requireNotNull(intermediateUpscaler.implementation.intermediateShader)),
                    ),
                )
            val fsrPipeline =
                device.createRenderPipeline(
                    GPURenderPipelineDescriptor(
                        label = "Mood FSR 1 EASU pipeline",
                        vertex = GPUVertexState(fsrModule, "vertexMain"),
                        primitive = GPUPrimitiveState(PrimitiveTopology.TriangleList),
                        fragment =
                            GPUFragmentState(
                                module = fsrModule,
                                entryPoint = "fragmentMain",
                                targets = arrayOf(GPUColorTargetState(TextureFormat.RGBA8Unorm)),
                            ),
                    ),
                )
            val options =
                device.createBuffer(
                    GPUBufferDescriptor(
                        usage = BufferUsage.Uniform or BufferUsage.CopyDst,
                        size = OPTIONS_BYTE_COUNT.toLong(),
                        label = "Mood display options",
                    ),
                )
            val palette =
                device.createTexture(
                    GPUTextureDescriptor(
                        usage = TextureUsage.TextureBinding or TextureUsage.CopyDst,
                        size = GPUExtent3D(PALETTE_COLOR_COUNT, 1),
                        label = "Mood Doom palette",
                        format = TextureFormat.RGBA8Unorm,
                    ),
                )
            val upscaledFallback =
                device.createTexture(
                    GPUTextureDescriptor(
                        usage = TextureUsage.TextureBinding or TextureUsage.RenderAttachment,
                        size = GPUExtent3D(1, 1),
                        label = "Mood upscaled fallback",
                        format = TextureFormat.RGBA8Unorm,
                    ),
                )
            return AndroidWebGpuDriver(
                androidSurface = surface,
                webGpu = webGpu,
                presentationShaderModule = presentationModule,
                presentationPipeline = pipeline,
                fsrShaderModule = fsrModule,
                fsrPipeline = fsrPipeline,
                optionsBuffer = options,
                paletteTexture = palette,
                paletteView = palette.createView(),
                upscaledTexture = upscaledFallback,
                upscaledView = upscaledFallback.createView(),
                surfaceFormat = surfaceFormat,
                presentMode = presentMode,
                backendLabel =
                    "Dawn/Vulkan, ${TextureFormat.toString(surfaceFormat)}, " +
                        PresentMode.toString(presentMode),
                initialPipeline =
                    WebGpuPipeline(
                        upscaler = features.upscalers.first(),
                        effect = features.effects.first(),
                    ),
            ).apply { configureTarget(width, height) }
        }
    }

    private class SubmittedFrameResources(
        private vararg val resources: AutoCloseable,
    ) : AutoCloseable {
        private var closed = false

        @Synchronized
        override fun close() {
            if (closed) return
            closed = true
            resources.forEach { resource -> runCatching { resource.close() } }
        }
    }
}

private class AndroidWebGpuContext private constructor(
    val instance: GPUInstance,
    val adapter: GPUAdapter,
    val device: GPUDevice,
    var surface: GPUSurface?,
) : AutoCloseable {
    fun attachSurface(androidSurface: Surface) {
        check(surface == null) { "A WebGPU surface is already attached" }
        surface = instance.createAndroidSurface(androidSurface)
    }

    fun detachSurface() {
        surface?.let { currentSurface ->
            runCatching { currentSurface.unconfigure() }
            currentSurface.close()
        }
        surface = null
    }

    override fun close() {
        // AndroidX alpha05 deliberately cannot close GPUDevice yet (b/428866400).
        detachSurface()
        adapter.close()
        instance.close()
    }

    companion object {
        private val DIRECT_EXECUTOR = Executor(Runnable::run)

        fun create(androidSurface: Surface): AndroidWebGpuContext {
            initLibrary()
            val instance = GPU.createInstance()
            val surface = instance.createAndroidSurface(androidSurface)
            try {
                val adapter =
                    instance.requestBlocking<GPUAdapter> { callback ->
                        instance.requestAdapter(
                            DIRECT_EXECUTOR,
                            GPURequestAdapterOptions(
                                featureLevel = FeatureLevel.Core,
                                backendType = BackendType.Vulkan,
                                compatibleSurface = surface,
                            ),
                            callback,
                        )
                    }
                try {
                    val device =
                        instance.requestBlocking<GPUDevice> { callback ->
                            adapter.requestDevice(
                                DIRECT_EXECUTOR,
                                GPUDeviceDescriptor(
                                    deviceLostCallbackExecutor = DIRECT_EXECUTOR,
                                    uncapturedErrorCallbackExecutor = DIRECT_EXECUTOR,
                                    deviceLostCallback =
                                        DeviceLostCallback { _, reason, message ->
                                            Log.e(
                                                "MoodWebGPU",
                                                "WebGPU device lost ($reason): $message",
                                            )
                                        },
                                    uncapturedErrorCallback =
                                        UncapturedErrorCallback { _, type, message ->
                                            Log.e(
                                                "MoodWebGPU",
                                                "WebGPU uncaptured error ($type): $message",
                                            )
                                        },
                                ),
                                callback,
                            )
                        }
                    return AndroidWebGpuContext(instance, adapter, device, surface)
                } catch (error: Throwable) {
                    adapter.close()
                    throw error
                }
            } catch (error: Throwable) {
                surface.close()
                instance.close()
                throw error
            }
        }

        private fun GPUInstance.createAndroidSurface(androidSurface: Surface): GPUSurface =
            createSurface(
                GPUSurfaceDescriptor(
                    surfaceSourceAndroidNativeWindow =
                        GPUSurfaceSourceAndroidNativeWindow(
                            Util.windowFromSurface(androidSurface),
                        ),
                ),
            )

        private fun <T> GPUInstance.requestBlocking(request: (GPURequestCallback<T>) -> Unit): T {
            val resultRef = AtomicReference<T?>(null)
            val failure = AtomicReference<Exception?>(null)
            val completed = AtomicBoolean(false)
            request(
                object : GPURequestCallback<T> {
                    override fun onResult(result: T) {
                        resultRef.set(result)
                        completed.set(true)
                    }

                    override fun onError(exception: Exception) {
                        failure.set(exception)
                        completed.set(true)
                    }
                },
            )
            while (!completed.get()) {
                processEvents()
                Thread.yield()
            }
            failure.get()?.let { throw it }
            return checkNotNull(resultRef.get()) { "WebGPU request completed without a result" }
        }
    }
}
