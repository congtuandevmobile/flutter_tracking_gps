package io.github.congtuandevmobile.flutter_tracking_gps

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The acknowledgement that the foreground service really reached `startForeground`.
 * `startForegroundService()` only QUEUES the start: it returning proves nothing, so recording begins only
 * after [TrackingService] calls [acknowledge]. Pure (no Android types) so it is unit-testable.
 *
 * Requests may overlap (two commands racing): every [expect] owns its own waiter. An acknowledgement or a
 * failure completes EVERY waiter that is open (the service is in the foreground for all of them), and a new
 * request never abandons an older one. Whoever cleans up after a timeout must check [isCurrent] first, so
 * a stale request can never stop a service that now belongs to a newer one.
 */
internal class ForegroundHandshake {
    private val lock = Any()
    private val waiters = ArrayList<CompletableDeferred<Unit>>()
    private var latest: CompletableDeferred<Unit>? = null

    /** Opens a new wait; call BEFORE asking the OS to start the service. */
    fun expect(): CompletableDeferred<Unit> = CompletableDeferred<Unit>().also {
        synchronized(lock) {
            waiters.add(it)
            latest = it
        }
    }

    /** The service is in the foreground: every open request is satisfied. */
    fun acknowledge() {
        val open = drain()
        open.forEach { it.complete(Unit) }
    }

    /** The service could not enter the foreground (the OS refused): every open request fails. */
    fun fail(error: Throwable) {
        val open = drain()
        open.forEach { it.completeExceptionally(error) }
    }

    /** True when [wait] is the newest request: only then may its owner stop the service on timeout. */
    fun isCurrent(wait: CompletableDeferred<Unit>): Boolean = synchronized(lock) { latest === wait }

    /** True when acknowledged within [timeoutMs]; false on timeout. Rethrows the failure from [fail]. */
    suspend fun await(wait: CompletableDeferred<Unit>, timeoutMs: Long): Boolean {
        val result = withTimeoutOrNull(timeoutMs) {
            wait.await()
            true
        }
        synchronized(lock) {
            waiters.remove(wait)
        }
        return result ?: false
    }

    private fun drain(): List<CompletableDeferred<Unit>> = synchronized(lock) {
        val open = ArrayList(waiters)
        waiters.clear()
        open
    }
}
