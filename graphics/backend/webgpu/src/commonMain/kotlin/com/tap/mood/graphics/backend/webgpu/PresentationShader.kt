package com.tap.mood.graphics.backend.webgpu

internal fun WebGpuFeatureRegistry.presentationShader(): String =
    wgsl {
        section(PRESENTATION_RESOURCES)
        section(FULLSCREEN_TRIANGLE_VERTEX)
        section(INDEXED_FRAME_SAMPLING)
        addUpscalers(upscalers)
        addEffects(effects)
    }

private data class ShaderBranch(
    val mode: Int,
    val expression: String,
)

private class WgslBuilder {
    private val source = StringBuilder()

    fun section(shader: String) {
        val normalized = shader.trimIndent().trim()
        if (normalized.isEmpty()) return
        if (source.isNotEmpty()) source.appendLine()
        source.appendLine(normalized)
    }

    fun sections(shaders: Iterable<String>) {
        shaders.forEach(::section)
    }

    fun addUpscalers(upscalers: List<RegisteredWebGpuUpscaler>) {
        sections(upscalers.map { it.implementation.shaderSource })
        dispatch(
            signature = "fn baseColor(uv: vec2f) -> vec4f",
            selector = "options.upscaler",
            branches = upscalers.map { ShaderBranch(it.shaderMode, it.implementation.sampleExpression) },
            fallback = "indexedColor(vec2i(floor(uv * vec2f(textureDimensions(frameTexture)))))",
        )
    }

    fun addEffects(effects: List<RegisteredWebGpuDisplayEffect>) {
        sections(effects.map { it.implementation.shaderSource })
        dispatch(
            signature = "@fragment\nfn fragmentMain(input: VertexOutput) -> @location(0) vec4f",
            selector = "options.effect",
            branches = effects.map { ShaderBranch(it.shaderMode, it.implementation.applyExpression) },
            fallback = "baseColor(input.uv)",
        )
    }

    fun dispatch(
        signature: String,
        selector: String,
        branches: List<ShaderBranch>,
        fallback: String,
    ) {
        section(
            buildString {
                appendLine("$signature {")
                branches.forEach { branch ->
                    appendLine("    if ($selector == ${branch.mode}u) { return ${branch.expression}; }")
                }
                appendLine("    return $fallback;")
                append("}")
            },
        )
    }

    fun build(): String = source.toString().trimEnd()
}

private fun wgsl(content: WgslBuilder.() -> Unit): String =
    WgslBuilder()
        .apply(content)
        .build()

private const val PRESENTATION_RESOURCES =
    """
    struct Options {
        effect: u32,
        upscaler: u32,
        sourceWidth: u32,
        sourceHeight: u32,
        outputWidth: u32,
        outputHeight: u32,
        padding0: u32,
        padding1: u32,
    };

    @group(0) @binding(0) var frameTexture: texture_2d<u32>;
    @group(0) @binding(1) var paletteTexture: texture_2d<f32>;
    @group(0) @binding(2) var<uniform> options: Options;
    @group(0) @binding(3) var upscaledTexture: texture_2d<f32>;

    struct VertexOutput {
        @builtin(position) position: vec4f,
        @location(0) uv: vec2f,
    };
    """

private const val FULLSCREEN_TRIANGLE_VERTEX =
    """
    @vertex
    fn vertexMain(@builtin(vertex_index) vertexIndex: u32) -> VertexOutput {
        let positions = array(vec2f(-1.0, -1.0), vec2f(3.0, -1.0), vec2f(-1.0, 3.0));
        let coordinates = array(vec2f(0.0, 1.0), vec2f(2.0, 1.0), vec2f(0.0, -1.0));
        var output: VertexOutput;
        output.position = vec4f(positions[vertexIndex], 0.0, 1.0);
        output.uv = coordinates[vertexIndex];
        return output;
    }
    """

private const val INDEXED_FRAME_SAMPLING =
    """
    fn indexedColor(pixel: vec2i) -> vec4f {
        let size = vec2i(textureDimensions(frameTexture));
        let safePixel = clamp(pixel, vec2i(0), size - vec2i(1));
        let paletteIndex = textureLoad(frameTexture, safePixel, 0).r;
        return textureLoad(paletteTexture, vec2i(i32(paletteIndex), 0), 0);
    }

    fn smoothIndexedColor(uv: vec2f) -> vec4f {
        let size = vec2f(textureDimensions(frameTexture));
        let position = uv * size - vec2f(0.5);
        let base = vec2i(floor(position));
        let fraction = fract(position);
        let top = mix(indexedColor(base), indexedColor(base + vec2i(1, 0)), fraction.x);
        let bottom = mix(indexedColor(base + vec2i(0, 1)), indexedColor(base + vec2i(1, 1)), fraction.x);
        return mix(top, bottom, fraction.y);
    }
    """
