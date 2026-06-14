package dev.mattramotar.inferencestore.core.cache

import dev.mattramotar.inferencestore.core.model.InferenceKey
import dev.mattramotar.inferencestore.core.model.OutputSpec
import dev.mattramotar.inferencestore.core.policy.CacheAccess
import dev.mattramotar.inferencestore.core.policy.CachePolicy
import dev.mattramotar.inferencestore.core.provider.ProviderId
import dev.mattramotar.inferencestore.core.provider.ProviderKind
import dev.mattramotar.inferencestore.core.provider.ProviderMetadata
import dev.mattramotar.inferencestore.core.provider.ProviderPrivacyBoundary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes

/** [MemoryInferenceCache] in isolation: read/write, TTL, stale, clear, eviction (OSS-30). */
@OptIn(ExperimentalCoroutinesApi::class)
class MemoryInferenceCacheTest {

    private val readWrite = CachePolicy.readWrite()

    private fun fp(namespace: String, id: String) =
        InferenceFingerprint(InferenceKey(namespace, id), "h-$namespace-$id", null, "text", "Personal", null, null)

    private fun artifact(fingerprint: InferenceFingerprint, output: String = "v") = InferenceArtifact(
        fingerprint = fingerprint,
        output = output,
        rawText = output,
        provider = ProviderMetadata(ProviderId("p"), ProviderKind.Local, ProviderPrivacyBoundary.localDevice()),
        trace = null,
    )

    @Test
    fun writeThenRead_returnsArtifact() = runTest {
        val cache = MemoryInferenceCache(testTimeSource)
        val f = fp("k", "1")
        cache.write(artifact(f, "hello"), readWrite)
        assertEquals("hello", cache.read(f, OutputSpec.Text, readWrite)?.output)
    }

    @Test
    fun read_missingKey_returnsNull() = runTest {
        val cache = MemoryInferenceCache(testTimeSource)
        assertNull(cache.read(fp("k", "missing"), OutputSpec.Text, readWrite))
    }

    @Test
    fun ttlExpiry_returnsNullAndEvicts() = runTest {
        val cache = MemoryInferenceCache(testTimeSource)
        val ttl = CachePolicy.readWrite(ttl = 1.minutes)
        val f = fp("k", "1")
        cache.write(artifact(f), ttl)
        assertNotNull(cache.read(f, OutputSpec.Text, ttl)) // still fresh
        delay(2.minutes)
        assertNull(cache.read(f, OutputSpec.Text, ttl)) // expired -> miss
    }

    @Test
    fun staleWhileRevalidate_servesStaleOnceThenEvicts() = runTest {
        val cache = MemoryInferenceCache(testTimeSource)
        val swr = CachePolicy(
            read = CacheAccess.Allow,
            write = CacheAccess.Allow,
            ttl = 1.minutes,
            allowStaleWhileRevalidate = true,
        )
        val f = fp("k", "1")
        cache.write(artifact(f, "stale"), swr)
        delay(2.minutes)
        assertEquals("stale", cache.read(f, OutputSpec.Text, swr)?.output) // served stale once
        assertNull(cache.read(f, OutputSpec.Text, swr)) // then evicted -> next read re-fetches
    }

    @Test
    fun clearByKey_removesOnlyThatKey() = runTest {
        val cache = MemoryInferenceCache(testTimeSource)
        val a = fp("notes", "1")
        val b = fp("other", "2")
        cache.write(artifact(a), readWrite)
        cache.write(artifact(b), readWrite)
        cache.clear(InferenceKey("notes", "1"))
        assertNull(cache.read(a, OutputSpec.Text, readWrite))
        assertNotNull(cache.read(b, OutputSpec.Text, readWrite))
    }

    @Test
    fun clearAll_removesEverything() = runTest {
        val cache = MemoryInferenceCache(testTimeSource)
        val f = fp("k", "1")
        cache.write(artifact(f), readWrite)
        cache.clearAll()
        assertNull(cache.read(f, OutputSpec.Text, readWrite))
    }

    @Test
    fun maxEntries_evictsOldestOnOverflow() = runTest {
        val cache = MemoryInferenceCache(testTimeSource, maxEntries = 2)
        val f1 = fp("k", "1")
        val f2 = fp("k", "2")
        val f3 = fp("k", "3")
        cache.write(artifact(f1), readWrite)
        cache.write(artifact(f2), readWrite)
        cache.write(artifact(f3), readWrite) // overflows -> evicts f1
        assertNull(cache.read(f1, OutputSpec.Text, readWrite))
        assertNotNull(cache.read(f2, OutputSpec.Text, readWrite))
        assertNotNull(cache.read(f3, OutputSpec.Text, readWrite))
    }
}
