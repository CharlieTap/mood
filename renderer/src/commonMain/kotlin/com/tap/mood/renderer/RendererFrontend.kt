@file:Suppress("FunctionName")

package com.tap.mood.renderer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.tap.mood.doom.runtime.instance.InstanceController
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.jvm.JvmInline

@JvmInline
value class RendererId(
    val value: String,
)

object RendererIds {
    const val WEB_GPU = "webgpu"
    const val CLASSIC = "classic"
}

sealed interface RendererPreference {
    data object Auto : RendererPreference

    data class Specific(
        val id: RendererId,
    ) : RendererPreference
}

interface RendererFrontend {
    val displayName: String
    val autoPriority: Int

    @Composable
    fun Content(
        controller: InstanceController,
        settings: DisplaySettings,
        onUnavailable: (Throwable?) -> Unit,
        modifier: Modifier,
    )
}

data class RegisteredRenderer(
    val id: RendererId,
    val frontend: RendererFrontend,
)

@Inject
@SingleIn(AppScope::class)
class RendererRegistry(
    contributions: Map<String, RendererFrontend>,
) {
    val renderers: List<RegisteredRenderer> =
        contributions
            .map { (id, frontend) -> RegisteredRenderer(RendererId(id), frontend) }
            .sortedWith(
                compareByDescending<RegisteredRenderer> { it.frontend.autoPriority }
                    .thenBy { it.id.value },
            )

    init {
        require(renderers.isNotEmpty()) { "At least one renderer frontend must be registered" }
        require(contributions.keys.none { it.isBlank() }) { "Renderer frontend ids must not be blank" }
    }

    fun candidates(preference: RendererPreference): List<RegisteredRenderer> {
        if (preference !is RendererPreference.Specific) return renderers
        val preferred = renderers.firstOrNull { it.id == preference.id } ?: return renderers
        return listOf(preferred) + renderers.filterNot { it.id == preference.id }
    }
}

@Composable
fun RendererSurface(
    registry: RendererRegistry,
    preference: RendererPreference,
    controller: InstanceController,
    settings: DisplaySettings,
    modifier: Modifier = Modifier,
) {
    var failedRenderers by remember(registry, preference) { mutableStateOf(emptySet<RendererId>()) }
    val renderer =
        registry
            .candidates(preference)
            .firstOrNull { candidate -> candidate.id !in failedRenderers }
            ?: return

    key(renderer.id.value) {
        renderer.frontend.Content(
            controller = controller,
            settings = settings,
            onUnavailable = { failedRenderers = failedRenderers + renderer.id },
            modifier = modifier,
        )
    }
}
