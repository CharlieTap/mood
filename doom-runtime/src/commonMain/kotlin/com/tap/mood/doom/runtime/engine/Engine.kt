package com.tap.mood.doom.runtime.engine

import com.tap.mood.doom.runtime.generated.DoomWasmModule
import com.tap.mood.doom.runtime.generated.DoomWasmModuleImpl
import com.tap.mood.doom.runtime.host.Host
import com.tap.mood.doom.runtime.input.InputMode
import com.tap.mood.doom.runtime.input.Key
import dev.zacsweers.metro.Inject

fun interface Binary {
    suspend fun load(): ByteArray
}

@Inject
class EngineFactory(private val doomBinary: Binary) {
    suspend fun create(host: Host): Engine =
        Engine.create(
            binary = doomBinary.load(),
            host = host,
        )
}

class Engine private constructor(
    private val module: DoomWasmModule,
    private val runtime: Runtime,
) : AutoCloseable {
    private var closed = false

    fun tick(): InputMode {
        checkOpen()
        return InputMode.fromCode(module.tickGame())
    }

    fun keyDown(key: Key) {
        checkOpen()
        module.reportKeyDown(key.code)
    }

    fun keyUp(key: Key) {
        checkOpen()
        module.reportKeyUp(key.code)
    }

    fun inputMode(): InputMode {
        checkOpen()
        return InputMode.fromCode(module.getInputMode())
    }

    fun configureView(
        lowDetail: Boolean,
        screenBlocks: Int,
    ) {
        checkOpen()
        module.configureView(if (lowDetail) 1 else 0, screenBlocks)
    }

    fun setGammaLevel(gammaLevel: Int) {
        checkOpen()
        module.setGammaLevel(gammaLevel)
    }

    override fun close() {
        if (closed) return
        closed = true
        runtime.close()
    }

    private fun checkOpen() {
        check(!closed) { "Doom engine is closed" }
    }

    companion object {
        internal suspend fun create(
            binary: ByteArray,
            host: Host,
        ): Engine {
            val runtime = Runtime(host)

            return try {
                val module =
                    DoomWasmModuleImpl.create(
                        binary = binary,
                        imports = runtime.imports,
                    )
                runtime.attach(module.memory)
                module.initGame()
                Engine(module, runtime)
            } catch (throwable: Throwable) {
                runtime.close()
                throw throwable
            }
        }
    }
}
