package com.tap.mood.doom.runtime.input

enum class InputMode {
    Gameplay,
    Menu,
    Confirmation,
    TextEntry,
    ;

    companion object {
        internal fun fromCode(code: Int): InputMode = entries.getOrNull(code) ?: error("Unknown Doom input mode: $code")
    }
}
