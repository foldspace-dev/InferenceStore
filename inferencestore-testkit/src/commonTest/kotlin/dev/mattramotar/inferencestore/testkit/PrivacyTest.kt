package dev.mattramotar.inferencestore.testkit

import dev.mattramotar.inferencestore.core.InferenceStore
import dev.mattramotar.inferencestore.core.event.FallbackReason
import dev.mattramotar.inferencestore.core.event.FinalStatus
import dev.mattramotar.inferencestore.core.event.InferenceEvent
import dev.mattramotar.inferencestore.core.model.InferenceKey
import dev.mattramotar.inferencestore.core.model.InferenceRequest
import dev.mattramotar.inferencestore.core.policy.CloudPermission
import dev.mattramotar.inferencestore.core.policy.InferencePolicy
import dev.mattramotar.inferencestore.core.policy.InferenceRoute
import dev.mattramotar.inferencestore.core.policy.Policies
import dev.mattramotar.inferencestore.core.policy.PolicyViolation
import dev.mattramotar.inferencestore.core.policy.PrivacyClass
import dev.mattramotar.inferencestore.core.policy.PrivacyDecision
import dev.mattramotar.inferencestore.core.policy.PrivacyPolicy
import dev.mattramotar.inferencestore.core.policy.allowsProvider
import dev.mattramotar.inferencestore.core.provider.ProviderAvailability
import dev.mattramotar.inferencestore.core.provider.ProviderId
import dev.mattramotar.inferencestore.core.provider.ProviderKind
import dev.mattramotar.inferencestore.core.provider.ProviderPrivacyBoundary
import dev.mattramotar.inferencestore.core.provider.ProviderPrivacyBoundaryId
import dev.mattramotar.inferencestore.core.provider.UnavailableReason
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Privacy model + enforcement gate (OSS-15, `privacy-model.md`). */
class PrivacyTest {

    private val key = InferenceKey("notes.summary", "n1")
    private fun req(privacy: PrivacyPolicy) = InferenceRequest.text(key, "hi", privacy = privacy)

    private fun cloud(id: String = "cloud", vendor: String = "openai") =
        fakeProvider(id, ProviderKind.Cloud, ProviderPrivacyBoundary.thirdPartyCloud(vendor)) { complete("from-$id") }

    private fun local(id: String = "local") =
        fakeProvider(id, ProviderKind.Local) { complete("from-$id") }

    // --- Enforcement through the engine ---

    @Test
    fun localOnly_neverInvokesCloud_evenWithCloudOnlyPolicy() = runTest {
        val c = cloud()
        val store = InferenceStore.build {
            provider(c)
            policy = Policies.cloudOnly() // policy WANTS cloud; privacy must still win
        }

        val last = store.stream(req(PrivacyPolicy.localOnly())).toList().last()
        assertTrue(last is InferenceEvent.Failed)
        assertEquals(FinalStatus.PrivacyDenied, last.trace?.finalStatus)
        assertRoute(last.trace) {
            didNotAttempt("cloud")
            rejected("cloud", FallbackReason.PolicyViolation)
        }
        c.assertInvocations(0)
    }

    @Test
    fun personalDefault_deniesCloud_butUsesLocal() = runTest {
        val l = local()
        val c = cloud()
        val store = InferenceStore.build {
            provider(l)
            provider(c)
            policy = Policies.preferLocalThenCloud()
        }

        val result = store.generate(req(PrivacyPolicy.Default)) // Personal, cloud denied
        assertEquals("from-local", result.output)
        assertRoute(result.trace) {
            completedWith("local")
            didNotAttempt("cloud")
            rejected("cloud", FallbackReason.PolicyViolation)
        }
        c.assertInvocations(0)
    }

    @Test
    fun localOnly_doesNotFallBackToCloud_whenLocalUnavailable() = runTest {
        val l = fakeProvider("local", ProviderKind.Local) {
            availability = ProviderAvailability.Unavailable(UnavailableReason.ModelMissing)
            complete("from-local")
        }
        val c = cloud()
        val store = InferenceStore.build {
            provider(l)
            provider(c)
            policy = Policies.preferLocalThenCloud()
        }

        // Local is privacy-allowed but unavailable; cloud is privacy-denied. The
        // request fails WITHOUT ever touching cloud — no privacy-bypassing fallback.
        val last = store.stream(req(PrivacyPolicy.localOnly())).toList().last()
        assertTrue(last is InferenceEvent.Failed)
        assertRoute(last.trace) {
            didNotAttempt("local")
            didNotAttempt("cloud")
            rejected("cloud", FallbackReason.PolicyViolation)
        }
        c.assertInvocations(0)
        l.assertInvocations(0)
    }

    @Test
    fun allCloudDenied_yieldsPrivacyDenied() = runTest {
        val a = cloud("a", "openai")
        val b = cloud("b", "anthropic")
        val store = InferenceStore.build {
            provider(a)
            provider(b)
            policy = Policies.cloudOnly()
        }

        val last = store.stream(req(PrivacyPolicy(classification = PrivacyClass.Sensitive))).toList().last()
        assertTrue(last is InferenceEvent.Failed)
        assertEquals(FinalStatus.PrivacyDenied, last.trace?.finalStatus)
        a.assertInvocations(0)
        b.assertInvocations(0)
    }

    @Test
    fun approvedProviders_invokesOnlyApproved() = runTest {
        val approved = cloud("approved", "openai")
        val other = cloud("other", "anthropic")
        val store = InferenceStore.build {
            provider(other)
            provider(approved)
            policy = Policies.cloudOnly()
        }
        val privacy = PrivacyPolicy(
            classification = PrivacyClass.Personal,
            cloud = CloudPermission.ApprovedProviders(setOf(ProviderId("approved"))),
        )

        val result = store.generate(req(privacy))
        assertEquals("from-approved", result.output)
        assertRoute(result.trace) {
            completedWith("approved")
            didNotAttempt("other")
            rejected("other", FallbackReason.PolicyViolation)
        }
        other.assertInvocations(0)
    }

    @Test
    fun approvedBoundaries_allowsMatchingBoundaryId() = runTest {
        val c = cloud("cloud", "openai") // boundary id "third-party-cloud:openai"
        val store = InferenceStore.build {
            provider(c)
            policy = Policies.cloudOnly()
        }
        val privacy = PrivacyPolicy(
            cloud = CloudPermission.ApprovedBoundaries(setOf(ProviderPrivacyBoundaryId("third-party-cloud:openai"))),
        )

        val result = store.generate(req(privacy))
        assertEquals("from-cloud", result.output)
        assertRoute(result.trace) { completedWith("cloud") }
    }

    @Test
    fun publicData_allowsCloud() = runTest {
        val c = cloud()
        val store = InferenceStore.build {
            provider(c)
            policy = Policies.cloudOnly()
        }

        val result = store.generate(req(PrivacyPolicy.publicData()))
        assertEquals("from-cloud", result.output)
        c.assertInvocations(1)
    }

    @Test
    fun privacyGate_cannotBeBypassedByCustomPolicy() = runTest {
        val c = cloud()
        // A rogue policy routes straight to the cloud provider, ignoring availability.
        val store = InferenceStore.build {
            provider(c)
            policy = InferencePolicy { candidates -> InferenceRoute("rogue", candidates.map { it.provider }) }
        }

        val last = store.stream(req(PrivacyPolicy.localOnly())).toList().last()
        assertTrue(last is InferenceEvent.Failed)
        assertEquals(FinalStatus.PrivacyDenied, last.trace?.finalStatus)
        c.assertInvocations(0)
    }

    @Test
    fun policy_onlySeesRoutableCandidates() = runTest {
        val l = local()
        val c = cloud() // cloud boundary -> privacy-denied under Personal default
        var seen: List<String> = emptyList()
        val capturing = InferencePolicy { candidates ->
            seen = candidates.map { it.provider.id.value }
            InferenceRoute("capture", candidates.map { it.provider })
        }
        val store = InferenceStore.build {
            provider(l)
            provider(c)
            policy = capturing
        }

        store.generate(req(PrivacyPolicy.Default)) // Personal: cloud denied
        assertEquals(listOf("local"), seen) // the privacy-denied cloud provider is not offered to the policy
        c.assertInvocations(0)
    }

    @Test
    fun localProvider_isAllowed_underLocalOnly() = runTest {
        val l = local()
        val store = InferenceStore.single(l)
        val result = store.generate(req(PrivacyPolicy.localOnly()))
        assertEquals("from-local", result.output)
        l.assertInvocations(1)
    }

    // --- Gate as a pure function ---

    @Test
    fun gate_localOnly_deniesCloud_evenIfCloudAllowed() {
        val policy = PrivacyPolicy(classification = PrivacyClass.LocalOnly, cloud = CloudPermission.Allowed)
        val decision = policy.allowsProvider(ProviderId("x"), ProviderPrivacyBoundary.thirdPartyCloud("x"))
        assertEquals(PrivacyDecision.Deny(PolicyViolation.CloudNotAllowed), decision)
    }

    @Test
    fun gate_localProviderAlwaysAllowed() {
        assertEquals(
            PrivacyDecision.Allow,
            PrivacyPolicy.Default.allowsProvider(ProviderId("l"), ProviderPrivacyBoundary.localDevice()),
        )
    }

    @Test
    fun gate_approvedProviders_distinguishesApproval() {
        val policy = PrivacyPolicy(cloud = CloudPermission.ApprovedProviders(setOf(ProviderId("ok"))))
        val boundary = ProviderPrivacyBoundary.thirdPartyCloud("v")
        assertEquals(PrivacyDecision.Allow, policy.allowsProvider(ProviderId("ok"), boundary))
        assertEquals(
            PrivacyDecision.Deny(PolicyViolation.ProviderNotApproved),
            policy.allowsProvider(ProviderId("nope"), boundary),
        )
    }

    @Test
    fun telemetryAndRedaction_areRedactingByDefault() {
        val p = PrivacyPolicy.Default
        assertFalse(p.telemetry.emitPrompt)
        assertFalse(p.telemetry.emitOutput)
        assertTrue(p.redaction.redactPrompts)
        assertTrue(p.redaction.redactOutputs)
        assertFalse(p.persistence.persistPrompt)
        assertFalse(p.persistence.persistOutput)
    }
}
