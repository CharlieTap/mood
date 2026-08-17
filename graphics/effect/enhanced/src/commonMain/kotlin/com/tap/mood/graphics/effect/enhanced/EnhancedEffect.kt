package com.tap.mood.graphics.effect.enhanced

import com.tap.mood.graphics.DisplayEffectId
import com.tap.mood.graphics.DisplayEffectIds
import com.tap.mood.graphics.DisplayEffectOption
import com.tap.mood.graphics.backend.webgpu.WebGpuDisplayEffect
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.StringKey
import dev.zacsweers.metro.binding

@StringKey(DisplayEffectIds.ENHANCED)
@ContributesIntoMap(AppScope::class, binding<WebGpuDisplayEffect>())
@Inject
class EnhancedEffect : WebGpuDisplayEffect {
    override val option = DisplayEffectOption(DisplayEffectId(DisplayEffectIds.ENHANCED), "Enhanced")
    override val order = 200
    override val applyExpression = "enhancedColor(input.uv)"
    override val shaderSource =
        """
        fn enhancedColor(uv: vec2f) -> vec4f {
            let texel = vec2f(1.0) / vec2f(f32(options.outputWidth), f32(options.outputHeight));
            var color = baseColor(uv).rgb;
            let glow =
                baseColor(uv + vec2f(texel.x * 2.0, 0.0)).rgb +
                baseColor(uv - vec2f(texel.x * 2.0, 0.0)).rgb +
                baseColor(uv + vec2f(0.0, texel.y * 2.0)).rgb +
                baseColor(uv - vec2f(0.0, texel.y * 2.0)).rgb;
            color += max(glow * 0.25 - color, vec3f(0.0)) * 0.08;
            let luminance = dot(color, vec3f(0.2126, 0.7152, 0.0722));
            color = mix(vec3f(luminance), color, 1.08);
            color = (color - vec3f(0.5)) * 1.04 + vec3f(0.5);
            return vec4f(clamp(color, vec3f(0.0), vec3f(1.0)), 1.0);
        }
        """
}
