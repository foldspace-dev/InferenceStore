package dev.mattramotar.inferencestore.samples.notes

import dev.mattramotar.inferencestore.core.InferenceException
import dev.mattramotar.inferencestore.core.InferenceStore
import dev.mattramotar.inferencestore.core.policy.Policies
import dev.mattramotar.inferencestore.core.policy.PrivacyPolicy
import dev.mattramotar.inferencestore.core.provider.ProviderAvailability
import dev.mattramotar.inferencestore.core.provider.ProviderKind
import dev.mattramotar.inferencestore.core.provider.ProviderPrivacyBoundary
import dev.mattramotar.inferencestore.core.provider.UnavailableReason
import dev.mattramotar.inferencestore.testkit.fakeProvider
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The note-summarization sample's scenarios (OSS-31). */
class NoteSummarizerTest {

    private val note = "roadmap: ship caching, draft RFC, demo Friday"
    private val cloudBoundary = ProviderPrivacyBoundary.thirdPartyCloud("demo-cloud")

    @Test
    fun localUnavailable_fallsBackToCloud() = runTest {
        val local = fakeProvider("on-device", ProviderKind.Local) {
            availability = ProviderAvailability.Unavailable(UnavailableReason.ModelMissing)
        }
        val cloud = fakeProvider("cloud", ProviderKind.Cloud, cloudBoundary) { complete("cloud summary") }
        val store = InferenceStore.build {
            provider(local)
            provider(cloud)
            policy = Policies.preferLocalThenCloud()
        }
        val result = NoteSummarizer(store).summarize(note, PrivacyPolicy.publicData())
        assertEquals("cloud summary", result.output)
        assertEquals("cloud", result.trace?.finalProvider)
        assertEquals(1, cloud.invocations)
    }

    @Test
    fun privateLocalOnly_servesLocally_withoutCloud() = runTest {
        val local = fakeProvider("on-device", ProviderKind.Local) { complete("local summary") }
        val cloud = fakeProvider("cloud", ProviderKind.Cloud, cloudBoundary) { complete("should never run") }
        val store = InferenceStore.build {
            provider(local)
            provider(cloud)
            policy = Policies.preferLocalThenCloud()
        }
        // The realistic private scenario: local serves and cloud is refused by the gate.
        val result = NoteSummarizer(store).summarize(note, PrivacyPolicy.Default)
        assertEquals("local summary", result.output)
        assertEquals("on-device", result.trace?.finalProvider)
        assertEquals(0, cloud.invocations)
    }

    @Test
    fun privacyGate_blocksCloud_evenWhenLocalUnavailable() = runTest {
        // Local is the ONLY non-cloud option and it's unavailable. If the privacy gate
        // failed to refuse cloud, routing would fall back to it (invocations == 1). So a
        // zero count here proves gate-level denial — the request fails rather than leaking.
        val local = fakeProvider("on-device", ProviderKind.Local) {
            availability = ProviderAvailability.Unavailable(UnavailableReason.ModelMissing)
        }
        val cloud = fakeProvider("cloud", ProviderKind.Cloud, cloudBoundary) { complete("should never run") }
        val store = InferenceStore.build {
            provider(local)
            provider(cloud)
            policy = Policies.preferLocalThenCloud()
        }
        assertFailsWith<InferenceException> { NoteSummarizer(store).summarize(note, PrivacyPolicy.Default) }
        assertEquals(0, cloud.invocations)
    }

    @Test
    fun liteRtLmDemoRuntime_producesOnDeviceSummary() = runTest {
        val store = InferenceStore.single(liteRtLmDemoProvider("/models/demo.task"))
        val result = NoteSummarizer(store).summarize(note)
        assertTrue(result.output.contains("demo runtime"), "expected on-device demo summary, was: ${result.output}")
    }

    @Test
    fun openAiCompatibleMock_producesCloudSummary() = runTest {
        val store = InferenceStore.single(openAiCompatibleDemoProvider("Cloud summary: roadmap on track."))
        val result = NoteSummarizer(store).summarize(note, PrivacyPolicy.publicData())
        assertEquals("Cloud summary: roadmap on track.", result.output)
    }

    @Test
    fun blankOutput_failsTheNonBlankValidator() = runTest {
        val local = fakeProvider("on-device", ProviderKind.Local) { complete("   ") }
        val store = InferenceStore.single(local)
        assertFailsWith<InferenceException> { NoteSummarizer(store).summarize(note) }
    }
}
