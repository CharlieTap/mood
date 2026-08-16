package com.tap.mood.doom.runtime.instance

import com.tap.mood.doom.runtime.engine.Engine
import com.tap.mood.doom.runtime.engine.EngineFactory
import com.tap.mood.doom.runtime.engine.Frame
import com.tap.mood.doom.runtime.host.AudioSink
import com.tap.mood.doom.runtime.host.InstanceHost
import com.tap.mood.doom.runtime.host.Logger
import com.tap.mood.doom.runtime.host.SaveStore
import com.tap.mood.doom.runtime.input.InputMode
import com.tap.mood.doom.runtime.input.InputSequencer
import com.tap.mood.doom.runtime.input.Key
import com.tap.mood.doom.runtime.settings.Detail
import com.tap.mood.doom.runtime.settings.EngineSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.TimeSource

class Instance(
    private val engineFactory: EngineFactory,
    saveStore: SaveStore,
    audioSink: AudioSink,
    dispatcher: CoroutineDispatcher,
    private val logger: Logger = Logger.None,
    private val onExecutionFinished: () -> Unit = {},
) : AutoCloseable {
    private val engineJob = SupervisorJob()
    private val engineScope = CoroutineScope(engineJob + dispatcher)
    private val active = MutableStateFlow(false)
    private val input = Channel<InputCommand>(Channel.UNLIMITED)
    private val inputSequencer = InputSequencer()
    private val desiredSettings = MutableStateFlow(EngineSettings())
    private val frameSink = MutableStateFlow<((Frame) -> Unit)?>(null)
    private var engine: Engine? = null

    private val host = InstanceHost(saveStore, logger, audioSink) { frame -> frameSink.value?.invoke(frame) }
    private val _state = MutableStateFlow(InstanceState())
    val state: StateFlow<InstanceState> = _state.asStateFlow()

    init {
        engineScope
            .launch {
                try {
                    active.collectLatest { shouldRun ->
                        if (shouldRun) {
                            runGame()
                        } else if (engine != null && _state.value.status != InstanceState.Status.Error) {
                            _state.value =
                                _state.value.copy(
                                    status = InstanceState.Status.Paused,
                                    framesPerSecond = null,
                                )
                        }
                    }
                } finally {
                    runCatching { engine?.close() }
                        .onFailure { throwable -> logger.error("Failed to close Doom", throwable) }
                    engine = null
                    runCatching(host::close)
                        .onFailure { throwable -> logger.error("Failed to close Doom audio", throwable) }
                }
            }.invokeOnCompletion { onExecutionFinished() }
    }

    fun setActive(active: Boolean) {
        if (!engineJob.isActive) return
        host.setActive(active)
        this.active.value = active
    }

    fun setFrameSink(sink: ((Frame) -> Unit)?) {
        frameSink.value = sink
    }

    fun clearFrameSink(sink: (Frame) -> Unit) {
        frameSink.compareAndSet(sink, null)
    }

    fun setKeyPressed(
        key: Key,
        pressed: Boolean,
    ) {
        input.trySend(InputCommand.ChangeKey(key, pressed))
    }

    fun tapKey(key: Key) {
        input.trySend(InputCommand.TapKey(key))
    }

    fun releaseAllKeys() {
        input.trySend(InputCommand.ReleaseAllKeys)
    }

    fun configure(settings: EngineSettings) {
        val normalizedSettings = settings.normalized()
        desiredSettings.value = normalizedSettings
        input.trySend(InputCommand.Configure(normalizedSettings))
    }

    override fun close() {
        if (!engineJob.isActive) return
        active.value = false
        frameSink.value = null
        input.close()
        engineScope.cancel()
    }

    private suspend fun runGame() {
        val doom =
            try {
                ensureEngine()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                logger.error("Failed to start Doom", throwable)
                _state.value =
                    _state.value.copy(
                        status = InstanceState.Status.Error,
                        errorMessage = throwable.message,
                        framesPerSecond = null,
                    )
                return
            }

        applySettings(doom, desiredSettings.value)

        _state.value =
            _state.value.copy(
                status = InstanceState.Status.Running,
                framesPerSecond = null,
                errorMessage = null,
            )
        val timeOrigin = TimeSource.Monotonic.markNow()
        var nextTickNanos = 0L
        var statsStartedAtNanos = 0L
        var completedTicks = 0

        var executionFailure: Throwable? = null
        try {
            while (currentCoroutineContext().isActive) {
                applyPendingInput(doom)
                updateInputMode(doom.tick())
                val tickFinishedAtNanos = timeOrigin.elapsedNow().inWholeNanoseconds
                completedTicks++

                val statsElapsedNanos = tickFinishedAtNanos - statsStartedAtNanos
                if (statsElapsedNanos >= NANOS_PER_STATS_WINDOW) {
                    val actualHertz = completedTicks * NANOS_PER_SECOND.toDouble() / statsElapsedNanos
                    _state.value =
                        _state.value.copy(
                            status = InstanceState.Status.Running,
                            framesPerSecond = actualHertz,
                        )
                    statsStartedAtNanos = tickFinishedAtNanos
                    completedTicks = 0
                }

                nextTickNanos += NANOS_PER_TICK
                val now = timeOrigin.elapsedNow().inWholeNanoseconds
                if (nextTickNanos < now - MAX_TICK_LAG_NANOS) nextTickNanos = now
                val delayMillis = (nextTickNanos - now) / NANOS_PER_MILLISECOND
                if (delayMillis > 0) delay(delayMillis)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Throwable) {
            executionFailure = throwable
            logger.error("Doom execution failed", throwable)
            _state.value =
                _state.value.copy(
                    status = InstanceState.Status.Error,
                    errorMessage = throwable.message,
                    framesPerSecond = null,
                )
        } finally {
            inputSequencer.reset { key ->
                runCatching { doom.keyUp(key) }
                    .onFailure { throwable -> logger.warning("Failed to release Doom key ${key.code}", throwable) }
            }
            while (input.tryReceive().isSuccess) {
                // Discard input queued before this instance was paused.
            }
        }

        if (executionFailure != null) {
            engine = null
            runCatching(doom::close)
                .onFailure { throwable -> logger.error("Failed to close crashed Doom engine", throwable) }
        }
    }

    private suspend fun ensureEngine(): Engine {
        engine?.let { return it }
        _state.value =
            _state.value.copy(
                status = InstanceState.Status.Loading,
                framesPerSecond = null,
                errorMessage = null,
            )

        val created = engineFactory.create(host)
        return try {
            updateInputMode(created.inputMode())
            created.also { engine = it }
        } catch (throwable: Throwable) {
            runCatching(created::close)
            throw throwable
        }
    }

    private fun applyPendingInput(doom: Engine) {
        while (true) {
            when (val command = input.tryReceive().getOrNull() ?: break) {
                is InputCommand.ChangeKey -> {
                    inputSequencer.setKeyPressed(command.key, command.pressed)
                }

                is InputCommand.TapKey -> {
                    inputSequencer.tap(command.key)
                }

                is InputCommand.Configure -> {
                    applySettings(doom, command.settings)
                }

                InputCommand.ReleaseAllKeys -> {
                    inputSequencer.releaseAll()
                }
            }
        }

        inputSequencer.apply(doom::keyDown, doom::keyUp)
    }

    private fun updateInputMode(inputMode: InputMode) {
        if (_state.value.inputMode != inputMode) {
            _state.value = _state.value.copy(inputMode = inputMode)
        }
    }

    private fun applySettings(
        doom: Engine,
        settings: EngineSettings,
    ) {
        doom.configureView(
            lowDetail = settings.detail == Detail.Low,
            screenBlocks = settings.border.screenBlocks,
        )
        doom.setGammaLevel(settings.gammaLevel)
    }

    private sealed interface InputCommand {
        data class ChangeKey(
            val key: Key,
            val pressed: Boolean,
        ) : InputCommand

        data class TapKey(
            val key: Key,
        ) : InputCommand

        data class Configure(
            val settings: EngineSettings,
        ) : InputCommand

        data object ReleaseAllKeys : InputCommand
    }

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val NANOS_PER_TICK = NANOS_PER_SECOND / 35
        const val NANOS_PER_STATS_WINDOW = NANOS_PER_SECOND
        const val MAX_TICK_LAG_NANOS = NANOS_PER_TICK * 4
    }
}
