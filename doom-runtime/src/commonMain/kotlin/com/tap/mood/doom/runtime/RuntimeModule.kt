package com.tap.mood.doom.runtime

import com.tap.mood.doom.runtime.engine.Binary
import com.tap.mood.doom.runtime.engine.Resource
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides

@ContributesTo(AppScope::class)
interface RuntimeModule {
    companion object {
        @Provides
        fun provideBinary(): Binary = Resource
    }
}
