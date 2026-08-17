package com.tap.mood.doom.ui.settings

import com.tap.mood.doom.runtime.settings.Border
import com.tap.mood.doom.runtime.settings.Detail
import com.tap.mood.doom.runtime.settings.EngineSettings
import com.tap.mood.renderer.AspectRatio
import com.tap.mood.renderer.DisplaySettings
import com.tap.mood.renderer.RendererId
import com.tap.mood.renderer.RendererIds
import com.tap.mood.renderer.RendererPreference
import com.tap.mood.renderer.Scaling
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Inject
@SingleIn(AppScope::class)
class SettingsStore(
    private val storage: SettingsStorage,
) {
    private val _settings = MutableStateFlow(readSettings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    fun update(transform: (Settings) -> Settings) {
        val updated = transform(_settings.value).normalized()
        if (updated == _settings.value) return
        _settings.value = updated
        storage.write(updated.toStorageValues())
    }

    private fun readSettings(): Settings =
        Settings(
            display =
                DisplaySettings(
                    aspectRatio = storage.enumValue(KEY_ASPECT_RATIO, AspectRatio.Corrected),
                    scaling = storage.enumValue(KEY_SCALING, Scaling.Classic),
                    renderer = storage.rendererPreference(),
                ),
            engine =
                EngineSettings(
                    detail = storage.enumValue(KEY_GRAPHIC_DETAIL, Detail.High),
                    border = storage.enumValue(KEY_VIEW_SIZE, Border.Large),
                    gammaLevel = storage.read(KEY_GAMMA_LEVEL)?.toIntOrNull() ?: 0,
                ),
        ).normalized()

    private fun Settings.normalized(): Settings = copy(engine = engine.normalized())

    private fun Settings.toStorageValues(): Map<String, String> =
        mapOf(
            KEY_ASPECT_RATIO to display.aspectRatio.name,
            KEY_SCALING to display.scaling.name,
            KEY_RENDERER to display.renderer.storageValue(),
            KEY_GRAPHIC_DETAIL to engine.detail.name,
            KEY_VIEW_SIZE to engine.border.name,
            KEY_GAMMA_LEVEL to engine.gammaLevel.toString(),
        )

    private inline fun <reified T : Enum<T>> SettingsStorage.enumValue(
        key: String,
        default: T,
    ): T = read(key)?.let { value -> enumValues<T>().firstOrNull { it.name == value } } ?: default

    private fun SettingsStorage.rendererPreference(): RendererPreference =
        read(KEY_RENDERER)
            ?.takeUnless { it == RENDERER_AUTO || it.isBlank() }
            ?.let { if (it == LEGACY_SOFTWARE_RENDERER) RendererIds.CLASSIC else it }
            ?.let { RendererPreference.Specific(RendererId(it)) }
            ?: RendererPreference.Auto

    private fun RendererPreference.storageValue(): String =
        when (this) {
            RendererPreference.Auto -> RENDERER_AUTO
            is RendererPreference.Specific -> id.value
        }

    private companion object {
        const val KEY_ASPECT_RATIO = "aspect_ratio"
        const val KEY_SCALING = "scaling"
        const val KEY_RENDERER = "renderer"
        const val KEY_GRAPHIC_DETAIL = "graphic_detail"
        const val KEY_VIEW_SIZE = "view_size"
        const val KEY_GAMMA_LEVEL = "gamma_level"
        const val RENDERER_AUTO = "auto"
        const val LEGACY_SOFTWARE_RENDERER = "software"
    }
}
