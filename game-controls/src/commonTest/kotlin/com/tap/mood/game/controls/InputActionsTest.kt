package com.tap.mood.game.controls

import kotlin.test.Test
import kotlin.test.assertEquals

class InputActionsTest {
    @Test
    fun overlappingOwnersOnlyEmitLogicalEdges() {
        val events = mutableListOf<Pair<String, Boolean>>()
        val held = HeldActions<String> { action, pressed -> events += action to pressed }

        held.acquire("fire")
        held.acquire("fire")
        held.release("fire")
        held.release("fire")

        assertEquals(listOf("fire" to true, "fire" to false), events)
    }

    @Test
    fun bindingChangesDirectionWithoutReleasingSharedActions() {
        val events = mutableListOf<Pair<String, Boolean>>()
        val held = HeldActions<String> { action, pressed -> events += action to pressed }
        val first = ActionBinding(held)
        val second = ActionBinding(held)

        first.update(setOf("up", "left"))
        second.update(setOf("up"))
        first.update(setOf("right"))
        second.release()
        first.release()

        assertEquals(
            listOf(
                "up" to true,
                "left" to true,
                "left" to false,
                "right" to true,
                "up" to false,
                "right" to false,
            ),
            events,
        )
    }

    @Test
    fun releaseAllCancelsEveryAction() {
        val events = mutableListOf<Pair<String, Boolean>>()
        val held = HeldActions<String> { action, pressed -> events += action to pressed }

        held.acquire("up")
        held.acquire("fire")
        held.releaseAll()

        assertEquals(
            listOf("up" to true, "fire" to true, "up" to false, "fire" to false),
            events,
        )
    }
}
