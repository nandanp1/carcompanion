package com.carcompanion.app.navigation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Briefly lets Maps replace its notification without publishing a false route end/start pair. */
internal class NavigationRemovalDebouncer(private val scope: CoroutineScope, private val reconcile: () -> Unit) {
    private var pending: Job? = null
    val isPending: Boolean get() = pending != null

    fun removed() {
        if (isPending) return // Further removals cannot extend the bounded grace period.
        pending = scope.launch {
            delay(250)
            pending = null
            reconcile()
        }
    }

    fun cancel() { pending?.cancel(); pending = null }
}
