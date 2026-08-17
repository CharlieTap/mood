package com.tap.mood.renderer

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.tap.mood.doom.runtime.instance.InstanceController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RendererRegistryTest {
    @Test
    fun autoOrdersRenderersByPriorityThenId() {
        val registry =
            RendererRegistry(
                mapOf(
                    "z-classic" to FakeFrontend("Classic", 0),
                    "b-gpu" to FakeFrontend("GPU B", 100),
                    "a-gpu" to FakeFrontend("GPU A", 100),
                ),
            )

        assertEquals(
            listOf("a-gpu", "b-gpu", "z-classic"),
            registry.candidates(RendererPreference.Auto).map { it.id.value },
        )
    }

    @Test
    fun specificRendererIsFirstAndKeepsFallbacks() {
        val registry =
            RendererRegistry(
                mapOf(
                    RendererIds.WEB_GPU to FakeFrontend("WebGPU", 100),
                    RendererIds.CLASSIC to FakeFrontend("Classic", 0),
                ),
            )

        assertEquals(
            listOf(RendererIds.CLASSIC, RendererIds.WEB_GPU),
            registry
                .candidates(RendererPreference.Specific(RendererId(RendererIds.CLASSIC)))
                .map { it.id.value },
        )
    }

    @Test
    fun unknownSpecificRendererUsesAutomaticOrder() {
        val registry =
            RendererRegistry(
                mapOf(RendererIds.CLASSIC to FakeFrontend("Classic", 0)),
            )

        assertEquals(
            listOf(RendererIds.CLASSIC),
            registry
                .candidates(RendererPreference.Specific(RendererId("removed-renderer")))
                .map { it.id.value },
        )
    }

    @Test
    fun emptyRegistryIsRejected() {
        assertFailsWith<IllegalArgumentException> { RendererRegistry(emptyMap()) }
    }
}

private class FakeFrontend(
    override val displayName: String,
    override val autoPriority: Int,
) : RendererFrontend {
    @Composable
    override fun Content(
        controller: InstanceController,
        settings: DisplaySettings,
        onUnavailable: (Throwable?) -> Unit,
        modifier: Modifier,
    ) = Unit
}
