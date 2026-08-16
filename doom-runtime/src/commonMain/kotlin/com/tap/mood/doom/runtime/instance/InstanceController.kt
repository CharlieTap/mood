package com.tap.mood.doom.runtime.instance

import com.tap.mood.doom.runtime.engine.Frame
import com.tap.mood.doom.runtime.input.Key
import com.tap.mood.doom.runtime.settings.EngineSettings
import kotlinx.coroutines.flow.StateFlow

interface InstanceController {
    val state: StateFlow<InstanceState>

    fun setActive(active: Boolean)

    fun setFrameSink(sink: ((Frame) -> Unit)?)

    fun clearFrameSink(sink: (Frame) -> Unit)

    fun setKeyPressed(
        key: Key,
        pressed: Boolean,
    )

    fun tapKey(key: Key)

    fun releaseAllKeys()

    fun configure(settings: EngineSettings)
}
