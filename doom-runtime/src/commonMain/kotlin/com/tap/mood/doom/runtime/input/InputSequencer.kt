package com.tap.mood.doom.runtime.input

import kotlin.collections.ArrayDeque

internal class InputSequencer {
    private val requestedKeys = linkedSetOf<Key>()
    private val pressedKeys = linkedSetOf<Key>()
    private val pendingTaps = ArrayDeque<Key>()
    private var activeTap: Key? = null

    fun setKeyPressed(
        key: Key,
        pressed: Boolean,
    ) {
        if (pressed) {
            requestedKeys.add(key)
        } else {
            val wasRequested = requestedKeys.remove(key)
            if (wasRequested && key !in pressedKeys) pendingTaps.addLast(key)
        }
    }

    fun tap(key: Key) {
        pendingTaps.addLast(key)
    }

    fun releaseAll() {
        requestedKeys.clear()
        pendingTaps.clear()
    }

    fun apply(
        keyDown: (Key) -> Unit,
        keyUp: (Key) -> Unit,
    ) {
        activeTap?.let { key ->
            keyUp(key)
            pressedKeys.remove(key)
            activeTap = null
        }

        val pressedIterator = pressedKeys.iterator()
        while (pressedIterator.hasNext()) {
            val key = pressedIterator.next()
            if (key !in requestedKeys) {
                keyUp(key)
                pressedIterator.remove()
            }
        }

        requestedKeys.forEach { key ->
            if (pressedKeys.add(key)) keyDown(key)
        }

        if (pendingTaps.isNotEmpty()) {
            val key = pendingTaps.removeFirst()
            if (pressedKeys.remove(key)) keyUp(key)
            keyDown(key)
            pressedKeys.add(key)
            activeTap = key
        }
    }

    fun reset(keyUp: (Key) -> Unit) {
        pressedKeys.forEach(keyUp)
        requestedKeys.clear()
        pressedKeys.clear()
        pendingTaps.clear()
        activeTap = null
    }
}
