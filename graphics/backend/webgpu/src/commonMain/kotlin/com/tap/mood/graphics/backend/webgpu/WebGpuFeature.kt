package com.tap.mood.graphics.backend.webgpu

import com.tap.mood.graphics.DisplayEffectId
import com.tap.mood.graphics.DisplayEffectIds
import com.tap.mood.graphics.DisplayEffectOption
import com.tap.mood.graphics.GraphicsCapabilities
import com.tap.mood.graphics.UpscalerId
import com.tap.mood.graphics.UpscalerIds
import com.tap.mood.graphics.UpscalerOption
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.StringKey
import dev.zacsweers.metro.binding

interface WebGpuUpscaler {
    val option: UpscalerOption
    val order: Int
    val shaderSource: String
    val sampleExpression: String
    val intermediateShader: String?
        get() = null
}

interface WebGpuDisplayEffect {
    val option: DisplayEffectOption
    val order: Int
    val shaderSource: String
    val applyExpression: String
}

data class RegisteredWebGpuUpscaler(
    val shaderMode: Int,
    val implementation: WebGpuUpscaler,
) {
    val requiresIntermediateTexture: Boolean
        get() = implementation.intermediateShader != null
}

data class RegisteredWebGpuDisplayEffect(
    val shaderMode: Int,
    val implementation: WebGpuDisplayEffect,
)

@Inject
@SingleIn(AppScope::class)
class WebGpuFeatureRegistry(
    upscalerContributions: Map<String, WebGpuUpscaler>,
    effectContributions: Map<String, WebGpuDisplayEffect>,
) {
    val upscalers =
        upscalerContributions
            .entries
            .sortedWith(compareBy<Map.Entry<String, WebGpuUpscaler>> { it.value.order }.thenBy { it.key })
            .mapIndexed { shaderMode, (id, implementation) ->
                require(id == implementation.option.id.value) { "WebGPU upscaler map key must match its id" }
                RegisteredWebGpuUpscaler(shaderMode, implementation)
            }

    val effects =
        effectContributions
            .entries
            .sortedWith(compareBy<Map.Entry<String, WebGpuDisplayEffect>> { it.value.order }.thenBy { it.key })
            .mapIndexed { shaderMode, (id, implementation) ->
                require(id == implementation.option.id.value) { "WebGPU effect map key must match its id" }
                RegisteredWebGpuDisplayEffect(shaderMode, implementation)
            }

    val intermediateUpscaler =
        upscalers
            .filter { it.requiresIntermediateTexture }
            .also { require(it.size <= 1) { "WebGPU supports at most one intermediate upscaler" } }
            .singleOrNull()

    val capabilities =
        GraphicsCapabilities(
            upscalers = upscalers.map { it.implementation.option },
            effects = effects.map { it.implementation.option },
        )

    init {
        require(
            upscalers
                .firstOrNull()
                ?.implementation
                ?.option
                ?.id == UpscalerId(UpscalerIds.NEAREST),
        ) {
            "Nearest must be the first WebGPU upscaler"
        }
        require(
            effects
                .firstOrNull()
                ?.implementation
                ?.option
                ?.id == DisplayEffectId(DisplayEffectIds.ORIGINAL),
        ) {
            "Original must be the first WebGPU effect"
        }
    }

    fun upscaler(id: UpscalerId): RegisteredWebGpuUpscaler =
        upscalers.firstOrNull { it.implementation.option.id == id } ?: upscalers.first()

    fun effect(id: DisplayEffectId): RegisteredWebGpuDisplayEffect =
        effects.firstOrNull { it.implementation.option.id == id } ?: effects.first()
}

@StringKey(UpscalerIds.NEAREST)
@ContributesIntoMap(AppScope::class, binding<WebGpuUpscaler>())
@Inject
class NearestWebGpuUpscaler : WebGpuUpscaler {
    override val option = UpscalerOption(UpscalerId(UpscalerIds.NEAREST), "Pixel Perfect")
    override val order = 0
    override val shaderSource = ""
    override val sampleExpression =
        "indexedColor(vec2i(floor(uv * vec2f(textureDimensions(frameTexture)))))"
}

@StringKey(UpscalerIds.BILINEAR)
@ContributesIntoMap(AppScope::class, binding<WebGpuUpscaler>())
@Inject
class BilinearWebGpuUpscaler : WebGpuUpscaler {
    override val option = UpscalerOption(UpscalerId(UpscalerIds.BILINEAR), "Smooth")
    override val order = 100
    override val shaderSource = ""
    override val sampleExpression = "smoothIndexedColor(uv)"
}

@StringKey(DisplayEffectIds.ORIGINAL)
@ContributesIntoMap(AppScope::class, binding<WebGpuDisplayEffect>())
@Inject
class OriginalWebGpuDisplayEffect : WebGpuDisplayEffect {
    override val option = DisplayEffectOption(DisplayEffectId(DisplayEffectIds.ORIGINAL), "Original")
    override val order = 0
    override val shaderSource = ""
    override val applyExpression = "baseColor(input.uv)"
}
