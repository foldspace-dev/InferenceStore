package dev.mattramotar.inferencestore.testkit

import dev.mattramotar.inferencestore.core.InferenceStore
import dev.mattramotar.inferencestore.core.event.AttemptOutcome
import dev.mattramotar.inferencestore.core.journal.MemoryRouteJournal
import dev.mattramotar.inferencestore.core.model.InferenceKey
import dev.mattramotar.inferencestore.core.model.InferenceRequest
import dev.mattramotar.inferencestore.core.policy.Policies
import dev.mattramotar.inferencestore.core.policy.excluding
import dev.mattramotar.inferencestore.core.provider.ErrorCategory
import dev.mattramotar.inferencestore.core.provider.ProviderId
import dev.mattramotar.inferencestore.core.provider.ProviderKind
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A policy consuming a RouteJournal cooldown snapshot via `excluding` (OSS-26). */
class RouteJournalIntegrationTest {

    private val key = InferenceKey("notes.summary", "n1")

    @Test
    fun excludingCooledProvider_routesToAnother() = runTest {
        // Provider "a" failed repeatedly and is now cooling down.
        val journal = MemoryRouteJournal()
        repeat(3) { journal.record(ProviderId("a"), AttemptOutcome.Failed, ErrorCategory.TransientProviderError) }
        val cooled = journal.cooledDownProviders()
        assertTrue(ProviderId("a") in cooled)

        val a = fakeProvider("a", ProviderKind.Local) { complete("from-a") }
        val b = fakeProvider("b", ProviderKind.Local) { complete("from-b") }
        val store = InferenceStore.build {
            provider(a)
            provider(b)
            policy = Policies.preferLocalThenCloud().excluding(cooled)
        }

        val result = store.generate(InferenceRequest.text(key, "hi"))
        assertEquals("from-b", result.output)
        assertEquals("b", result.trace?.finalProvider)
        a.assertInvocations(0) // cooled-down provider was excluded from the route
    }

    @Test
    fun noCooldowns_routesNormally() = runTest {
        val journal = MemoryRouteJournal()
        journal.record(ProviderId("a"), AttemptOutcome.Succeeded)
        val cooled = journal.cooledDownProviders() // snapshot read before building the store
        val a = fakeProvider("a", ProviderKind.Local) { complete("from-a") }
        val store = InferenceStore.build {
            provider(a)
            policy = Policies.preferLocalThenCloud().excluding(cooled)
        }
        assertEquals("from-a", store.generate(InferenceRequest.text(key, "hi")).output)
    }
}
