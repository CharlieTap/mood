@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class, kotlin.wasm.unsafe.UnsafeWasmMemoryApi::class)

package com.tap.mood

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
import kotlinx.browser.localStorage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.wasm.unsafe.Pointer
import kotlin.wasm.unsafe.withScopedMemoryAllocator

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
internal class WebAudioSinkFactory(
    private val logger: Logger,
) : AudioSinkFactory {
    override fun create(): AudioSink = WebAudioSink(logger)
}

private class WebAudioSink(
    private val logger: Logger,
) : AudioSink {
    private val player = createWebAudioPlayer()
    private var active = false
    private var closed = false

    override fun setActive(active: Boolean) {
        if (closed) return
        this.active = active
        setWebAudioActive(player, active)
    }

    override fun write(frame: AudioFrame) {
        if (!active || closed) return
        runCatching {
            frame.pcm.withLinearMemory { pointer ->
                writeWebAudio(
                    player = player,
                    pcmPointer = pointer.address.toInt(),
                    frameCount = frame.frameCount,
                    sampleRate = frame.sampleRate,
                )
            }
        }.onFailure { throwable -> logger.error("Doom audio playback failed", throwable) }
    }

    override fun close() {
        if (closed) return
        closed = true
        closeWebAudio(player)
    }
}

@JsFun(
    """() => {
        const player = { context: null, nextTime: 0, active: false, reportedRunning: false };
        player.resume = () => {
            if (player.active && player.context?.state === 'suspended') {
                player.context.resume().then(() => {
                    if (!player.reportedRunning) {
                        console.info('Doom audio running at ' + player.context.sampleRate + ' Hz stereo');
                        player.reportedRunning = true;
                    }
                });
            }
        };
        window.addEventListener('pointerdown', player.resume, true);
        window.addEventListener('keydown', player.resume, true);
        return player;
    }""",
)
private external fun createWebAudioPlayer(): JsAny

@JsFun(
    """(player, active) => {
        player.active = active;
        if (active) {
            player.resume();
        } else if (player.context) {
            player.nextTime = 0;
            player.context.suspend();
        }
    }""",
)
private external fun setWebAudioActive(
    player: JsAny,
    active: Boolean,
)

@JsFun(
    """(player, pcmPointer, frameCount, sampleRate) => {
        const AudioContext = window.AudioContext || window.webkitAudioContext;
        if (!player.context) player.context = new AudioContext({ latencyHint: 'interactive' });
        const context = player.context;
        if (context.state === 'running' && !player.reportedRunning) {
            console.info('Doom audio running at ' + context.sampleRate + ' Hz stereo');
            player.reportedRunning = true;
        }
        if (!player.active || context.state !== 'running') return;

        const pcm = new Int16Array(wasmExports.memory.buffer, pcmPointer, frameCount * 2);
        const buffer = context.createBuffer(2, frameCount, sampleRate);
        const left = buffer.getChannelData(0);
        const right = buffer.getChannelData(1);
        for (let frame = 0, sample = 0; frame < frameCount; frame++) {
            left[frame] = pcm[sample++] / 32768;
            right[frame] = pcm[sample++] / 32768;
        }

        const source = context.createBufferSource();
        source.buffer = buffer;
        source.connect(context.destination);
        const startTime = Math.max(player.nextTime, context.currentTime + 0.04);
        source.start(startTime);
        player.nextTime = startTime + buffer.duration;
    }""",
)
private external fun writeWebAudio(
    player: JsAny,
    pcmPointer: Int,
    frameCount: Int,
    sampleRate: Int,
)

@JsFun(
    """(player) => {
        window.removeEventListener('pointerdown', player.resume, true);
        window.removeEventListener('keydown', player.resume, true);
        if (player.context) player.context.close();
        player.active = false;
        player.nextTime = 0;
    }""",
)
private external fun closeWebAudio(player: JsAny)

private inline fun <T> ByteArray.withLinearMemory(block: (Pointer) -> T): T =
    withScopedMemoryAllocator { allocator ->
        val pointer = allocator.allocate(size)
        var index = 0
        while (index < size) {
            (pointer + index).storeByte(this[index])
            index++
        }
        block(pointer)
    }

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
internal class WebExecutionContextFactory : ExecutionContextFactory {
    override fun create(): ExecutionContext =
        object : ExecutionContext {
            override val dispatcher: CoroutineDispatcher = Dispatchers.Default

            override fun close() = Unit
        }
}

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
internal class WebSettingsStorage(
    private val logger: Logger,
) : SettingsStorage {
    override fun read(key: String): String? = runCatching { localStorage.getItem("$SETTINGS_PREFIX$key") }.getOrNull()

    override fun write(values: Map<String, String>) {
        runCatching {
            values.forEach { (key, value) -> localStorage.setItem("$SETTINGS_PREFIX$key", value) }
        }.onFailure { throwable -> logger.warning("Could not persist settings", throwable) }
    }

    private companion object {
        const val SETTINGS_PREFIX = "mood.doom.settings."
    }
}

@OptIn(ExperimentalEncodingApi::class)
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
internal class WebSaveStore(
    private val logger: Logger,
) : SaveStore {
    override fun load(slot: Int): ByteArray? {
        SaveSlots.requireValid(slot)
        return runCatching {
            localStorage.getItem(key(slot))?.let(Base64::decode)
        }.onFailure { throwable -> logger.error("Could not load save slot $slot", throwable) }
            .getOrNull()
    }

    override fun size(slot: Int): Int = load(slot)?.size ?: 0

    override fun save(
        slot: Int,
        data: ByteArray,
    ): Boolean {
        SaveSlots.requireValid(slot)
        return runCatching {
            localStorage.setItem(key(slot), Base64.encode(data))
            true
        }.onFailure { throwable -> logger.error("Could not save slot $slot", throwable) }
            .getOrDefault(false)
    }

    private fun key(slot: Int): String = "$SAVE_PREFIX$slot"

    private companion object {
        const val SAVE_PREFIX = "mood.doom.save."
    }
}

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
internal class WebLogger : Logger {
    override fun debug(message: String) = println("Doom: $message")

    override fun info(message: String) = println("Doom: $message")

    override fun warning(
        message: String,
        throwable: Throwable?,
    ) {
        println("Doom warning: $message${throwable?.let { ": ${it.message}" }.orEmpty()}")
    }

    override fun error(
        message: String,
        throwable: Throwable?,
    ) {
        println("Doom error: $message${throwable?.let { ": ${it.message}" }.orEmpty()}")
    }
}
