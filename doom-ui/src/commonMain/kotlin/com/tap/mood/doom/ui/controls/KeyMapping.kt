package com.tap.mood.doom.ui.controls

import com.tap.mood.doom.runtime.input.InputMode
import com.tap.mood.doom.runtime.input.Key

internal fun InputMode.primaryKeys(): List<Key> =
    when (this) {
        InputMode.Gameplay -> listOf(Key.FIRE)
        InputMode.Menu, InputMode.TextEntry -> listOf(Key.ENTER)
        InputMode.Confirmation -> listOf(Key.character('y'))
    }

internal fun InputMode.backKeys(): List<Key> =
    when (this) {
        InputMode.Gameplay -> listOf(Key.ESCAPE)
        InputMode.Menu -> listOf(Key.BACKSPACE)
        InputMode.Confirmation -> listOf(Key.character('n'))
        InputMode.TextEntry -> listOf(Key.ESCAPE)
    }
