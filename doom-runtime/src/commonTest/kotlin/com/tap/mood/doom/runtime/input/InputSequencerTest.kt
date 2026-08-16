package com.tap.mood.doom.runtime.input

import kotlin.test.Test
import kotlin.test.assertEquals

class InputSequencerTest {
    private val events = mutableListOf<String>()
    private val sequencer = InputSequencer()

    @Test
    fun heldKeyIsOnlyChangedAtItsEdges() {
        sequencer.setKeyPressed(Key.FIRE, true)
        apply()
        apply()
        sequencer.setKeyPressed(Key.FIRE, false)
        apply()

        assertEquals(listOf("down:${Key.FIRE.code}", "up:${Key.FIRE.code}"), events)
    }

    @Test
    fun tapsAreOrderedAndHeldForOneTick() {
        sequencer.tap(Key.ENTER)
        sequencer.tap(Key.ESCAPE)

        apply()
        apply()
        apply()

        assertEquals(
            listOf(
                "down:${Key.ENTER.code}",
                "up:${Key.ENTER.code}",
                "down:${Key.ESCAPE.code}",
                "up:${Key.ESCAPE.code}",
            ),
            events,
        )
    }

    @Test
    fun releaseAllReleasesEveryHeldKey() {
        sequencer.setKeyPressed(Key.UP, true)
        sequencer.setKeyPressed(Key.FIRE, true)
        apply()
        sequencer.releaseAll()
        apply()

        assertEquals(
            listOf(
                "down:${Key.UP.code}",
                "down:${Key.FIRE.code}",
                "up:${Key.UP.code}",
                "up:${Key.FIRE.code}",
            ),
            events,
        )
    }

    private fun apply() {
        sequencer.apply(
            keyDown = { key -> events += "down:${key.code}" },
            keyUp = { key -> events += "up:${key.code}" },
        )
    }
}
