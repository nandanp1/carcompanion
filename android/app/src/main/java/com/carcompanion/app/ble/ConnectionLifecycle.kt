package com.carcompanion.app.ble

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes replacement sessions including cancellation cleanup, even across rapid replacements. */
class ConnectionLifecycle(private val scope: CoroutineScope) {
    private val owner = Mutex()
    private var runner: Job? = null
    val isActive: Boolean get() = synchronized(this) { runner?.isActive == true }

    @Synchronized fun replace(session: suspend CoroutineScope.() -> Unit) {
        runner?.cancel()
        runner = scope.launch { owner.withLock { coroutineScope { session() } } }
    }
    @Synchronized fun cancel() { runner?.cancel() }
}
