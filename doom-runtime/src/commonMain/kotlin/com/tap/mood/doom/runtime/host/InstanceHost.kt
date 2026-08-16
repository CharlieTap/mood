package com.tap.mood.doom.runtime.host

import com.tap.mood.doom.runtime.engine.AudioFrame
import com.tap.mood.doom.runtime.engine.Frame
import kotlin.time.TimeSource

internal class InstanceHost(
    private val saveStore: SaveStore,
    private val logger: Logger,
    private val audioSink: AudioSink,
    private val frameConsumer: (Frame) -> Unit,
) : Host,
    AutoCloseable {
    private val timeOrigin = TimeSource.Monotonic.markNow()

    override fun timeInMilliseconds(): Long = timeOrigin.elapsedNow().inWholeMilliseconds

    override fun onGameInitialized(
        width: Int,
        height: Int,
    ) {
        logger.info("Doom framebuffer initialized at ${width}x$height")
    }

    override fun onFrame(frame: Frame) {
        frameConsumer(frame)
    }

    override fun onAudio(frame: AudioFrame) {
        audioSink.write(frame)
    }

    fun setActive(active: Boolean) {
        audioSink.setActive(active)
    }

    override fun onInfoMessage(message: String) {
        logger.info(message.trimEnd())
    }

    override fun onErrorMessage(message: String) {
        logger.error(message.trimEnd())
    }

    override fun loadSaveGame(slot: Int): ByteArray? = saveStore.load(slot)

    override fun saveGameSize(slot: Int): Int = saveStore.size(slot)

    override fun saveGame(
        slot: Int,
        data: ByteArray,
    ): Boolean = saveStore.save(slot, data)

    override fun close() {
        audioSink.close()
    }
}
