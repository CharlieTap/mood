package com.tap.mood.game.controls

class HeldActions<Action>(
    private val onChanged: (Action, Boolean) -> Unit,
) {
    private val counts = mutableMapOf<Action, Int>()

    fun acquire(action: Action) {
        val count = counts[action] ?: 0
        counts[action] = count + 1
        if (count == 0) onChanged(action, true)
    }

    fun release(action: Action) {
        val count = counts[action] ?: return
        if (count == 1) {
            counts.remove(action)
            onChanged(action, false)
        } else {
            counts[action] = count - 1
        }
    }

    fun releaseAll() {
        counts.keys.toList().forEach { action -> onChanged(action, false) }
        counts.clear()
    }
}

class ActionBinding<Action>(
    private val heldActions: HeldActions<Action>,
) {
    private var actions: Set<Action> = emptySet()

    fun update(next: Set<Action>) {
        (actions - next).forEach(heldActions::release)
        (next - actions).forEach(heldActions::acquire)
        actions = next
    }

    fun release() {
        update(emptySet())
    }
}
