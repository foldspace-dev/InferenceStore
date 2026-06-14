package dev.mattramotar.inferencestore.core.dedupe

import dev.mattramotar.inferencestore.core.event.InferenceEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory request deduplication fan-out (`threading-dispatchers.md`).
 *
 * Compatible concurrent collectors of the same dedupe key share ONE upstream
 * provider execution until the first content event (`Token`/`Partial`); after
 * that the group stops accepting joins and later requests start their own call.
 * Ref-counted upstream cancellation and single provider cleanup are delegated to
 * `shareIn(SharingStarted.WhileSubscribed)`: cancelling one joined collector does
 * not cancel upstream while another remains; the last one cancels it.
 */
internal class DedupeCoordinator(private val scope: CoroutineScope) {

    private val mutex = Mutex()
    private val groups = HashMap<String, Group<*>>()

    /**
     * Returns a flow that joins the in-flight group for [key] if one is still
     * accepting joins, otherwise starts a new shared execution over [upstream].
     */
    fun <Output : Any> deduped(key: String, upstream: Flow<InferenceEvent<Output>>): Flow<InferenceEvent<Output>> = flow {
        val group = mutex.withLock {
            @Suppress("UNCHECKED_CAST")
            val existing = groups[key] as? Group<Output>
            if (existing != null && existing.joinable.value) {
                existing
            } else {
                Group(key, upstream).also { groups[key] = it }
            }
        }
        emitAll(group.flow)
    }

    private suspend fun release(key: String, group: Group<*>) = mutex.withLock {
        if (groups[key] === group) groups.remove(key)
    }

    private inner class Group<Output : Any>(
        private val key: String,
        upstream: Flow<InferenceEvent<Output>>,
    ) {
        // True until the first content event closes the join window.
        val joinable: MutableStateFlow<Boolean> = MutableStateFlow(true)

        private val shared = upstream
            .onEach { event ->
                if (joinable.value && (event is InferenceEvent.Token || event is InferenceEvent.Partial<*>)) {
                    joinable.value = false
                    release(key, this) // later requests start their own call
                }
            }
            .shareIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 0, replayExpirationMillis = 0), replay = REPLAY)

        // A subscriber replays the buffered pre-content events, streams live, and
        // completes at the terminal event (SharedFlow is otherwise endless).
        val flow: Flow<InferenceEvent<Output>> = shared.transformWhile { event ->
            emit(event)
            event !is InferenceEvent.Done<*> && event !is InferenceEvent.Failed
        }
    }

    private companion object {
        // Covers the pre-content events (Started, ProviderAttemptStarted, and any
        // FallbackStarted/RetryScheduled) a join-before-content collector must replay.
        const val REPLAY = 16
    }
}
