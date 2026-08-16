package com.tap.mood.doom.runtime.instance

import com.tap.mood.doom.runtime.input.InputMode

data class InstanceState(
    val status: Status = Status.Idle,
    val framesPerSecond: Double? = null,
    val errorMessage: String? = null,
    val inputMode: InputMode = InputMode.Gameplay,
) {
    enum class Status {
        Idle,
        Loading,
        Running,
        Paused,
        Error,
    }
}
