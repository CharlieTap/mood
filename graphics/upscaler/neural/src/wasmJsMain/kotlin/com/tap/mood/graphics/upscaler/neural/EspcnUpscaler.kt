@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.tap.mood.graphics.upscaler.neural

import com.tap.mood.graphics.UpscalerId
import com.tap.mood.graphics.UpscalerIds
import com.tap.mood.graphics.UpscalerOption
import com.tap.mood.graphics.backend.webgpu.BrowserWebGpuUpscaler
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.StringKey
import dev.zacsweers.metro.binding
import kotlin.js.JsAny

@StringKey(UpscalerIds.NEURAL)
@ContributesIntoMap(AppScope::class, binding<com.tap.mood.graphics.backend.webgpu.WebGpuUpscaler>())
@Inject
class EspcnUpscaler : BrowserWebGpuUpscaler {
    override val option =
        UpscalerOption(
            id = UpscalerId(UpscalerIds.NEURAL),
            displayName = "Neural (Experimental)",
        )
    override val order = 300
    override val shaderSource =
        """
        fn neuralColor(uv: vec2f) -> vec4f {
            let size = vec2i(textureDimensions(upscaledTexture));
            let pixel = clamp(vec2i(floor(uv * vec2f(size))), vec2i(0), size - vec2i(1));
            return textureLoad(upscaledTexture, pixel, 0);
        }
        """
    override val sampleExpression = "neuralColor(uv)"

    override fun install(
        renderer: JsAny,
        shaderMode: Int,
    ) = installEspcnUpscaler(renderer, shaderMode, MODEL_URL)
}

@JsFun(
    """(renderer, mode, modelUrl) => {
    const neural = {
        mode,
        modelUrl,
        backend: 'initializing',
        outputReady: false,
        busy: false,
        width: 0,
        height: 0,
        weights: null,
        indices: null,
        palette: null,
        featureA: null,
        featureB: null,
        weightsBuffer: null,
        optionsBuffer: null,
        preprocessPipeline: null,
        convolutionPipelines: [],
        reconstructPipeline: null,
        preprocessBindGroup: null,
        convolutionBindGroups: [],
        reconstructBindGroup: null,
        webnnContext: null,
        webnnGraph: null,
        webnnInput: null,
        webnnOutput: null,
        webnnGeneration: 0,
        initialization: null,

        captureIndices(pixels) {
            this.indices = Uint8Array.from(pixels);
        },

        capturePalette(palette) {
            this.palette = Uint8Array.from(palette);
        },

        initialize(owner) {
            if (this.initialization) return this.initialization;
            this.initialization = (async () => {
                const response = await fetch(new URL(this.modelUrl, document.baseURI));
                if (!response.ok) throw new Error('Unable to load ESPCN weights: HTTP ' + response.status);
                const bytes = await response.arrayBuffer();
                if (bytes.byteLength !== 238628) throw new Error('Unexpected ESPCN weight length: ' + bytes.byteLength);
                this.weights = new Float32Array(bytes);
                this.createPipelines(owner);
                this.backend = 'webgpu';
                if (owner.sourceWidth > 0 && owner.sourceHeight > 0) {
                    this.configureFrame(owner, owner.sourceWidth, owner.sourceHeight);
                }

                if (!navigator.ml) {
                    console.info('Mood neural upscaler: WebGPU compute (ESPCN x3)');
                    return;
                }
                try {
                    this.webnnContext = await navigator.ml.createContext(owner.device);
                    console.info('Mood neural upscaler: WebNN available; graph will compile for the Doom frame size');
                } catch (error) {
                    try {
                        this.webnnContext = await navigator.ml.createContext({
                            deviceType: 'gpu', powerPreference: 'high-performance',
                        });
                        console.info('Mood neural upscaler: WebNN GPU context available');
                    } catch (fallbackError) {
                        console.info('Mood neural upscaler: WebNN unavailable, using WebGPU compute (' + fallbackError + ')');
                    }
                }
                if (this.webnnContext && this.width > 0 && this.height > 0) {
                    this.compileWebNN(this.width, this.height);
                }
            })().catch(error => {
                this.backend = 'failed';
                console.warn('Mood neural upscaler failed to initialize: ' + error);
            });
            return this.initialization;
        },

        createPipelines(owner) {
            const device = owner.device;
            const preprocessModule = device.createShaderModule({
                label: 'Mood ESPCN luminance shader',
                code: `
                    struct NeuralOptions { width: u32, height: u32, outputLayout: u32, padding: u32 };
                    @group(0) @binding(0) var frameTexture: texture_2d<u32>;
                    @group(0) @binding(1) var paletteTexture: texture_2d<f32>;
                    @group(0) @binding(2) var<storage, read_write> output: array<f32>;
                    @group(0) @binding(3) var<uniform> options: NeuralOptions;
                    @compute @workgroup_size(8, 8)
                    fn main(@builtin(global_invocation_id) id: vec3u) {
                        if (id.x >= options.width || id.y >= options.height) { return; }
                        let paletteIndex = textureLoad(frameTexture, vec2i(id.xy), 0).r;
                        let color = textureLoad(paletteTexture, vec2i(i32(paletteIndex), 0), 0).rgb;
                        output[id.y * options.width + id.x] = dot(color, vec3f(0.299, 0.587, 0.114));
                    }
                `,
            });
            this.preprocessPipeline = device.createComputePipeline({
                label: 'Mood ESPCN luminance pipeline', layout: 'auto',
                compute: { module: preprocessModule, entryPoint: 'main' },
            });

            const convolution = (inputChannels, outputChannels, kernelSize, weightOffset, biasOffset, relu) => `
                struct NeuralOptions { width: u32, height: u32, outputLayout: u32, padding: u32 };
                @group(0) @binding(0) var<storage, read> input: array<f32>;
                @group(0) @binding(1) var<storage, read> weights: array<f32>;
                @group(0) @binding(2) var<storage, read_write> output: array<f32>;
                @group(0) @binding(3) var<uniform> options: NeuralOptions;
                @compute @workgroup_size(8, 8, 1)
                fn main(@builtin(global_invocation_id) id: vec3u) {
                    if (id.x >= options.width || id.y >= options.height || id.z >= ${'$'}{outputChannels}u) { return; }
                    var sum = weights[${'$'}{biasOffset}u + id.z];
                    for (var inputChannel = 0u; inputChannel < ${'$'}{inputChannels}u; inputChannel++) {
                        for (var kernelY = 0u; kernelY < ${'$'}{kernelSize}u; kernelY++) {
                            let sourceY = i32(id.y + kernelY) - ${'$'}{Math.floor(kernelSize / 2)};
                            if (sourceY < 0 || sourceY >= i32(options.height)) { continue; }
                            for (var kernelX = 0u; kernelX < ${'$'}{kernelSize}u; kernelX++) {
                                let sourceX = i32(id.x + kernelX) - ${'$'}{Math.floor(kernelSize / 2)};
                                if (sourceX < 0 || sourceX >= i32(options.width)) { continue; }
                                let inputIndex =
                                    inputChannel * options.width * options.height +
                                    u32(sourceY) * options.width + u32(sourceX);
                                let weightIndex = ${'$'}{weightOffset}u +
                                    ((id.z * ${'$'}{inputChannels}u + inputChannel) * ${'$'}{kernelSize}u + kernelY) *
                                        ${'$'}{kernelSize}u + kernelX;
                                sum += input[inputIndex] * weights[weightIndex];
                            }
                        }
                    }
                    let outputIndex = id.z * options.width * options.height + id.y * options.width + id.x;
                    output[outputIndex] = ${'$'}{relu ? 'max(sum, 0.0)' : 'sum'};
                }
            `;
            const layers = [
                [1, 64, 5, 0, 1600, true],
                [64, 64, 3, 1664, 38528, true],
                [64, 32, 3, 38592, 57024, true],
                [32, 9, 3, 57056, 59648, false],
            ];
            this.convolutionPipelines = layers.map((layer, index) => {
                const module = device.createShaderModule({
                    label: 'Mood ESPCN convolution ' + (index + 1), code: convolution(...layer),
                });
                return device.createComputePipeline({
                    label: 'Mood ESPCN convolution ' + (index + 1), layout: 'auto',
                    compute: { module, entryPoint: 'main' },
                });
            });

            const reconstructModule = device.createShaderModule({
                label: 'Mood ESPCN reconstruction shader',
                code: `
                    struct NeuralOptions { width: u32, height: u32, outputLayout: u32, padding: u32 };
                    @group(0) @binding(0) var frameTexture: texture_2d<u32>;
                    @group(0) @binding(1) var paletteTexture: texture_2d<f32>;
                    @group(0) @binding(2) var<storage, read> luminance: array<f32>;
                    @group(0) @binding(3) var<uniform> options: NeuralOptions;
                    struct VertexOutput {
                        @builtin(position) position: vec4f,
                        @location(0) uv: vec2f,
                    };
                    @vertex fn vertexMain(@builtin(vertex_index) index: u32) -> VertexOutput {
                        let positions = array(vec2f(-1.0, -1.0), vec2f(3.0, -1.0), vec2f(-1.0, 3.0));
                        let coordinates = array(vec2f(0.0, 1.0), vec2f(2.0, 1.0), vec2f(0.0, -1.0));
                        var output: VertexOutput;
                        output.position = vec4f(positions[index], 0.0, 1.0);
                        output.uv = coordinates[index];
                        return output;
                    }
                    fn indexedColor(pixel: vec2i) -> vec3f {
                        let size = vec2i(textureDimensions(frameTexture));
                        let safePixel = clamp(pixel, vec2i(0), size - vec2i(1));
                        let paletteIndex = textureLoad(frameTexture, safePixel, 0).r;
                        return textureLoad(paletteTexture, vec2i(i32(paletteIndex), 0), 0).rgb;
                    }
                    fn smoothColor(uv: vec2f) -> vec3f {
                        let size = vec2f(textureDimensions(frameTexture));
                        let position = uv * size - vec2f(0.5);
                        let base = vec2i(floor(position));
                        let fraction = fract(position);
                        let top = mix(indexedColor(base), indexedColor(base + vec2i(1, 0)), fraction.x);
                        let bottom = mix(indexedColor(base + vec2i(0, 1)), indexedColor(base + vec2i(1, 1)), fraction.x);
                        return mix(top, bottom, fraction.y);
                    }
                    @fragment fn fragmentMain(input: VertexOutput) -> @location(0) vec4f {
                        let outputWidth = options.width * 3u;
                        let outputHeight = options.height * 3u;
                        let pixel = min(vec2u(input.position.xy), vec2u(outputWidth - 1u, outputHeight - 1u));
                        var outputIndex = pixel.y * outputWidth + pixel.x;
                        if (options.outputLayout == 0u) {
                            let channel = (pixel.y % 3u) * 3u + pixel.x % 3u;
                            outputIndex = channel * options.width * options.height +
                                (pixel.y / 3u) * options.width + pixel.x / 3u;
                        }
                        let y = clamp(luminance[outputIndex], 0.0, 1.0);
                        let source = smoothColor(input.uv);
                        let sourceY = dot(source, vec3f(0.299, 0.587, 0.114));
                        return vec4f(clamp(source + vec3f(y - sourceY), vec3f(0.0), vec3f(1.0)), 1.0);
                    }
                `,
            });
            this.reconstructPipeline = device.createRenderPipeline({
                label: 'Mood ESPCN reconstruction pipeline', layout: 'auto',
                vertex: { module: reconstructModule, entryPoint: 'vertexMain' },
                fragment: {
                    module: reconstructModule, entryPoint: 'fragmentMain',
                    targets: [{ format: 'rgba8unorm' }],
                },
                primitive: { topology: 'triangle-list' },
            });
        },

        configureFrame(owner, width, height) {
            if (!this.weights || (this.width === width && this.height === height)) return;
            this.width = width;
            this.height = height;
            this.outputReady = false;
            this.featureA?.destroy();
            this.featureB?.destroy();
            this.weightsBuffer?.destroy();
            this.optionsBuffer?.destroy();
            const featureBytes = width * height * 64 * 4;
            this.featureA = owner.device.createBuffer({
                label: 'Mood ESPCN features A', size: featureBytes,
                usage: GPUBufferUsage.STORAGE | GPUBufferUsage.COPY_DST,
            });
            this.featureB = owner.device.createBuffer({
                label: 'Mood ESPCN features B', size: featureBytes,
                usage: GPUBufferUsage.STORAGE | GPUBufferUsage.COPY_DST,
            });
            this.weightsBuffer = owner.device.createBuffer({
                label: 'Mood ESPCN weights', size: this.weights.byteLength,
                usage: GPUBufferUsage.STORAGE | GPUBufferUsage.COPY_DST,
            });
            this.optionsBuffer = owner.device.createBuffer({
                label: 'Mood ESPCN options', size: 16,
                usage: GPUBufferUsage.UNIFORM | GPUBufferUsage.COPY_DST,
            });
            owner.device.queue.writeBuffer(this.weightsBuffer, 0, this.weights);
            owner.device.queue.writeBuffer(this.optionsBuffer, 0, new Uint32Array([width, height, 0, 0]));
            this.rebuildComputeBindGroups(owner);
            this.rebuildOutputBindGroup(owner);
            this.compileWebNN(width, height);
        },

        rebuildComputeBindGroups(owner) {
            if (!owner.frameTexture || !this.featureA) return;
            this.preprocessBindGroup = owner.device.createBindGroup({
                layout: this.preprocessPipeline.getBindGroupLayout(0),
                entries: [
                    { binding: 0, resource: owner.frameTexture.createView() },
                    { binding: 1, resource: owner.paletteTexture.createView() },
                    { binding: 2, resource: { buffer: this.featureA } },
                    { binding: 3, resource: { buffer: this.optionsBuffer } },
                ],
            });
            const pairs = [
                [this.featureA, this.featureB],
                [this.featureB, this.featureA],
                [this.featureA, this.featureB],
                [this.featureB, this.featureA],
            ];
            this.convolutionBindGroups = pairs.map((pair, index) => owner.device.createBindGroup({
                layout: this.convolutionPipelines[index].getBindGroupLayout(0),
                entries: [
                    { binding: 0, resource: { buffer: pair[0] } },
                    { binding: 1, resource: { buffer: this.weightsBuffer } },
                    { binding: 2, resource: { buffer: pair[1] } },
                    { binding: 3, resource: { buffer: this.optionsBuffer } },
                ],
            }));
        },

        rebuildOutputBindGroup(owner) {
            if (!owner.frameTexture || !owner.upscaledTexture || !this.featureA || !this.reconstructPipeline) return;
            this.reconstructBindGroup = owner.device.createBindGroup({
                layout: this.reconstructPipeline.getBindGroupLayout(0),
                entries: [
                    { binding: 0, resource: owner.frameTexture.createView() },
                    { binding: 1, resource: owner.paletteTexture.createView() },
                    { binding: 2, resource: { buffer: this.featureA } },
                    { binding: 3, resource: { buffer: this.optionsBuffer } },
                ],
            });
            this.rebuildComputeBindGroups(owner);
        },

        encode(owner, encoder) {
            if (this.backend !== 'webgpu' || !this.preprocessBindGroup || !this.reconstructBindGroup) return;
            owner.device.queue.writeBuffer(this.optionsBuffer, 0, new Uint32Array([this.width, this.height, 0, 0]));
            const preprocess = encoder.beginComputePass({ label: 'Mood ESPCN luminance pass' });
            preprocess.setPipeline(this.preprocessPipeline);
            preprocess.setBindGroup(0, this.preprocessBindGroup);
            preprocess.dispatchWorkgroups(Math.ceil(this.width / 8), Math.ceil(this.height / 8));
            preprocess.end();
            const outputChannels = [64, 64, 32, 9];
            for (let index = 0; index < this.convolutionPipelines.length; index++) {
                const pass = encoder.beginComputePass({ label: 'Mood ESPCN convolution ' + (index + 1) });
                pass.setPipeline(this.convolutionPipelines[index]);
                pass.setBindGroup(0, this.convolutionBindGroups[index]);
                pass.dispatchWorkgroups(Math.ceil(this.width / 8), Math.ceil(this.height / 8), outputChannels[index]);
                pass.end();
            }
            this.encodeReconstruction(owner, encoder);
            this.outputReady = true;
        },

        encodeReconstruction(owner, encoder) {
            if (!this.reconstructBindGroup) return;
            const pass = encoder.beginRenderPass({ colorAttachments: [{
                view: owner.upscaledTexture.createView(), clearValue: { r: 0, g: 0, b: 0, a: 1 },
                loadOp: 'clear', storeOp: 'store',
            }] });
            pass.setPipeline(this.reconstructPipeline);
            pass.setBindGroup(0, this.reconstructBindGroup);
            pass.draw(3);
            pass.end();
        },

        async compileWebNN(width, height) {
            if (!this.webnnContext || !this.weights) return;
            const generation = ++this.webnnGeneration;
            try {
                const context = this.webnnContext;
                const builder = new MLGraphBuilder(context);
                const tensor = (shape, data) => builder.constant({ dataType: 'float32', shape }, data);
                const input = builder.input('input', { dataType: 'float32', shape: [1, 1, height, width] });
                const convolution = (value, weightOffset, weightShape, biasOffset, biasShape, padding, relu) => {
                    const weights = tensor(weightShape, this.weights.subarray(weightOffset, weightOffset + weightShape.reduce((a, b) => a * b)));
                    const bias = tensor(biasShape, this.weights.subarray(biasOffset, biasOffset + biasShape[0]));
                    const result = builder.conv2d(value, weights, {
                        bias, inputLayout: 'nchw', filterLayout: 'oihw', padding: [padding, padding, padding, padding],
                    });
                    return relu ? builder.relu(result) : result;
                };
                let value = convolution(input, 0, [64, 1, 5, 5], 1600, [64], 2, true);
                value = convolution(value, 1664, [64, 64, 3, 3], 38528, [64], 1, true);
                value = convolution(value, 38592, [32, 64, 3, 3], 57024, [32], 1, true);
                value = convolution(value, 57056, [9, 32, 3, 3], 59648, [9], 1, false);
                value = builder.reshape(value, [1, 3, 3, height, width]);
                value = builder.transpose(value, { permutation: [0, 3, 1, 4, 2] });
                value = builder.reshape(value, [1, 1, height * 3, width * 3]);
                const graph = await builder.build({ output: value });
                if (generation !== this.webnnGeneration) return;
                this.webnnGraph = graph;
                if (context.createTensor) {
                    this.webnnInput?.destroy();
                    this.webnnOutput?.destroy();
                    this.webnnInput = await context.createTensor({
                        dataType: 'float32', shape: [1, 1, height, width], writable: true,
                    });
                    this.webnnOutput = await context.createTensor({
                        dataType: 'float32', shape: [1, 1, height * 3, width * 3], readable: true,
                    });
                }
                this.backend = 'webnn';
                console.info('Mood neural upscaler: WebNN ESPCN x3 graph ready');
            } catch (error) {
                this.backend = 'webgpu';
                console.warn('Mood neural upscaler: WebNN graph failed, using WebGPU compute: ' + error);
            }
        },

        scheduleWebNN(owner) {
            if (this.backend !== 'webnn' || this.busy || !this.webnnGraph || !this.indices || !this.palette) return;
            this.busy = true;
            const indices = this.indices;
            const palette = this.palette;
            const input = new Float32Array(this.width * this.height);
            for (let index = 0; index < input.length; index++) {
                const color = indices[index] * 4;
                input[index] =
                    (palette[color] * 0.299 + palette[color + 1] * 0.587 + palette[color + 2] * 0.114) / 255;
            }
            (async () => {
                try {
                    let output;
                    if (this.webnnContext.dispatch && this.webnnInput) {
                        this.webnnContext.writeTensor(this.webnnInput, input);
                        this.webnnContext.dispatch(
                            this.webnnGraph,
                            { input: this.webnnInput },
                            { output: this.webnnOutput },
                        );
                        output = new Float32Array(await this.webnnContext.readTensor(this.webnnOutput));
                    } else {
                        const outputBuffer = new Float32Array(this.width * this.height * 9);
                        const result = await this.webnnContext.compute(
                            this.webnnGraph,
                            { input },
                            { output: outputBuffer },
                        );
                        output = result?.outputs?.output ?? outputBuffer;
                    }
                    if (owner.closed || this.backend !== 'webnn') return;
                    owner.device.queue.writeBuffer(this.featureA, 0, output);
                    owner.device.queue.writeBuffer(
                        this.optionsBuffer, 0, new Uint32Array([this.width, this.height, 1, 0]),
                    );
                    const encoder = owner.device.createCommandEncoder({ label: 'Mood WebNN reconstruction' });
                    this.encodeReconstruction(owner, encoder);
                    owner.device.queue.submit([encoder.finish()]);
                    this.outputReady = true;
                } catch (error) {
                    this.backend = 'webgpu';
                    this.outputReady = false;
                    console.warn('Mood neural upscaler: WebNN dispatch failed, using WebGPU compute: ' + error);
                } finally {
                    this.busy = false;
                }
            })();
        },

        close() {
            this.webnnGeneration++;
            this.webnnInput?.destroy();
            this.webnnOutput?.destroy();
            this.webnnContext?.destroy?.();
            this.featureA?.destroy();
            this.featureB?.destroy();
            this.weightsBuffer?.destroy();
            this.optionsBuffer?.destroy();
        },
    };
    renderer.neural = neural;
}""",
)
private external fun installEspcnUpscaler(
    renderer: JsAny,
    mode: Int,
    modelUrl: String,
)

private const val MODEL_URL =
    "composeResources/com.tap.mood.graphics.upscaler.neural.resources/files/neural/espcn-x3.weights"
