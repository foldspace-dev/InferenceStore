package dev.mattramotar.inferencestore.testkit

import dev.mattramotar.inferencestore.core.InferenceStore
import dev.mattramotar.inferencestore.core.cache.MemoryInferenceCache
import dev.mattramotar.inferencestore.core.model.InferenceKey
import dev.mattramotar.inferencestore.core.model.InferenceRequest
import dev.mattramotar.inferencestore.core.policy.CacheAccess
import dev.mattramotar.inferencestore.core.policy.CachePolicy
import dev.mattramotar.inferencestore.core.policy.PersistencePermission
import dev.mattramotar.inferencestore.core.policy.PrivacyPolicy
import dev.mattramotar.inferencestore.core.provider.ProviderKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** MemoryInferenceCache driven end-to-end through the engine (OSS-30). */
@OptIn(ExperimentalCoroutinesApi::class)
class MemoryCacheTest {

    private val key = InferenceKey("notes.summary", "n1")
    private val persistOutput = PrivacyPolicy(persistence = PersistencePermission(persistOutput = true))

    private fun req(cache: CachePolicy, privacy: PrivacyPolicy = persistOutput) =
        InferenceRequest.text(key, "hi", privacy = privacy).copy(cache = cache)

    @Test
    fun cacheHit_returnsWithoutProviderCall() = runTest {
        val cache = MemoryInferenceCache(testTimeSource)
        val provider = fakeProvider("p", ProviderKind.Local) { complete("ok") }
        val store = InferenceStore.build {
            provider(provider)
            this.cache = cache
        }
        val r1 = store.generate(req(CachePolicy.readWrite()))
        val r2 = store.generate(req(CachePolicy.readWrite()))
        assertEquals("ok", r1.output)
        assertEquals("ok", r2.output)
        assertTrue(r2.trace?.servedFromCache == true)
        provider.assertInvocations(1) // second call served from cache
    }

    @Test
    fun expiredArtifact_triggersProviderCall() = runTest {
        val cache = MemoryInferenceCache(testTimeSource)
        val provider = fakeProvider("p", ProviderKind.Local) { complete("ok") }
        val store = InferenceStore.build {
            provider(provider)
            this.cache = cache
        }
        store.generate(req(CachePolicy.readWrite(ttl = 1.minutes)))
        provider.assertInvocations(1)
        delay(2.minutes) // advance the cache's clock past the TTL
        store.generate(req(CachePolicy.readWrite(ttl = 1.minutes)))
        provider.assertInvocations(2) // expired -> re-fetched
    }

    @Test
    fun staleWhileRevalidate_servesStaleAfterExpiry() = runTest {
        val cache = MemoryInferenceCache(testTimeSource)
        val provider = fakeProvider("p", ProviderKind.Local) { complete("ok") }
        val store = InferenceStore.build {
            provider(provider)
            this.cache = cache
        }
        val swr = CachePolicy(
            read = CacheAccess.Allow,
            write = CacheAccess.Allow,
            ttl = 1.minutes,
            allowStaleWhileRevalidate = true,
        )
        store.generate(req(swr))
        delay(2.minutes)
        val r = store.generate(req(swr))
        assertEquals("ok", r.output)
        assertTrue(r.trace?.servedFromCache == true)
        provider.assertInvocations(1) // stale served, no re-fetch
    }

    @Test
    fun clearByKey_forcesReFetch() = runTest {
        val cache = MemoryInferenceCache(testTimeSource)
        val provider = fakeProvider("p", ProviderKind.Local) { complete("ok") }
        val store = InferenceStore.build {
            provider(provider)
            this.cache = cache
        }
        store.generate(req(CachePolicy.readWrite()))
        cache.clear(key)
        store.generate(req(CachePolicy.readWrite()))
        provider.assertInvocations(2)
    }

    @Test
    fun clearAll_forcesReFetch() = runTest {
        val cache = MemoryInferenceCache(testTimeSource)
        val provider = fakeProvider("p", ProviderKind.Local) { complete("ok") }
        val store = InferenceStore.build {
            provider(provider)
            this.cache = cache
        }
        store.generate(req(CachePolicy.readWrite()))
        cache.clearAll()
        store.generate(req(CachePolicy.readWrite()))
        provider.assertInvocations(2)
    }

    @Test
    fun privacyNoWrite_doesNotCache() = runTest {
        val cache = MemoryInferenceCache(testTimeSource)
        val provider = fakeProvider("p", ProviderKind.Local) { complete("ok") }
        val store = InferenceStore.build {
            provider(provider)
            this.cache = cache
        }
        // Default privacy forbids persisting output, so nothing is cached.
        store.generate(req(CachePolicy.readWrite(), privacy = PrivacyPolicy.Default))
        store.generate(req(CachePolicy.readWrite(), privacy = PrivacyPolicy.Default))
        provider.assertInvocations(2)
    }
}
