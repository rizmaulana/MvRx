@file:Suppress("Detekt.ParameterListWrapping")

package com.airbnb.mvrx.launcher

import com.airbnb.epoxy.AsyncEpoxyController
import com.airbnb.epoxy.EpoxyController
import com.airbnb.mvrx.MavericksViewModel
import com.airbnb.mvrx.MavericksState
import com.airbnb.mvrx.withState

/**
 * For use with [MvRxLauncherBaseFragment.epoxyController].
 *
 * This builds Epoxy models in a background thread.
 */
internal open class MvRxEpoxyController(
    val buildModelsCallback: EpoxyController.() -> Unit = {}
) : AsyncEpoxyController() {

    override fun buildModels() {
        buildModelsCallback()
    }
}

/**
 * Create a [MvRxEpoxyController] that builds models with the given callback.
 */
fun MvRxLauncherBaseFragment.simpleController(
    buildModels: EpoxyController.() -> Unit
): EpoxyController = MvRxEpoxyController {
    // Models are built asynchronously, so it is possible that this is called after the fragment
    // is detached under certain race conditions.
    if (view == null || isRemoving) return@MvRxEpoxyController
    buildModels()
}

/**
 * Create a [MvRxEpoxyController] that builds models with the given callback.
 * When models are built the current state of the viewmodel will be provided.
 */
fun <S : MavericksState, A : MavericksViewModel<S>> MvRxLauncherBaseFragment.simpleController(
    viewModel: A,
    buildModels: EpoxyController.(state: S) -> Unit
): EpoxyController = MvRxEpoxyController {
    if (view == null || isRemoving) return@MvRxEpoxyController
    withState(viewModel) { state ->
        buildModels(state)
    }
}

/**
 * Create a [MvRxEpoxyController] that builds models with the given callback.
 * When models are built the current state of the viewmodels will be provided.
 */
fun <A : MavericksViewModel<B>, B : MavericksState, C : MavericksViewModel<D>, D : MavericksState> MvRxLauncherBaseFragment.simpleController(
    viewModel1: A,
    viewModel2: C,
    buildModels: EpoxyController.(state1: B, state2: D) -> Unit
): EpoxyController = MvRxEpoxyController {
    if (view == null || isRemoving) return@MvRxEpoxyController
    withState(viewModel1, viewModel2) { state1, state2 ->
        buildModels(state1, state2)
    }
}

/**
 * Create a [MvRxEpoxyController] that builds models with the given callback.
 * When models are built the current state of the viewmodels will be provided.
 */
fun <A : MavericksViewModel<B>,
    B : MavericksState,
    C : MavericksViewModel<D>,
    D : MavericksState,
    E : MavericksViewModel<F>,
    F : MavericksState>
    MvRxLauncherBaseFragment.simpleController(
    viewModel1: A,
    viewModel2: C,
    viewModel3: E,
    buildModels: EpoxyController.(state1: B, state2: D, state3: F) -> Unit
): EpoxyController = MvRxEpoxyController {
    if (view == null || isRemoving) return@MvRxEpoxyController
    withState(viewModel1, viewModel2, viewModel3) { state1, state2, state3 ->
        buildModels(state1, state2, state3)
    }
}
