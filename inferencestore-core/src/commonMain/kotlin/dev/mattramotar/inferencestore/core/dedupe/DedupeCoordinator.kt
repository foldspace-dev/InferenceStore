package dev.mattramotar.inferencestore.core.dedupe

import dev.mattramotar.inferencestore.core.event.InferenceEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory request deduplication fan-out (`threading-dispatchers.md`).
 *
 * Compatible concurrent callers of the same dedupe key share ONE upstream
 * provider execution. `stream()` collectors may join only before the first
 * content event (`Token`/`Partial`); `generate()` callers may join until the
 * terminal result. Sharing is built on explicit per-subscriber channels (not
 * `SharedFlow` replay), so a joiner deterministically receives the full
 * pre-content prelude plus all live events — regardless of how many lifecycle
 * events precede the first token.
 *
 * Cancellation is reference counted: cancelling one joined caller does not cancel
 * upstream while another remains; the last one cancels it (provider cleanup runs
 * once). Groups retire from the registry on their terminal event.
 */
internal class DedupeCoordinator(private val scope: CoroutineScope) {

    private val mutex = Mutex()
    private val groups = HashMap<String, Group<*>>()

    /** Join the in-flight group for [key] if it still accepts stream joins, else start a new shared execution. */
    fun <Output : Any> stream(key: String, upstream: Flow<InferenceEvent<Output>>): Flow<InferenceEvent<Output>> = flow {
        val group = mutex.withLock {
            @Suppress("UNCHECKED_CAST")
            val current = groups[key] as? Group<Output>
            if (current != null && current.streamJoinable) current else newGroup(key, upstream)
        }
        emitAll(group.subscribe())
    }

    /** Join the in-flight group for [key] until its terminal result, else start a new shared execution. */
    suspend fun <Output : Any> generate(key: String, upstream: Flow<InferenceEvent<Output>>): InferenceEvent<Output> {
        val group = mutex.withLock {
            @Suppress("UNCHECKED_CAST")
            (groups[key] as? Group<Output>) ?: newGroup(key, upstream)
        }
        return group.awaitTerminal()
    }

    // Called under [mutex]. Always (re)registers as the current group for the key.
    private fun <Output : Any> newGroup(key: String, upstream: Flow<InferenceEvent<Output>>): Group<Output> {
        val group = Group(upstream, scope) { retire(key, it) }
        groups[key] = group
        return group
    }

    private suspend fun retire(key: String, group: Group<*>) = mutex.withLock {
        if (groups[key] === group) groups.remove(key)
    }

    private class Group<Output : Any>(
        upstream: Flow<InferenceEvent<Output>>,
        scope: CoroutineScope,
        private val onTerminal: suspend (Group<Output>) -> Unit,
    ) {
        private val mutex = Mutex()
        private val prelude = mutableListOf<InferenceEvent<Output>>()
        private val subscribers = mutableSetOf<Channel<InferenceEvent<Output>>>()
        private var firstContent = false
        private var terminalEvent: InferenceEvent<Output>? = null
        private var refCount = 0
        private val terminal = CompletableDeferred<InferenceEvent<Output>>()

        /** Whether a new `stream()` collector may still join (before first content). */
        var streamJoinable: Boolean = true
            private set

        private val job: Job = scope.launch {
            try {
                upstream.collect { event -> broadcast(event) }
            } finally {
                // Upstream ended or was cancelled: close any remaining subscribers.
                mutex.withLock {
                    subscribers.forEach { it.close() }
                    subscribers.clear()
                }
            }
        }

        private suspend fun broadcast(event: InferenceEvent<Output>) {
            var terminalReached = false
            mutex.withLock {
                if ((event is InferenceEvent.Token || event is InferenceEvent.Partial<*>) && !firstContent) {
                    firstContent = true
                    streamJoinable = false
                }
                if (!firstContent) prelude += event // pre-content prelude (the content event itself is live-only)
                subscribers.forEach { it.trySend(event) }
                if (event is InferenceEvent.Done<*> || event is InferenceEvent.Failed) {
                    terminalEvent = event
                    streamJoinable = false
                    terminal.complete(event)
                    subscribers.forEach { it.close() }
                    subscribers.clear()
                    terminalReached = true
                }
            }
            if (terminalReached) onTerminal(this)
        }

        /** A stream subscriber: replays the buffered prelude, streams live, completes at the terminal. */
        fun subscribe(): Flow<InferenceEvent<Output>> = flow {
            val channel = Channel<InferenceEvent<Output>>(Channel.UNLIMITED)
            val live = mutex.withLock {
                prelude.forEach { channel.trySend(it) }
                val done = terminalEvent
                if (done != null) {
                    channel.trySend(done)
                    channel.close()
                    false
                } else {
                    subscribers += channel
                    refCount++
                    true
                }
            }
            try {
                for (event in channel) {
                    emit(event)
                    if (event is InferenceEvent.Done<*> || event is InferenceEvent.Failed) break
                }
            } finally {
                if (live) leave(channel)
            }
        }

        /** A generate() caller: awaits the terminal result (joins past first content). */
        suspend fun awaitTerminal(): InferenceEvent<Output> {
            mutex.withLock { refCount++ }
            try {
                return terminal.await()
            } finally {
                leave(null)
            }
        }

        private suspend fun leave(channel: Channel<InferenceEvent<Output>>?) {
            mutex.withLock {
                if (channel != null) subscribers.remove(channel)
                refCount--
                if (refCount == 0 && terminalEvent == null) job.cancel()
            }
        }
    }
}
