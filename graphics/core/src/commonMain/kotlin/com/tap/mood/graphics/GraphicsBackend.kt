@file:Suppress("FunctionName")

package com.tap.mood.graphics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
value class GraphicsBackendId(
    val value: String,
)

object GraphicsBackendIds {
    const val WEB_GPU = "webgpu"
    const val CLASSIC = "classic"
}

data class UpscalerOption(
    val id: UpscalerId,
    val displayName: String,
)

data class DisplayEffectOption(
    val id: DisplayEffectId,
    val displayName: String,
)

data class GraphicsCapabilities(
    val upscalers: List<UpscalerOption>,
    val effects: List<DisplayEffectOption>,
) {
    init {
        require(upscalers.isNotEmpty()) { "A graphics backend must provide an upscaler" }
        require(effects.isNotEmpty()) { "A graphics backend must provide a display effect" }
        require(upscalers.distinctBy { it.id }.size == upscalers.size) { "Upscaler ids must be unique" }
        require(effects.distinctBy { it.id }.size == effects.size) { "Display effect ids must be unique" }
    }

    fun resolve(settings: DisplaySettings): DisplaySettings =
        settings.copy(
            upscaler = upscalers.firstOrNull { it.id == settings.upscaler }?.id ?: upscalers.first().id,
            effect = effects.firstOrNull { it.id == settings.effect }?.id ?: effects.first().id,
        )
}

interface GraphicsBackend {
    val displayName: String
    val fallbackPriority: Int
    val capabilities: GraphicsCapabilities

    @Composable
    fun Content(
        controller: InstanceController,
        settings: DisplaySettings,
        onUnavailable: (Throwable?) -> Unit,
        modifier: Modifier,
    )
}

data class RegisteredGraphicsBackend(
    val id: GraphicsBackendId,
    val backend: GraphicsBackend,
)

@Inject
@SingleIn(AppScope::class)
class GraphicsBackendRegistry(
    contributions: Map<String, GraphicsBackend>,
) {
    val backends: List<RegisteredGraphicsBackend> =
        contributions
            .map { (id, backend) -> RegisteredGraphicsBackend(GraphicsBackendId(id), backend) }
            .sortedWith(
                compareByDescending<RegisteredGraphicsBackend> { it.backend.fallbackPriority }
                    .thenBy { it.id.value },
            )

    init {
        require(backends.isNotEmpty()) { "At least one graphics backend must be registered" }
        require(contributions.keys.none { it.isBlank() }) { "Graphics backend ids must not be blank" }
    }

    fun candidates(preferredId: GraphicsBackendId): List<RegisteredGraphicsBackend> {
        val preferred = backends.firstOrNull { it.id == preferredId } ?: return backends
        return listOf(preferred) + backends.filterNot { it.id == preferredId }
    }
}

@Composable
fun GraphicsSurface(
    registry: GraphicsBackendRegistry,
    preferredBackend: GraphicsBackendId,
    controller: InstanceController,
    settings: DisplaySettings,
    onBackendSelected: (GraphicsBackendId) -> Unit,
    modifier: Modifier = Modifier,
) {
    var failedBackends by remember(registry, preferredBackend) { mutableStateOf(emptySet<GraphicsBackendId>()) }
    val selectedBackend =
        registry
            .candidates(preferredBackend)
            .firstOrNull { candidate -> candidate.id !in failedBackends }
            ?: return

    LaunchedEffect(selectedBackend.id) {
        onBackendSelected(selectedBackend.id)
    }

    key(selectedBackend.id.value) {
        selectedBackend.backend.Content(
            controller = controller,
            settings = selectedBackend.backend.capabilities.resolve(settings),
            onUnavailable = { failedBackends = failedBackends + selectedBackend.id },
            modifier = modifier,
        )
    }
}
