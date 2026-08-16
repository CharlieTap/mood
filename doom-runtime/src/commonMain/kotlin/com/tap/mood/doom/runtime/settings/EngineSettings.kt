package com.tap.mood.doom.runtime.settings

enum class Detail {
    High,
    Low,
}

enum class Border(
    val screenBlocks: Int,
) {
    Large(10),
    Medium(8),
    Small(6),
}

data class EngineSettings(
    val detail: Detail = Detail.High,
    val border: Border = Border.Large,
    val gammaLevel: Int = 0,
) {
    fun normalized(): EngineSettings = copy(gammaLevel = gammaLevel.coerceIn(0, 4))
}
