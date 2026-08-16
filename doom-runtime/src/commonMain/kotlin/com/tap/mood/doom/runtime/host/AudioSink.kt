package com.tap.mood.doom.runtime.host

import com.tap.mood.doom.runtime.engine.AudioFrame

interface AudioSink : AutoCloseable {
    fun setActive(active: Boolean) = Unit

    /** Implementations that retain audio beyond this call must copy [AudioFrame.pcm]. */
    fun write(frame: AudioFrame)

    override fun close() = Unit
}

fun interface AudioSinkFactory {
    fun create(): AudioSink
}
