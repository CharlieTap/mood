package com.tap.mood.doom.runtime.host

import com.tap.mood.doom.runtime.engine.AudioFrame
import com.tap.mood.doom.runtime.engine.Frame

/** All callbacks are invoked synchronously on the engine thread. */
interface Host {
    /** Must be monotonic for the lifetime of the engine. */
    fun timeInMilliseconds(): Long

    fun onGameInitialized(
        width: Int,
        height: Int,
    ) = Unit

    /** See [Frame] for the lifetime of its borrowed pixel buffer. */
    fun onFrame(frame: Frame) = Unit

    /** See [AudioFrame] for the lifetime of its borrowed PCM buffer. */
    fun onAudio(frame: AudioFrame) = Unit

    fun onInfoMessage(message: String) = Unit

    fun onErrorMessage(message: String) = Unit

    /** Return an empty list to use the shareware WAD embedded in the module. */
    fun wads(): List<ByteArray> = emptyList()

    fun loadSaveGame(slot: Int): ByteArray? = null

    fun saveGameSize(slot: Int): Int = loadSaveGame(slot)?.size ?: 0

    fun saveGame(
        slot: Int,
        data: ByteArray,
    ): Boolean = false
}
