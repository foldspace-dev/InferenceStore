package dev.mattramotar.inferencestore.testkit

import dev.mattramotar.inferencestore.core.InferenceStore
import dev.mattramotar.inferencestore.core.event.FallbackReason
import dev.mattramotar.inferencestore.core.event.InferenceEvent
import dev.mattramotar.inferencestore.core.model.InferenceKey
import dev.mattramotar.inferencestore.core.model.InferenceRequest
import dev.mattramotar.inferencestore.core.policy.InferenceRoute
import dev.mattramotar.inferencestore.core.policy.Policies
import dev.mattramotar.inferencestore.core.policy.ProviderCandidate
import dev.mattramotar.inferencestore.core.provider.Capability
import dev.mattramotar.inferencestore.core.provider.ErrorCategory
import dev.mattramotar.inferencestore.core.provider.ProviderAvailability
import dev.mattramotar.inferencestore.core.provider.ProviderKind
import dev.mattramotar.inferencestore.core.provider.UnavailableReason
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Exercises the five built-in policy presets and the routing/fallback engine (OSS-13). */
class RoutingTest {

    private val key = InferenceKey("notes.summary", "n1")
    private val request get() = InferenceRequest.text(key, "hi")

    // --- Policy preset ordering is deterministic (no providers invoked). ---

    @Test
    fun presets_orderCandidatesDeterministically() {
        val local = fakeProvider("local", ProviderKind.Local) {}
        val cloud = fakeProvider("cloud", ProviderKind.Cloud) {}
        // Candidates intentionally given cloud-first to prove the policy re-orders.
        val candidates = listOf(
            ProviderCandidate(cloud, available = true, supported = true),
            ProviderCandidate(local, available = true, supported = true),
        )

        assertEquals(listOf("local"), Policies.localOnly().selectRoute(candidates).ids())
        assertEquals(listOf("cloud"), Policies.cloudOnly().selectRoute(candidates).ids())
        assertEquals(listOf("local", "cloud"), Policies.preferLocalThenCloud().selectRoute(candidates).ids())
        assertEquals(listOf("cloud", "local"), Policies.preferCloudThenLocal().selectRoute(candidates).ids())
        assertEquals(listOf("local", "cloud"), Policies.validateLocalThenCloudRepair().selectRoute(candidates).ids())
    }

    @Test
    fun presets_dropUnusableCandidates() {
        val local = fakeProvider("local", ProviderKind.Local) {}
        val cloud = fakeProvider("cloud", ProviderKind.Cloud) {}
        val candidates = listOf(
            ProviderCandidate(local, available = false, supported = true),
            ProviderCandidate(cloud, available = true, supported = true),
        )
        assertEquals(listOf("cloud"), Policies.preferLocalThenCloud().selectRoute(candidates).ids())
        assertEquals(emptyList(), Policies.localOnly().selectRoute(candidates).ids())
    }

    // --- Engine routing/fallback ---

    @Test
    fun preferLocalThenCloud_fallsBackToCloud_onLocalFailure() = runTest {
        val local = fakeProvider("local", ProviderKind.Local) { fail(ErrorCategory.TransientProviderError, "boom") }
        val cloud = fakeProvider("cloud", ProviderKind.Cloud) {
            tokens("ok")
            complete("ok")
        }
        val store = InferenceStore.build {
            provider(local)
            provider(cloud)
            policy = Policies.preferLocalThenCloud()
        }

        val events = store.stream(request).toList()
        assertEvents(events) {
            started()
            providerAttemptStarted("local")
            providerAttemptCompleted() // local failed
            fallbackStarted("cloud")
            providerAttemptStarted("cloud")
            token("ok")
            providerAttemptCompleted()
            done()
        }

        val result = store.generate(request)
        assertEquals("ok", result.output)
        assertRoute(result.trace) {
            attempted("local")
            fellBackTo("cloud", FallbackReason.TransientError)
            completedWith("cloud")
        }
        local.assertInvocations(2)
        cloud.assertInvocations(2)
    }

    @Test
    fun localOnly_neverAttemptsCloud_evenWhenLocalFails() = runTest {
        val local = fakeProvider("local", ProviderKind.Local) { fail(ErrorCategory.TransientProviderError) }
        val cloud = fakeProvider("cloud", ProviderKind.Cloud) { complete("cloud") }
        val store = InferenceStore.build {
            provider(local)
            provider(cloud)
            policy = Policies.localOnly()
        }

        val last = store.stream(request).toList().last()
        assertTrue(last is InferenceEvent.Failed)
        assertRoute(last.trace) {
            attempted("local")
            didNotAttempt("cloud")
            rejected("cloud", FallbackReason.PolicyViolation)
        }
        cloud.assertInvocations(0)
    }

    @Test
    fun cloudOnly_routesToCloud_skipsLocal() = runTest {
        val local = fakeProvider("local", ProviderKind.Local) { complete("local") }
        val cloud = fakeProvider("cloud", ProviderKind.Cloud) { complete("cloud") }
        val store = InferenceStore.build {
            provider(local)
            provider(cloud)
            policy = Policies.cloudOnly()
        }

        val result = store.generate(request)
        assertEquals("cloud", result.output)
        assertRoute(result.trace) {
            completedWith("cloud")
            didNotAttempt("local")
            rejected("local", FallbackReason.PolicyViolation)
        }
        local.assertInvocations(0)
    }

    @Test
    fun preferCloudThenLocal_fallsBackToLocal_onCloudFailure() = runTest {
        val cloud = fakeProvider("cloud", ProviderKind.Cloud) { fail(ErrorCategory.RateLimited, "429") }
        val local = fakeProvider("local", ProviderKind.Local) { complete("local") }
        val store = InferenceStore.build {
            provider(local)
            provider(cloud)
            policy = Policies.preferCloudThenLocal()
        }

        val result = store.generate(request)
        assertEquals("local", result.output)
        assertRoute(result.trace) {
            attempted("cloud")
            fellBackTo("local", FallbackReason.RateLimited)
            completedWith("local")
        }
    }

    @Test
    fun unavailableLocal_isRejected_andCloudHandlesDirectly() = runTest {
        val local = fakeProvider("local", ProviderKind.Local) {
            availability = ProviderAvailability.Unavailable(UnavailableReason.ModelMissing)
            complete("local")
        }
        val cloud = fakeProvider("cloud", ProviderKind.Cloud) { complete("cloud") }
        val store = InferenceStore.build {
            provider(local)
            provider(cloud)
            policy = Policies.preferLocalThenCloud()
        }

        val result = store.generate(request)
        assertEquals("cloud", result.output)
        assertRoute(result.trace) {
            completedWith("cloud")
            didNotAttempt("local")
            rejected("local", FallbackReason.ProviderUnavailable)
        }
        local.assertInvocations(0) // never streamed — only probed for availability
    }

    @Test
    fun incapableLocal_isRejected_forCapabilityUnsupported() = runTest {
        val local = fakeProvider("local", ProviderKind.Local) {
            supports(Capability.Embeddings) // a text request requires TextGeneration
            complete("local")
        }
        val cloud = fakeProvider("cloud", ProviderKind.Cloud) { complete("cloud") }
        val store = InferenceStore.build {
            provider(local)
            provider(cloud)
            policy = Policies.preferLocalThenCloud()
        }

        val result = store.generate(request)
        assertEquals("cloud", result.output)
        assertRoute(result.trace) {
            completedWith("cloud")
            rejected("local", FallbackReason.CapabilityUnsupported)
        }
        local.assertInvocations(0)
    }

    @Test
    fun noUsableProvider_failsWithProviderUnavailable() = runTest {
        val local = fakeProvider("local", ProviderKind.Local) {
            availability = ProviderAvailability.Unavailable(UnavailableReason.Disabled)
            complete("local")
        }
        val store = InferenceStore.build {
            provider(local)
            policy = Policies.preferLocalThenCloud()
        }

        val last = store.stream(request).toList().last()
        assertTrue(last is InferenceEvent.Failed)
        assertEquals(ErrorCategory.ProviderUnavailable, last.error.category)
        assertRoute(last.trace) {
            didNotAttempt("local")
            rejected("local", FallbackReason.ProviderUnavailable)
        }
        local.assertInvocations(0)
    }

    @Test
    fun requestPolicyOverridesStoreDefault() = runTest {
        val local = fakeProvider("local", ProviderKind.Local) { complete("local") }
        val cloud = fakeProvider("cloud", ProviderKind.Cloud) { complete("cloud") }
        val store = InferenceStore.build {
            provider(local)
            provider(cloud)
            policy = Policies.preferLocalThenCloud() // store default would pick local
        }

        // The request overrides the store policy to cloud-only.
        val result = store.generate(InferenceRequest.text(key, "hi", policy = Policies.cloudOnly()))
        assertEquals("cloud", result.output)
        assertRoute(result.trace) {
            completedWith("cloud")
            didNotAttempt("local")
        }
    }
}

private fun InferenceRoute.ids(): List<String> = orderedProviders.map { it.id.value }
