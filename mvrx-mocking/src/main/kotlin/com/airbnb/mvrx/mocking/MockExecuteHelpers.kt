package com.airbnb.mvrx.mocking

import com.airbnb.mvrx.MavericksViewModel
import com.airbnb.mvrx.MavericksState

internal fun <S : MavericksState> MavericksViewModel<S>.reportExecuteCallToInteractionTest() {
    // TODO (eli_hart 2019-08-09): Report this to the interaction management system
    // For now we'll just print this out
    println("subscribeAndSetState mocked: ${getCallStack()}")
}

/**
 * Returns all method calls in the current stack trace that belong to the current ViewModel class implementation, excluding
 * any super class methods.
 *
 * These calls are joined in a string - "MyViewModel#fooFunction -> MyViewModel#barFunction"
 */
private fun <S : MavericksState> MavericksViewModel<S>.getCallStack(): String {
    return Thread.currentThread().stackTrace
        .filter { traceElement -> this::class.java.simpleName in traceElement.className }
        .asReversed()
        .joinToString(" -> ") { traceElement ->
            // Shorten to simple name for easier reading - package name shouldn't be needed since they will have other context
            val simpleViewModelName = traceElement.className.substringAfterLast(".")
            "$simpleViewModelName#${traceElement.methodName}"
        }
}
