package com.tap.mood.doom.runtime.engine

import com.tap.mood.doom.runtime.host.Host

/** Signed 16-bit, little-endian, interleaved stereo PCM borrowed until [Host.onAudio] returns. */
class AudioFrame internal constructor(
    val sampleRate: Int,
    val frameCount: Int,
    val pcm: ByteArray,
) {
    val channelCount: Int = 2
}
