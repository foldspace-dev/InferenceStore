package dev.mattramotar.inferencestore.testkit

import dev.mattramotar.inferencestore.core.InferenceStore
import dev.mattramotar.inferencestore.core.cache.InferenceArtifact
import dev.mattramotar.inferencestore.core.cache.InferenceCache
import dev.mattramotar.inferencestore.core.cache.InferenceFingerprint
import dev.mattramotar.inferencestore.core.model.InferenceKey
import dev.mattramotar.inferencestore.core.model.InferenceRequest
import dev.mattramotar.inferencestore.core.model.OutputSpec
import dev.mattramotar.inferencestore.core.policy.CacheAccess
import dev.mattramotar.inferencestore.core.policy.CachePolicy
import dev.mattramotar.inferencestore.core.policy.PersistencePermission
import dev.mattramotar.inferencestore.core.policy.PrivacyPolicy
import dev.mattramotar.inferencestore.core.provider.ErrorCategory
import dev.mattramotar.inferencestore.core.provider.ProviderKind
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Cache read-before-execution and write-after-success wiring (OSS-25). */
class CacheTest {

    private val key = InferenceKey("notes.summary", "n1")

    /** A minimal in-memory [InferenceCache] that records reads/writes (real impl is OSS-30). */
    private class RecordingCache : InferenceCache {
        val store: MutableMap<InferenceFingerprint, InferenceArtifact<*>> = mutableMapOf()
        var reads: Int = 0
        var writes: Int = 0

        @Suppress("UNCHECKED_CAST")
        override suspend fun <Output : Any> read(
            fingerprint: InferenceFingerprint,
            output: OutputSpec<Output>,
        ): InferenceArtifact<Output>? {
            reads++
            return store[fingerprint] as InferenceArtifact<Output>?
        }

        override suspend fun <Output : Any> write(artifact: InferenceArtifact<Output>) {
            writes++
            store[artifact.fingerprint] = artifact
        }

        override suspend fun clear(key: InferenceKey) {
            store.keys.retainAll { it.key != key }
        }

        override suspend fun clearAll() {
            store.clear()
        }
    }

    /** A cache that throws on read and/or write — proves caching is best-effort. */
    private class ThrowingCache(val onRead: Boolean, val onWrite: Boolean) : InferenceCache {
        override suspend fun <Output : Any> read(
            fingerprint: InferenceFingerprint,
            output: OutputSpec<Output>,
        ): InferenceArtifact<Output>? {
            if (onRead) error("read boom")
            return null
        }

        override suspend fun <Output : Any> write(artifact: InferenceArtifact<Output>) {
            if (onWrite) error("write boom")
        }

        override suspend fun clear(key: InferenceKey) {}
        override suspend fun clearAll() {}
    }

    private val persistOutput = PrivacyPolicy(persistence = PersistencePermission(persistOutput = true))

    @Test
    fun write_thenRead_roundTripsAndShortCircuitsProvider() = runTest {
        val cache = RecordingCache()
        val request = InferenceRequest.text(key, "hi", privacy = persistOutput)
            .copy(cache = CachePolicy.readWrite())

        // First store computes the result and writes it to the cache.
        val r1 = InferenceStore.build {
            provider(fakeProvider("p", ProviderKind.Local) { complete("fresh") })
            this.cache = cache
        }.generate(request)
        assertEquals("fresh", r1.output)
        assertEquals(1, cache.writes)
        assertFalse(r1.trace?.servedFromCache == true)

        // A second store whose only provider *fails* still returns the cached result,
        // proving the cache read short-circuits provider execution.
        val r2 = InferenceStore.build {
            provider(fakeProvider("p", ProviderKind.Local) { fail(ErrorCategory.PermanentProviderError) })
            this.cache = cache
        }.generate(request)
        assertEquals("fresh", r2.output)
        assertTrue(r2.trace?.servedFromCache == true)
        assertEquals("p", r2.trace?.finalProvider)
    }

    @Test
    fun write_blockedByPrivacy_whenPersistOutputDenied() = runTest {
        val cache = RecordingCache()
        // Cache policy allows writes, but the default privacy policy forbids persisting output.
        val request = InferenceRequest.text(key, "hi").copy(cache = CachePolicy.readWrite())
        InferenceStore.build {
            provider(fakeProvider("p", ProviderKind.Local) { complete("fresh") })
            this.cache = cache
        }.generate(request)
        assertEquals(0, cache.writes)
    }

    @Test
    fun write_blockedByCachePolicy_whenWriteDenied() = runTest {
        val cache = RecordingCache()
        // Privacy allows output persistence, but the default cache policy denies writes.
        val request = InferenceRequest.text(key, "hi", privacy = persistOutput)
        InferenceStore.build {
            provider(fakeProvider("p", ProviderKind.Local) { complete("fresh") })
            this.cache = cache
        }.generate(request)
        assertEquals(0, cache.writes)
    }

    @Test
    fun read_notConsulted_whenReadDenied() = runTest {
        val cache = RecordingCache()
        val request = InferenceRequest.text(key, "hi")
            .copy(cache = CachePolicy(read = CacheAccess.Deny))
        val result = InferenceStore.build {
            provider(fakeProvider("p", ProviderKind.Local) { complete("fresh") })
            this.cache = cache
        }.generate(request)
        assertEquals("fresh", result.output)
        assertEquals(0, cache.reads)
    }

    @Test
    fun throwingCacheRead_fallsThroughToProvider() = runTest {
        val result = InferenceStore.build {
            provider(fakeProvider("p", ProviderKind.Local) { complete("fresh") })
            this.cache = ThrowingCache(onRead = true, onWrite = false)
        }.generate(InferenceRequest.text(key, "hi").copy(cache = CachePolicy.readWrite()))
        assertEquals("fresh", result.output)
        assertFalse(result.trace?.servedFromCache == true)
    }

    @Test
    fun throwingCacheWrite_doesNotFailSuccessfulRequest() = runTest {
        val result = InferenceStore.build {
            provider(fakeProvider("p", ProviderKind.Local) { complete("fresh") })
            this.cache = ThrowingCache(onRead = false, onWrite = true)
        }.generate(InferenceRequest.text(key, "hi", privacy = persistOutput).copy(cache = CachePolicy.readWrite()))
        assertEquals("fresh", result.output)
    }

    @Test
    fun noCacheConfigured_executesNormally() = runTest {
        val result = InferenceStore.build {
            provider(fakeProvider("p", ProviderKind.Local) { complete("fresh") })
        }.generate(InferenceRequest.text(key, "hi").copy(cache = CachePolicy.readWrite()))
        assertEquals("fresh", result.output)
    }
}
