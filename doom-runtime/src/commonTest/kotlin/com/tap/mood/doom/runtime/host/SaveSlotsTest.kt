package com.tap.mood.doom.runtime.host

import kotlin.test.Test
import kotlin.test.assertFailsWith

class SaveSlotsTest {
    @Test
    fun acceptsEveryEngineSlot() {
        SaveSlots.range.forEach(SaveSlots::requireValid)
    }

    @Test
    fun rejectsSlotsOutsideTheEngineRange() {
        assertFailsWith<IllegalArgumentException> { SaveSlots.requireValid(-1) }
        assertFailsWith<IllegalArgumentException> { SaveSlots.requireValid(10) }
    }
}
