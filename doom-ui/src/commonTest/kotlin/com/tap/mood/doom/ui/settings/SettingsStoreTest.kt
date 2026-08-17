package com.tap.mood.doom.ui.settings

import com.tap.mood.doom.runtime.settings.Detail
import com.tap.mood.renderer.AspectRatio
import com.tap.mood.renderer.RendererId
import com.tap.mood.renderer.RendererIds
import com.tap.mood.renderer.RendererPreference
import com.tap.mood.renderer.Scaling
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsStoreTest {
    @Test
    fun invalidStoredValuesUseDefaults() {
        val store =
            SettingsStore(
                FakeSettingsStorage(
                    mutableMapOf(
                        "aspect_ratio" to "invalid",
                        "scaling" to Scaling.Smooth.name,
                        "renderer" to "software",
                        "gamma_level" to "99",
                    ),
                ),
            )

        val settings = store.settings.value
        assertEquals(AspectRatio.Corrected, settings.display.aspectRatio)
        assertEquals(Scaling.Smooth, settings.display.scaling)
        assertEquals(
            RendererPreference.Specific(RendererId(RendererIds.CLASSIC)),
            settings.display.renderer,
        )
        assertEquals(4, settings.engine.gammaLevel)
    }

    @Test
    fun updatePersistsNormalizedSettings() {
        val storage = FakeSettingsStorage()
        val store = SettingsStore(storage)

        store.update { settings ->
            settings.copy(
                display = settings.display.copy(aspectRatio = AspectRatio.Fill),
                engine = settings.engine.copy(detail = Detail.Low, gammaLevel = -2),
            )
        }

        assertEquals(AspectRatio.Fill, store.settings.value.display.aspectRatio)
        assertEquals(Detail.Low, store.settings.value.engine.detail)
        assertEquals(0, store.settings.value.engine.gammaLevel)
        assertEquals("Fill", storage.values["aspect_ratio"])
        assertEquals("auto", storage.values["renderer"])
        assertEquals("Low", storage.values["graphic_detail"])
        assertEquals("0", storage.values["gamma_level"])
    }
}

private class FakeSettingsStorage(
    val values: MutableMap<String, String> = mutableMapOf(),
) : SettingsStorage {
    override fun read(key: String): String? = values[key]

    override fun write(values: Map<String, String>) {
        this.values.putAll(values)
    }
}
