package dev.mattramotar.inferencestore.core.cache

import dev.mattramotar.inferencestore.core.model.InferenceKey
import dev.mattramotar.inferencestore.core.model.OutputSpec
import dev.mattramotar.inferencestore.core.policy.CachePolicy
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * A small in-memory [InferenceCache] for demos, tests, and repeated UI calls
 * (`storage-model.md`). Honors `CachePolicy.ttl` and `allowStaleWhileRevalidate`.
 *
 * TTL is tracked against [timeSource] (inject `TestScope.testTimeSource` in tests).
 * Expiry uses the cache's own monotonic clock — the artifact's epoch
 * [InferenceArtifact.createdAtMillis]/[InferenceArtifact.expiresAtMillis] are for
 * persistent stores and are not required here.
 *
 * Concurrency: a [Mutex] guards the map, so reads/writes are coroutine-safe. The
 * store is unbounded unless [maxEntries] is set, in which case the oldest entry is
 * evicted (insertion order) when the cap is exceeded — suitable for the bounded
 * call sets this cache targets, not as a general-purpose LRU.
 *
 * Privacy: the engine only calls [write] when the privacy policy permits persisting
 * output, so this cache stores whatever it is given without re-checking privacy.
 */
public class MemoryInferenceCache(
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val maxEntries: Int? = null,
) : InferenceCache {

    init {
        require(maxEntries == null || maxEntries > 0) { "maxEntries must be positive, was $maxEntries" }
    }

    private class Entry(val artifact: InferenceArtifact<*>, val expiresAt: TimeMark?)

    private val mutex = Mutex()
    // LinkedHashMap preserves insertion order for cap eviction.
    private val entries = LinkedHashMap<InferenceFingerprint, Entry>()

    override suspend fun <Output : Any> read(
        fingerprint: InferenceFingerprint,
        output: OutputSpec<Output>,
        policy: CachePolicy,
    ): InferenceArtifact<Output>? = mutex.withLock {
        val entry = entries[fingerprint] ?: return@withLock null
        if (entry.expiresAt?.hasPassedNow() == true && !policy.allowStaleWhileRevalidate) {
            // Expired and the caller does not tolerate stale → evict and miss, so the
            // engine re-fetches from a provider.
            entries.remove(fingerprint)
            return@withLock null
        }
        @Suppress("UNCHECKED_CAST")
        entry.artifact as InferenceArtifact<Output>
    }

    override suspend fun <Output : Any> write(artifact: InferenceArtifact<Output>, policy: CachePolicy) {
        mutex.withLock {
            val expiresAt = policy.ttl?.let { timeSource.markNow() + it }
            // Remove-then-put so a re-written key moves to the newest insertion slot.
            entries.remove(artifact.fingerprint)
            entries[artifact.fingerprint] = Entry(artifact, expiresAt)
            if (maxEntries != null && entries.size > maxEntries) {
                val oldest = entries.keys.iterator().next()
                entries.remove(oldest)
            }
        }
    }

    override suspend fun clear(key: InferenceKey) {
        mutex.withLock { entries.keys.retainAll { it.key != key } }
    }

    override suspend fun clearAll() {
        mutex.withLock { entries.clear() }
    }
}
