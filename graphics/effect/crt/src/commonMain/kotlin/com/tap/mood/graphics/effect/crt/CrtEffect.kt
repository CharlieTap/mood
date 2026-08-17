package com.tap.mood.graphics.effect.crt

import com.tap.mood.graphics.DisplayEffectId
import com.tap.mood.graphics.DisplayEffectIds
import com.tap.mood.graphics.DisplayEffectOption
import com.tap.mood.graphics.backend.webgpu.WebGpuDisplayEffect
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.StringKey
import dev.zacsweers.metro.binding

@StringKey(DisplayEffectIds.CRT)
@ContributesIntoMap(AppScope::class, binding<WebGpuDisplayEffect>())
@Inject
class CrtEffect : WebGpuDisplayEffect {
    override val option = DisplayEffectOption(DisplayEffectId(DisplayEffectIds.CRT), "CRT")
    override val order = 100
    override val applyExpression = "crtColor(input)"
    override val shaderSource =
        """
        fn crtColor(input: VertexOutput) -> vec4f {
            let centered = input.uv - vec2f(0.5);
            let warped = centered * (1.0 + dot(centered, centered) * 0.16) + vec2f(0.5);
            if (any(warped < vec2f(0.0)) || any(warped > vec2f(1.0))) {
                return vec4f(0.0, 0.0, 0.0, 1.0);
            }
            let color = baseColor(warped);
            let scanline = 0.78 + 0.22 * cos(warped.y * f32(options.sourceHeight) * 6.28318530718);
            let maskIndex = i32(floor(input.position.x)) % 3;
            var mask = vec3f(0.84, 0.84, 1.0);
            if (maskIndex == 0) { mask = vec3f(1.0, 0.84, 0.84); }
            else if (maskIndex == 1) { mask = vec3f(0.84, 1.0, 0.84); }
            let edge = warped * (vec2f(1.0) - warped);
            let vignette = clamp(pow(16.0 * edge.x * edge.y, 0.18), 0.0, 1.0);
            return vec4f(color.rgb * mask * scanline * vignette, 1.0);
        }
        """
}
