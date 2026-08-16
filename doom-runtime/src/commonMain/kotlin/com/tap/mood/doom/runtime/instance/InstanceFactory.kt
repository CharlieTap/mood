package com.tap.mood.doom.runtime.instance

import com.tap.mood.doom.runtime.engine.EngineFactory
import com.tap.mood.doom.runtime.host.AudioSinkFactory
import com.tap.mood.doom.runtime.host.ExecutionContextFactory
import com.tap.mood.doom.runtime.host.Logger
import com.tap.mood.doom.runtime.host.SaveStore
import dev.zacsweers.metro.Inject

@Inject
class InstanceFactory(
    private val engineFactory: EngineFactory,
    private val saveStore: SaveStore,
    private val audioSinkFactory: AudioSinkFactory,
    private val executionContextFactory: ExecutionContextFactory,
    private val logger: Logger,
) {
    fun create(): Instance {
        val executionContext = executionContextFactory.create()
        return try {
            val audioSink = audioSinkFactory.create()
            try {
                Instance(
                    engineFactory = engineFactory,
                    saveStore = saveStore,
                    audioSink = audioSink,
                    dispatcher = executionContext.dispatcher,
                    logger = logger,
                    onExecutionFinished = executionContext::close,
                )
            } catch (throwable: Throwable) {
                audioSink.close()
                throw throwable
            }
        } catch (throwable: Throwable) {
            executionContext.close()
            throw throwable
        }
    }
}
