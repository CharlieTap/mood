@file:OptIn(kotlin.wasm.unsafe.UnsafeWasmMemoryApi::class)

package com.tap.mood.graphics

import kotlin.wasm.unsafe.withScopedMemoryAllocator

inline fun <T> ByteArray.withLinearMemoryAddress(block: (Int) -> T): T =
    withScopedMemoryAllocator { allocator ->
        val pointer = allocator.allocate(size)
        var index = 0
        while (index + Int.SIZE_BYTES <= size) {
            val value =
                (this[index].toInt() and 0xff) or
                    ((this[index + 1].toInt() and 0xff) shl 8) or
                    ((this[index + 2].toInt() and 0xff) shl 16) or
                    ((this[index + 3].toInt() and 0xff) shl 24)
            (pointer + index).storeInt(value)
            index += Int.SIZE_BYTES
        }
        while (index < size) (pointer + index).storeByte(this[index++])
        block(pointer.address.toInt())
    }
