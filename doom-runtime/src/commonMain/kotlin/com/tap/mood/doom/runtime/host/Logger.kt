package com.tap.mood.doom.runtime.host

interface Logger {
    fun debug(message: String) = Unit

    fun info(message: String) = Unit

    fun warning(
        message: String,
        throwable: Throwable? = null,
    ) = Unit

    fun error(
        message: String,
        throwable: Throwable? = null,
    ) = Unit

    companion object {
        val None: Logger = object : Logger {}
    }
}
