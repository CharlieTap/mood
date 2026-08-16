package com.tap.mood.doom.runtime.host

interface SaveStore {
    fun load(slot: Int): ByteArray?

    fun size(slot: Int): Int

    fun save(
        slot: Int,
        data: ByteArray,
    ): Boolean
}

object SaveSlots {
    val range = 0..9

    fun requireValid(slot: Int) {
        require(slot in range) { "Invalid Doom save slot: $slot" }
    }
}
