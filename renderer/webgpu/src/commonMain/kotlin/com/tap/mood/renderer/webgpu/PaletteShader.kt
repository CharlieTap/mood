package com.tap.mood.renderer.webgpu

internal const val PALETTE_WEBGPU_SHADER =
    """
    struct Options {
        interpolationEnabled: u32,
    };

    @group(0) @binding(0) var frameTexture: texture_2d<u32>;
    @group(0) @binding(1) var paletteTexture: texture_2d<f32>;
    @group(0) @binding(2) var<uniform> options: Options;

    struct VertexOutput {
        @builtin(position) position: vec4f,
        @location(0) uv: vec2f,
    };

    @vertex
    fn vertexMain(@builtin(vertex_index) vertexIndex: u32) -> VertexOutput {
        let positions = array(
            vec2f(-1.0, -1.0),
            vec2f(3.0, -1.0),
            vec2f(-1.0, 3.0),
        );
        let coordinates = array(
            vec2f(0.0, 1.0),
            vec2f(2.0, 1.0),
            vec2f(0.0, -1.0),
        );
        var output: VertexOutput;
        output.position = vec4f(positions[vertexIndex], 0.0, 1.0);
        output.uv = coordinates[vertexIndex];
        return output;
    }

    fn paletteColor(pixel: vec2i) -> vec4f {
        let frameSize = vec2i(textureDimensions(frameTexture));
        let safePixel = clamp(pixel, vec2i(0), frameSize - vec2i(1));
        let paletteIndex = textureLoad(frameTexture, safePixel, 0).r;
        return textureLoad(paletteTexture, vec2i(i32(paletteIndex), 0), 0);
    }

    @fragment
    fn fragmentMain(input: VertexOutput) -> @location(0) vec4f {
        let frameSize = vec2f(textureDimensions(frameTexture));
        if (options.interpolationEnabled == 0u) {
            return paletteColor(vec2i(floor(input.uv * frameSize)));
        }

        let samplePosition = input.uv * frameSize - vec2f(0.5);
        let base = vec2i(floor(samplePosition));
        let fraction = fract(samplePosition);
        let top = mix(paletteColor(base), paletteColor(base + vec2i(1, 0)), fraction.x);
        let bottom = mix(
            paletteColor(base + vec2i(0, 1)),
            paletteColor(base + vec2i(1, 1)),
            fraction.x,
        );
        return mix(top, bottom, fraction.y);
    }
    """
