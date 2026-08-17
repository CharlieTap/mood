package com.tap.mood.graphics.upscaler.fsr1

import com.tap.mood.graphics.UpscalerId
import com.tap.mood.graphics.UpscalerIds
import com.tap.mood.graphics.UpscalerOption
import com.tap.mood.graphics.backend.webgpu.WebGpuUpscaler
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.StringKey
import dev.zacsweers.metro.binding

@StringKey(UpscalerIds.FSR1)
@ContributesIntoMap(AppScope::class, binding<WebGpuUpscaler>())
@Inject
class Fsr1Upscaler : WebGpuUpscaler {
    override val option = UpscalerOption(UpscalerId(UpscalerIds.FSR1), "FSR 1")
    override val order = 200
    override val shaderSource = FSR1_RCAS_WEBGPU_SHADER
    override val sampleExpression = "fsr1RcasColor(uv)"
    override val intermediateShader = FSR1_EASU_WEBGPU_SHADER
}

// Port of AMD FidelityFX Super Resolution 1 EASU. See THIRD_PARTY_NOTICES.md.
// https://github.com/GPUOpen-Effects/FidelityFX-FSR/blob/master/ffx-fsr/ffx_fsr1.h
private const val FSR1_EASU_WEBGPU_SHADER =
    """
    struct Options {
        presentation: u32, upscaler: u32, sourceWidth: u32, sourceHeight: u32,
        outputWidth: u32, outputHeight: u32, padding0: u32, padding1: u32,
    };
    @group(0) @binding(0) var frameTexture: texture_2d<u32>;
    @group(0) @binding(1) var paletteTexture: texture_2d<f32>;
    @group(0) @binding(2) var<uniform> options: Options;

    struct VertexOutput { @builtin(position) position: vec4f };
    @vertex
    fn vertexMain(@builtin(vertex_index) index: u32) -> VertexOutput {
        let positions = array(vec2f(-1.0, -1.0), vec2f(3.0, -1.0), vec2f(-1.0, 3.0));
        var output: VertexOutput;
        output.position = vec4f(positions[index], 0.0, 1.0);
        return output;
    }

    fn color(pixel: vec2i) -> vec3f {
        let size = vec2i(textureDimensions(frameTexture));
        let index = textureLoad(frameTexture, clamp(pixel, vec2i(0), size - vec2i(1)), 0).r;
        return textureLoad(paletteTexture, vec2i(i32(index), 0), 0).rgb;
    }
    fn luma(value: vec3f) -> f32 { return value.b * 0.5 + value.r * 0.5 + value.g; }
    fn edge(weight: f32, a: f32, b: f32, c: f32, d: f32, e: f32) -> vec3f {
        let directionX = d - b;
        let directionY = e - a;
        let rangeX = max(max(abs(d - c), abs(c - b)), 0.00001);
        let rangeY = max(max(abs(e - c), abs(c - a)), 0.00001);
        let length = pow(clamp(abs(directionX) / rangeX, 0.0, 1.0), 2.0) +
            pow(clamp(abs(directionY) / rangeY, 0.0, 1.0), 2.0);
        return vec3f(directionX * weight, directionY * weight, length * weight);
    }
    fn tap(offset: vec2f, direction: vec2f, length: vec2f, lobe: f32, clip: f32, value: vec3f) -> vec4f {
        var rotated = vec2f(dot(offset, direction), dot(offset, vec2f(-direction.y, direction.x)));
        rotated *= length;
        let distanceSquared = min(dot(rotated, rotated), clip);
        var base = 0.4 * distanceSquared - 1.0;
        var window = lobe * distanceSquared - 1.0;
        base *= base;
        window *= window;
        base = 1.5625 * base - 0.5625;
        let weight = base * window;
        return vec4f(value * weight, weight);
    }

    @fragment
    fn fragmentMain(input: VertexOutput) -> @location(0) vec4f {
        let sourceSize = vec2f(f32(options.sourceWidth), f32(options.sourceHeight));
        let outputSize = vec2f(f32(options.outputWidth), f32(options.outputHeight));
        let sourcePosition = floor(input.position.xy) * sourceSize / outputSize +
            0.5 * sourceSize / outputSize - 0.5;
        let base = vec2i(floor(sourcePosition));
        let pp = fract(sourcePosition);
        let b = color(base + vec2i(0, -1)); let c = color(base + vec2i(1, -1));
        let e = color(base + vec2i(-1, 0)); let f = color(base); let g = color(base + vec2i(1, 0));
        let h = color(base + vec2i(2, 0)); let i = color(base + vec2i(-1, 1));
        let j = color(base + vec2i(0, 1)); let k = color(base + vec2i(1, 1));
        let l = color(base + vec2i(2, 1)); let n = color(base + vec2i(0, 2));
        let o = color(base + vec2i(1, 2));
        var gradient = vec3f(0.0);
        gradient += edge((1.0 - pp.x) * (1.0 - pp.y), luma(b), luma(e), luma(f), luma(g), luma(j));
        gradient += edge(pp.x * (1.0 - pp.y), luma(c), luma(f), luma(g), luma(h), luma(k));
        gradient += edge((1.0 - pp.x) * pp.y, luma(f), luma(i), luma(j), luma(k), luma(n));
        gradient += edge(pp.x * pp.y, luma(g), luma(j), luma(k), luma(l), luma(o));
        let magnitude = dot(gradient.xy, gradient.xy);
        var direction = vec2f(1.0, 0.0);
        if (magnitude >= 1.0 / 32768.0) { direction = gradient.xy * inverseSqrt(magnitude); }
        var edgeLength = 0.5 * gradient.z;
        edgeLength *= edgeLength;
        let stretch = 1.0 / max(abs(direction.x), abs(direction.y));
        let anisotropy = vec2f(1.0 + (stretch - 1.0) * edgeLength, 1.0 - 0.5 * edgeLength);
        let lobe = 0.5 - 0.29 * edgeLength;
        let clip = 1.0 / lobe;
        let minimum = min(min(f, g), min(j, k));
        let maximum = max(max(f, g), max(j, k));
        var accumulation = vec4f(0.0);
        accumulation += tap(vec2f(0.0, -1.0) - pp, direction, anisotropy, lobe, clip, b);
        accumulation += tap(vec2f(1.0, -1.0) - pp, direction, anisotropy, lobe, clip, c);
        accumulation += tap(vec2f(-1.0, 0.0) - pp, direction, anisotropy, lobe, clip, e);
        accumulation += tap(vec2f(0.0, 0.0) - pp, direction, anisotropy, lobe, clip, f);
        accumulation += tap(vec2f(1.0, 0.0) - pp, direction, anisotropy, lobe, clip, g);
        accumulation += tap(vec2f(2.0, 0.0) - pp, direction, anisotropy, lobe, clip, h);
        accumulation += tap(vec2f(-1.0, 1.0) - pp, direction, anisotropy, lobe, clip, i);
        accumulation += tap(vec2f(0.0, 1.0) - pp, direction, anisotropy, lobe, clip, j);
        accumulation += tap(vec2f(1.0, 1.0) - pp, direction, anisotropy, lobe, clip, k);
        accumulation += tap(vec2f(2.0, 1.0) - pp, direction, anisotropy, lobe, clip, l);
        accumulation += tap(vec2f(0.0, 2.0) - pp, direction, anisotropy, lobe, clip, n);
        accumulation += tap(vec2f(1.0, 2.0) - pp, direction, anisotropy, lobe, clip, o);
        return vec4f(clamp(accumulation.rgb / accumulation.a, minimum, maximum), 1.0);
    }
    """

private const val FSR1_RCAS_WEBGPU_SHADER =
    """
    fn fsr1UpscaledColor(pixel: vec2i) -> vec3f {
        let size = vec2i(textureDimensions(upscaledTexture));
        return textureLoad(upscaledTexture, clamp(pixel, vec2i(0), size - vec2i(1)), 0).rgb;
    }

    fn fsr1RcasColor(uv: vec2f) -> vec4f {
        let size = vec2f(textureDimensions(upscaledTexture));
        let pixel = vec2i(floor(uv * size));
        let north = fsr1UpscaledColor(pixel + vec2i(0, -1));
        let west = fsr1UpscaledColor(pixel + vec2i(-1, 0));
        let center = fsr1UpscaledColor(pixel);
        let east = fsr1UpscaledColor(pixel + vec2i(1, 0));
        let south = fsr1UpscaledColor(pixel + vec2i(0, 1));
        let minimum = min(min(north, west), min(east, south));
        let maximum = max(max(north, west), max(east, south));
        let hitMinimum = min(minimum, center) / max(4.0 * maximum, vec3f(0.00001));
        let hitMaximum =
            (vec3f(1.0) - max(maximum, center)) /
                min(4.0 * minimum - vec3f(4.0), vec3f(-0.00001));
        let lobes = max(-hitMinimum, hitMaximum);
        let lobe = max(-0.1875, min(max(lobes.r, max(lobes.g, lobes.b)), 0.0)) * 0.87;
        let color = (lobe * (north + west + east + south) + center) / (4.0 * lobe + 1.0);
        return vec4f(clamp(color, vec3f(0.0), vec3f(1.0)), 1.0);
    }
    """
