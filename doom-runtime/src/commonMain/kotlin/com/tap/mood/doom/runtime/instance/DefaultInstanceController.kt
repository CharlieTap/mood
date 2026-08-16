package com.tap.mood.doom.runtime.instance

import com.tap.mood.doom.runtime.engine.Frame
import com.tap.mood.doom.runtime.input.Key
import com.tap.mood.doom.runtime.settings.EngineSettings
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.StateFlow

@Inject
class DefaultInstanceController(
    instanceFactory: InstanceFactory,
) : InstanceController,
    AutoCloseable {
    private val instance = instanceFactory.create()

    override val state: StateFlow<InstanceState> = instance.state

    override fun setActive(active: Boolean) = instance.setActive(active)

    override fun setFrameSink(sink: ((Frame) -> Unit)?) = instance.setFrameSink(sink)

    override fun clearFrameSink(sink: (Frame) -> Unit) = instance.clearFrameSink(sink)

    override fun setKeyPressed(
        key: Key,
        pressed: Boolean,
    ) = instance.setKeyPressed(key, pressed)

    override fun tapKey(key: Key) = instance.tapKey(key)

    override fun releaseAllKeys() = instance.releaseAllKeys()

    override fun configure(settings: EngineSettings) = instance.configure(settings)

    override fun close() = instance.close()
}
