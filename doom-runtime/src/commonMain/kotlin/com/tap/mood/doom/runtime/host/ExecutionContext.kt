package com.tap.mood.doom.runtime.host

import kotlinx.coroutines.CoroutineDispatcher

interface ExecutionContext : AutoCloseable {
    val dispatcher: CoroutineDispatcher
}

fun interface ExecutionContextFactory {
    fun create(): ExecutionContext
}
