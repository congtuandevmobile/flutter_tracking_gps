package com.uprace.flutter_tracking_gps

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The acknowledgement that the foreground service really reached `startForeground`.
 * `startForegroundService()` only QUEUES the start: it returning proves nothing, so recording begins only
 * after [TrackingService] calls [acknowledge]. Pure (no Android types) so it is unit-testable.
 */
internal class ForegroundHandshake {
    @Volatile private var pending: CompletableDeferred<Unit>? = null

    /** Opens a new wait; call BEFORE asking the OS to start the service. */
    fun expect(): CompletableDeferred<Unit> = CompletableDeferred<Unit>().also { pending = it }

    /** The service is in the foreground. */
    fun acknowledge() {
        pending?.complete(Unit)
    }

    /** The service could not enter the foreground (the OS refused). */
    fun fail(error: Throwable) {
        pending?.completeExceptionally(error)
    }

    /** True when acknowledged within [timeoutMs]; false on timeout. Rethrows the failure from [fail]. */
    suspend fun await(wait: CompletableDeferred<Unit>, timeoutMs: Long): Boolean {
        val result = withTimeoutOrNull(timeoutMs) {
            wait.await()
            true
        }
        if (pending === wait) pending = null
        return result ?: false
    }
}
