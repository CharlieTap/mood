package com.tap.mood.viewmodel

import androidx.lifecycle.ViewModel
import com.tap.mood.doom.runtime.instance.DefaultInstanceController
import com.tap.mood.doom.runtime.instance.InstanceController
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey

@ContributesIntoMap(AppScope::class, binding<ViewModel>())
@ViewModelKey
@Inject
class MoodViewModel(
    private val controller: DefaultInstanceController,
) : ViewModel(),
    InstanceController by controller {
    init {
        addCloseable(controller)
    }
}
