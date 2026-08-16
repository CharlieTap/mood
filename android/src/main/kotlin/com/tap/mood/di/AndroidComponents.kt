package com.tap.mood.di

import android.app.Application
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.tap.mood.doom.runtime.engine.AudioFrame
import com.tap.mood.doom.runtime.host.AudioSink
import com.tap.mood.doom.runtime.host.AudioSinkFactory
import com.tap.mood.doom.runtime.host.ExecutionContext
import com.tap.mood.doom.runtime.host.ExecutionContextFactory
import com.tap.mood.doom.runtime.host.Logger
import com.tap.mood.doom.runtime.host.SaveSlots
import com.tap.mood.doom.runtime.host.SaveStore
import com.tap.mood.doom.ui.settings.SettingsStorage
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import kotlin.concurrent.thread

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class AndroidAudioSinkFactory(
    private val logger: Logger,
) : AudioSinkFactory {
    override fun create(): AudioSink = AndroidAudioSink(logger)
}

private class AndroidAudioSink(
    private val logger: Logger,
) : AudioSink {
    private val buffers = ArrayBlockingQueue<AudioBuffer>(BUFFER_COUNT)
    private val trackLock = Any()

    @Volatile
    private var active = false

    @Volatile
    private var closed = false

    @Volatile
    private var audioTrack: AudioTrack? = null

    private val playbackThread =
        thread(name = "Mood-Doom-Audio") {
            try {
                while (!closed) {
                    val buffer = buffers.take()
                    if (!active) continue
                    val track = ensureAudioTrack(buffer.sampleRate)
                    if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
                    track.write(buffer.pcm, 0, buffer.pcm.size, AudioTrack.WRITE_BLOCKING)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (throwable: Throwable) {
                if (!closed) logger.error("Doom audio playback failed", throwable)
            }
        }

    override fun setActive(active: Boolean) {
        this.active = active
        if (!active) {
            buffers.clear()
            synchronized(trackLock) {
                audioTrack?.run {
                    pause()
                    flush()
                }
            }
        }
    }

    override fun write(frame: AudioFrame) {
        if (!active || closed) return
        val buffer = AudioBuffer(frame.sampleRate, frame.pcm.copyOf())
        if (!buffers.offer(buffer)) {
            buffers.poll()
            buffers.offer(buffer)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        active = false
        buffers.clear()
        playbackThread.interrupt()
        synchronized(trackLock) {
            audioTrack?.run {
                runCatching(::stop)
                release()
            }
            audioTrack = null
        }
    }

    private fun ensureAudioTrack(sampleRate: Int): AudioTrack =
        audioTrack?.takeIf { track -> track.sampleRate == sampleRate } ?: synchronized(trackLock) {
            audioTrack?.takeIf { track -> track.sampleRate == sampleRate } ?: createAudioTrack(sampleRate).also {
                audioTrack?.release()
                audioTrack = it
                logger.info("Doom audio initialized at $sampleRate Hz stereo")
            }
        }

    private fun createAudioTrack(sampleRate: Int): AudioTrack {
        val minimumBufferSize =
            AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
        check(minimumBufferSize > 0) { "Could not determine the Doom audio buffer size" }
        return AudioTrack
            .Builder()
            .setAudioAttributes(
                AudioAttributes
                    .Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            ).setAudioFormat(
                AudioFormat
                    .Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build(),
            ).setBufferSizeInBytes(maxOf(minimumBufferSize, bytesPerTic(sampleRate) * BUFFER_COUNT))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private data class AudioBuffer(
        val sampleRate: Int,
        val pcm: ByteArray,
    )

    private companion object {
        const val DOOM_TIC_RATE = 35
        const val CHANNEL_COUNT = 2
        const val BUFFER_COUNT = 4

        fun bytesPerTic(sampleRate: Int) = sampleRate / DOOM_TIC_RATE * CHANNEL_COUNT * Short.SIZE_BYTES
    }
}

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class AndroidExecutionContextFactory : ExecutionContextFactory {
    override fun create(): ExecutionContext {
        val dispatcher =
            Executors
                .newSingleThreadExecutor { runnable -> Thread(runnable, "Mood-Doom") }
                .asCoroutineDispatcher()
        return object : ExecutionContext {
            override val dispatcher: CoroutineDispatcher = dispatcher

            override fun close() = dispatcher.close()
        }
    }
}

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class AndroidSettingsStorage(
    application: Application,
) : SettingsStorage {
    private val sharedPreferences = application.getSharedPreferences(PREFERENCES_NAME, 0)

    override fun read(key: String): String? = sharedPreferences.all[key]?.toString()

    override fun write(values: Map<String, String>) {
        sharedPreferences
            .edit()
            .also { editor -> values.forEach { (key, value) -> editor.putString(key, value) } }
            .apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "doom_settings"
    }
}

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class AndroidSaveStore(
    application: Application,
    private val logger: Logger,
) : SaveStore {
    private val directory = File(application.filesDir, "doom/saves")

    override fun load(slot: Int): ByteArray? = saveFile(slot).takeIf(File::isFile)?.readBytes()

    override fun size(slot: Int): Int {
        val file = saveFile(slot)
        if (!file.isFile) return 0
        val size = file.length()
        check(size <= Int.MAX_VALUE) { "Doom save is too large: $size bytes" }
        return size.toInt()
    }

    override fun save(
        slot: Int,
        data: ByteArray,
    ): Boolean =
        runCatching {
            check(directory.exists() || directory.mkdirs()) { "Could not create Doom save directory" }
            val destination = saveFile(slot)
            val temporary = File.createTempFile("slot-$slot-", ".tmp", directory)
            try {
                temporary.writeBytes(data)
                try {
                    Files.move(
                        temporary.toPath(),
                        destination.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(
                        temporary.toPath(),
                        destination.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                }
            } finally {
                temporary.delete()
            }
        }.onFailure { throwable ->
            logger.error("Failed to save Doom slot $slot", throwable)
        }.isSuccess

    private fun saveFile(slot: Int): File {
        SaveSlots.requireValid(slot)
        return File(directory, "slot-$slot.sav")
    }
}

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class AndroidLogger : Logger {
    override fun debug(message: String) {
        Log.d(TAG, message)
    }

    override fun info(message: String) {
        Log.i(TAG, message)
    }

    override fun warning(
        message: String,
        throwable: Throwable?,
    ) {
        Log.w(TAG, message, throwable)
    }

    override fun error(
        message: String,
        throwable: Throwable?,
    ) {
        Log.e(TAG, message, throwable)
    }

    private companion object {
        const val TAG = "Instance"
    }
}
