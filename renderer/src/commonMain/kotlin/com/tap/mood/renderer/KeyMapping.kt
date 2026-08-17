package com.tap.mood.renderer

import com.tap.mood.doom.runtime.input.InputMode
import com.tap.mood.doom.runtime.input.Key

fun InputMode.primaryKeys(): List<Key> =
    when (this) {
        InputMode.Gameplay -> listOf(Key.FIRE)
        InputMode.Menu, InputMode.TextEntry -> listOf(Key.ENTER)
        InputMode.Confirmation -> listOf(Key.character('y'))
    }

fun InputMode.backKeys(): List<Key> =
    when (this) {
        InputMode.Gameplay -> listOf(Key.ESCAPE)
        InputMode.Menu -> listOf(Key.BACKSPACE)
        InputMode.Confirmation -> listOf(Key.character('n'))
        InputMode.TextEntry -> listOf(Key.ESCAPE)
    }
