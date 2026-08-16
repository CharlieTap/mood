package com.tap.mood.doom.runtime.engine

import com.tap.mood.doom.runtime.resources.Res

object Resource : Binary {
    const val RESOURCE_PATH = "files/doom/doom.wasm"

    override suspend fun load(): ByteArray = Res.readBytes(RESOURCE_PATH)
}
