package com.tap.mood.doom.runtime.engine

import com.tap.mood.doom.runtime.generated.DoomWasmModule
import com.tap.mood.doom.runtime.host.Host
import io.github.charlietap.chasm.vm.FunctionType
import io.github.charlietap.chasm.vm.HostFunction
import io.github.charlietap.chasm.vm.NumberType
import io.github.charlietap.chasm.vm.ValueType
import io.github.charlietap.chasm.vm.WasmVirtualMachine.Value
import io.github.charlietap.chasm.vm.codegen.CodegenImport
import io.github.charlietap.chasm.vm.codegen.FunctionImport

internal class Runtime(
    private val host: Host,
) {
    private lateinit var memory: DoomWasmModule.Memory
    private var closed = false
    private var frameWidth = 0
    private var frameHeight = 0
    private var frameIndices = ByteArray(0)
    private var framePixels = ByteArray(0)
    private var commandBuffer = ByteArray(0)
    private var audioBuffer = ByteArray(0)
    private var memoryPages = arrayOfNulls<ByteArray>(0)
    private var memoryPageGenerations = IntArray(0)
    private var memoryGeneration = 0
    private var currentMemoryByteCount = 0
    private val paletteBytes = ByteArray(PALETTE_BYTE_COUNT)
    private val palette = IntArray(PALETTE_COLOR_COUNT)
    private val integerBytes = ByteArray(Int.SIZE_BYTES)
    private val customWads = host.wads().map(ByteArray::copyOf)
    private val customWadBytes = customWads.sumOf { wad -> wad.size.toLong() }

    init {
        requireHost(customWadBytes <= Int.MAX_VALUE) {
            "Combined WAD data is too large: $customWadBytes"
        }
    }

    fun attach(memory: DoomWasmModule.Memory) {
        checkOpen()
        this.memory = memory
    }

    val imports: List<CodegenImport> =
        listOf(
            functionImport(
                moduleName = "audio",
                entityName = "submitPcm",
                params = listOf(NumberType.I32, NumberType.I32, NumberType.I32),
            ) { values ->
                submitPcm(
                    pointer = values.i32(0),
                    frameCount = values.i32(1),
                    sampleRate = values.i32(2),
                )
                emptyList()
            },
            functionImport(
                moduleName = "runtimeControl",
                entityName = "timeInMilliseconds",
                results = listOf(NumberType.I64),
            ) {
                listOf(Value.I64(host.timeInMilliseconds()))
            },
            functionImport(
                moduleName = "console",
                entityName = "onInfoMessage",
                params = listOf(NumberType.I32, NumberType.I32),
            ) { values ->
                host.onInfoMessage(readString(values))
                emptyList()
            },
            functionImport(
                moduleName = "console",
                entityName = "onErrorMessage",
                params = listOf(NumberType.I32, NumberType.I32),
            ) { values ->
                host.onErrorMessage(readString(values))
                emptyList()
            },
            functionImport(
                moduleName = "gameSaving",
                entityName = "writeSaveGame",
                params = listOf(NumberType.I32, NumberType.I32, NumberType.I32),
                results = listOf(NumberType.I32),
            ) { values ->
                val slot = values.i32(0)
                val pointer = values.i32(1)
                val length = values.i32(2)
                requireHost(length in 0..MAX_SAVE_BYTES) { "Invalid save-game length: $length" }
                val data = readBytes(pointer, length)
                listOf(Value.I32(if (host.saveGame(slot, data)) length else 0))
            },
            functionImport(
                moduleName = "gameSaving",
                entityName = "readSaveGame",
                params = listOf(NumberType.I32, NumberType.I32),
                results = listOf(NumberType.I32),
            ) { values ->
                val slot = values.i32(0)
                val destination = values.i32(1)
                val data = host.loadSaveGame(slot)
                if (data == null) {
                    listOf(Value.I32(0))
                } else {
                    requireHost(data.size <= MAX_SAVE_BYTES) { "Save game is too large: ${data.size}" }
                    writeBytes(destination, data)
                    listOf(Value.I32(data.size))
                }
            },
            functionImport(
                moduleName = "gameSaving",
                entityName = "sizeOfSaveGame",
                params = listOf(NumberType.I32),
                results = listOf(NumberType.I32),
            ) { values ->
                val size = host.saveGameSize(values.i32(0))
                requireHost(size >= 0) { "Invalid save-game size: $size" }
                requireHost(size <= MAX_SAVE_BYTES) { "Save game is too large: $size" }
                listOf(Value.I32(size))
            },
            functionImport(
                moduleName = "ui",
                entityName = "setPalette",
                params = listOf(NumberType.I32),
            ) { values ->
                readBytes(values.i32(0), paletteBytes)
                updatePalette()
                emptyList()
            },
            functionImport(
                moduleName = "ui",
                entityName = "drawFrame",
                params = listOf(NumberType.I32),
            ) { values ->
                requireHost(frameIndices.isNotEmpty()) { "Doom drew a frame before initializing its display" }
                readBytes(values.i32(0), frameIndices)
                host.onFrame(
                    Frame(
                        frameWidth,
                        frameHeight,
                        frameIndices,
                        palette,
                        framePixels,
                    ),
                )
                emptyList()
            },
            functionImport(
                moduleName = "ui",
                entityName = "renderCommands",
                params = listOf(NumberType.I32, NumberType.I32, NumberType.I32, NumberType.I32),
            ) { values ->
                renderCommands(
                    commandsPointer = values.i32(0),
                    commandCount = values.i32(1),
                    framebufferPointer = values.i32(2),
                    memoryByteCount = values.i32(3),
                )
                emptyList()
            },
            functionImport(
                moduleName = "loading",
                entityName = "readWads",
                params = listOf(NumberType.I32, NumberType.I32),
            ) { values ->
                var dataPointer = values.i32(0)
                var lengthPointer = values.i32(1)
                customWads.forEach { wad ->
                    writeBytes(dataPointer, wad)
                    writeInt(lengthPointer, wad.size)
                    dataPointer += wad.size
                    lengthPointer += Int.SIZE_BYTES
                }
                emptyList()
            },
            functionImport(
                moduleName = "loading",
                entityName = "wadSizes",
                params = listOf(NumberType.I32, NumberType.I32),
            ) { values ->
                writeInt(values.i32(0), customWads.size)
                writeInt(values.i32(1), customWadBytes.toInt())
                emptyList()
            },
            functionImport(
                moduleName = "loading",
                entityName = "onGameInit",
                params = listOf(NumberType.I32, NumberType.I32),
            ) { values ->
                configureFrame(values.i32(0), values.i32(1))
                emptyList()
            },
        )

    fun close() {
        if (closed) return
        closed = true
        frameIndices = ByteArray(0)
        framePixels = ByteArray(0)
        commandBuffer = ByteArray(0)
        audioBuffer = ByteArray(0)
        memoryPages = arrayOfNulls(0)
        memoryPageGenerations = IntArray(0)
    }

    private fun configureFrame(
        width: Int,
        height: Int,
    ) {
        requireHost(width > 0 && height > 0) {
            "Invalid Doom frame dimensions: ${width}x$height"
        }
        val pixelCount = width.toLong() * height
        requireHost(pixelCount <= MAX_FRAME_BYTES / BYTES_PER_PIXEL) {
            "Invalid Doom frame dimensions: ${width}x$height"
        }
        val byteCount = pixelCount * BYTES_PER_PIXEL
        frameWidth = width
        frameHeight = height
        frameIndices = ByteArray(pixelCount.toInt())
        framePixels = ByteArray(byteCount.toInt())
        host.onGameInitialized(width, height)
    }

    private fun submitPcm(
        pointer: Int,
        frameCount: Int,
        sampleRate: Int,
    ) {
        requireHost(frameCount in 0..MAX_AUDIO_FRAMES) { "Invalid Doom audio frame count: $frameCount" }
        requireHost(sampleRate in MIN_SAMPLE_RATE..MAX_SAMPLE_RATE) { "Invalid Doom audio sample rate: $sampleRate" }
        val byteCount = frameCount * AUDIO_BYTES_PER_FRAME
        if (audioBuffer.size != byteCount) audioBuffer = ByteArray(byteCount)
        readBytes(pointer, audioBuffer)
        host.onAudio(AudioFrame(sampleRate, frameCount, audioBuffer))
    }

    private fun renderCommands(
        commandsPointer: Int,
        commandCount: Int,
        framebufferPointer: Int,
        memoryByteCount: Int,
    ) {
        requireHost(commandCount in 0..MAX_RENDER_COMMANDS) { "Invalid render command count: $commandCount" }
        requireHost(memoryByteCount in frameIndices.size..MAX_DOOM_MEMORY_BYTES) {
            "Invalid Doom memory size: $memoryByteCount"
        }

        val framebufferEnd = framebufferPointer.toLong() + frameIndices.size
        requireHost(framebufferPointer >= 0 && framebufferEnd <= memoryByteCount) {
            "Invalid framebuffer pointer: $framebufferPointer"
        }
        readBytes(framebufferPointer, frameIndices)

        val commandByteCount = commandCount * RENDER_COMMAND_BYTES
        val commandsEnd = commandsPointer.toLong() + commandByteCount
        requireHost(commandsPointer >= 0 && commandsEnd <= memoryByteCount) {
            "Invalid render command buffer: $commandsPointer + $commandCount commands"
        }
        if (commandBuffer.size < commandByteCount) commandBuffer = ByteArray(commandByteCount)
        if (commandByteCount > 0) memory.read(commandBuffer, commandsPointer, bytesToRead = commandByteCount)
        beginMemoryGeneration(memoryByteCount)

        var commandPointer = 0
        repeat(commandCount) {
            val kind = commandBuffer.readInt(commandPointer)
            var destination = commandBuffer.readInt(commandPointer + 4) - framebufferPointer
            val pixelCount = commandBuffer.readInt(commandPointer + 8)
            val source = commandBuffer.readInt(commandPointer + 12)
            val colormap = commandBuffer.readInt(commandPointer + 16)
            var position = commandBuffer.readInt(commandPointer + 20)
            val step = commandBuffer.readInt(commandPointer + 24)
            requireHost(pixelCount in 0..MAX_RENDER_COMMAND_PIXELS) {
                "Invalid render command pixel count: $pixelCount"
            }

            when (kind) {
                RENDER_COMMAND_COLUMN -> {
                    repeat(pixelCount) {
                        val textureIndex = memoryByte(source + ((position ushr 16) and 127)).toInt() and 0xff
                        frameIndices[destination] = memoryByte(colormap + textureIndex)
                        destination += frameWidth
                        position += step
                    }
                }

                RENDER_COMMAND_SPAN -> {
                    repeat(pixelCount) {
                        val textureIndex = ((position ushr 4) and 0x0fc0) or (position ushr 26)
                        val paletteIndex = memoryByte(source + textureIndex).toInt() and 0xff
                        frameIndices[destination++] = memoryByte(colormap + paletteIndex)
                        position += step
                    }
                }

                RENDER_COMMAND_COLUMN_LOW -> {
                    repeat(pixelCount) {
                        val textureIndex = memoryByte(source + ((position ushr 16) and 127)).toInt() and 0xff
                        val paletteIndex = memoryByte(colormap + textureIndex)
                        frameIndices[destination] = paletteIndex
                        frameIndices[destination + 1] = paletteIndex
                        destination += frameWidth
                        position += step
                    }
                }

                RENDER_COMMAND_SPAN_LOW -> {
                    repeat(pixelCount) {
                        val textureIndex = ((position ushr 4) and 0x0fc0) or (position ushr 26)
                        val paletteIndex = memoryByte(source + textureIndex).toInt() and 0xff
                        val colorIndex = memoryByte(colormap + paletteIndex)
                        frameIndices[destination++] = colorIndex
                        frameIndices[destination++] = colorIndex
                        position += step
                    }
                }

                else -> {
                    error("Unknown Doom render command kind: $kind")
                }
            }
            commandPointer += RENDER_COMMAND_BYTES
        }

        writeBytes(framebufferPointer, frameIndices)
    }

    private fun beginMemoryGeneration(memoryByteCount: Int) {
        val pageCount = (memoryByteCount + MEMORY_PAGE_SIZE - 1) / MEMORY_PAGE_SIZE
        if (memoryPages.size != pageCount) {
            memoryPages = arrayOfNulls(pageCount)
            memoryPageGenerations = IntArray(pageCount)
        }
        currentMemoryByteCount = memoryByteCount
        if (memoryGeneration == Int.MAX_VALUE) {
            memoryPageGenerations.fill(0)
            memoryGeneration = 1
        } else {
            memoryGeneration++
        }
    }

    private fun memoryByte(pointer: Int): Byte {
        requireHost(pointer in 0..<currentMemoryByteCount) { "Invalid Doom memory pointer: $pointer" }
        val pageIndex = pointer / MEMORY_PAGE_SIZE
        val page =
            memoryPages[pageIndex] ?: ByteArray(MEMORY_PAGE_SIZE).also { memoryPages[pageIndex] = it }
        if (memoryPageGenerations[pageIndex] != memoryGeneration) {
            val pagePointer = pageIndex * MEMORY_PAGE_SIZE
            memory.read(
                page,
                pagePointer,
                bytesToRead = minOf(MEMORY_PAGE_SIZE, currentMemoryByteCount - pagePointer),
            )
            memoryPageGenerations[pageIndex] = memoryGeneration
        }
        return page[pointer % MEMORY_PAGE_SIZE]
    }

    private fun updatePalette() {
        var colorIndex = 0
        var byteIndex = 0
        while (colorIndex < palette.size) {
            val red = paletteBytes[byteIndex].toInt() and 0xff
            val green = paletteBytes[byteIndex + 1].toInt() and 0xff
            val blue = paletteBytes[byteIndex + 2].toInt() and 0xff
            palette[colorIndex] = blue or (green shl 8) or (red shl 16)
            colorIndex++
            byteIndex += PALETTE_COMPONENT_COUNT
        }
    }

    private fun readString(values: List<Value>): String {
        val pointer = values.i32(0)
        val length = values.i32(1)
        requireHost(length in 0..MAX_MESSAGE_BYTES) { "Invalid Doom message length: $length" }
        return readBytes(pointer, length).decodeToString()
    }

    private fun readBytes(
        pointer: Int,
        length: Int,
    ): ByteArray = ByteArray(length).also { buffer -> readBytes(pointer, buffer) }

    private fun readBytes(
        pointer: Int,
        buffer: ByteArray,
    ) {
        checkOpen()
        memory.read(buffer, pointer)
    }

    private fun writeBytes(
        pointer: Int,
        bytes: ByteArray,
    ) {
        checkOpen()
        memory.write(pointer, bytes)
    }

    private fun writeInt(
        pointer: Int,
        value: Int,
    ) {
        checkOpen()
        integerBytes.writeInt(value)
        memory.write(pointer, integerBytes)
    }

    private fun checkOpen() {
        check(!closed) { "Doom runtime is closed" }
    }

    private fun List<Value>.i32(index: Int): Int =
        (getOrNull(index) as? Value.I32)?.value
            ?: error("Expected i32 argument at index $index")

    private inline fun requireHost(
        condition: Boolean,
        message: () -> String,
    ) {
        if (!condition) error(message())
    }

    private companion object {
        const val BYTES_PER_PIXEL = 4
        const val PALETTE_COLOR_COUNT = 256
        const val PALETTE_COMPONENT_COUNT = 3
        const val PALETTE_BYTE_COUNT = PALETTE_COLOR_COUNT * PALETTE_COMPONENT_COUNT
        const val MAX_FRAME_BYTES = 16L * 1024 * 1024
        const val MAX_DOOM_MEMORY_BYTES = 32 * 1024 * 1024
        const val MEMORY_PAGE_SIZE = 64 * 1024
        const val MAX_MESSAGE_BYTES = 1024 * 1024
        const val MAX_SAVE_BYTES = 8 * 1024 * 1024
        const val AUDIO_BYTES_PER_FRAME = 4
        const val MAX_AUDIO_FRAMES = 8192
        const val MIN_SAMPLE_RATE = 4_000
        const val MAX_SAMPLE_RATE = 192_000
        const val MAX_RENDER_COMMANDS = 4096
        const val MAX_RENDER_COMMAND_PIXELS = 320 * 200
        const val RENDER_COMMAND_COLUMN = 0
        const val RENDER_COMMAND_SPAN = 1
        const val RENDER_COMMAND_COLUMN_LOW = 2
        const val RENDER_COMMAND_SPAN_LOW = 3
        const val RENDER_COMMAND_BYTES = 7 * Int.SIZE_BYTES
    }
}

private fun ByteArray.readInt(pointer: Int): Int =
    (this[pointer].toInt() and 0xff) or
        ((this[pointer + 1].toInt() and 0xff) shl 8) or
        ((this[pointer + 2].toInt() and 0xff) shl 16) or
        ((this[pointer + 3].toInt() and 0xff) shl 24)

private fun ByteArray.writeInt(value: Int) {
    this[0] = value.toByte()
    this[1] = (value ushr 8).toByte()
    this[2] = (value ushr 16).toByte()
    this[3] = (value ushr 24).toByte()
}

private fun functionImport(
    moduleName: String,
    entityName: String,
    params: List<NumberType> = emptyList(),
    results: List<NumberType> = emptyList(),
    function: HostFunction,
): FunctionImport =
    FunctionImport(
        moduleName = moduleName,
        entityName = entityName,
        type =
            FunctionType(
                params = params.map { type -> ValueType.Number(type) },
                results = results.map { type -> ValueType.Number(type) },
            ),
        function = function,
    )
