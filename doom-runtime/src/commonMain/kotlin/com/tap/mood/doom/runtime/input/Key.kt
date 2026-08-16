package com.tap.mood.doom.runtime.input

import kotlin.jvm.JvmInline

@JvmInline
value class Key private constructor(
    val code: Int,
) {
    companion object {
        val LEFT = Key(172)
        val RIGHT = Key(174)
        val UP = Key(173)
        val DOWN = Key(175)
        val STRAFE_LEFT = Key(160)
        val STRAFE_RIGHT = Key(161)
        val FIRE = Key(163)
        val USE = Key(162)
        val SHIFT = Key(182)
        val TAB = Key(9)
        val ESCAPE = Key(27)
        val ENTER = Key(13)
        val BACKSPACE = Key(127)
        val ALT = Key(184)

        fun character(character: Char): Key {
            val normalized = character.lowercaseChar()
            require(normalized.code in PRINTABLE_ASCII) {
                "Doom character keys must be printable ASCII: $character"
            }
            return Key(normalized.code)
        }

        private val PRINTABLE_ASCII = 32..126
    }
}
