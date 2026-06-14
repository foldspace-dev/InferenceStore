package dev.mattramotar.inferencestore.samples.validation

import dev.mattramotar.inferencestore.core.event.AttemptOutcome
import dev.mattramotar.inferencestore.core.event.FallbackReason
import dev.mattramotar.inferencestore.core.event.FinalStatus
import dev.mattramotar.inferencestore.core.event.RouteTrace
import dev.mattramotar.inferencestore.core.provider.ProviderKind
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Verifies the OSS-6 validation demo's four flagship flows and canonical traces. */
class ValidationDemoTest {

    @Test
    fun demo_hasExactlyTheFourFlagshipFlows() {
        assertEquals(4, ValidationDemo.flows.size)
        assertEquals(
            listOf(
                "Local success",
                "Local unavailable → cloud fallback",
                "Local schema invalid → cloud repair",
                "Local-only privacy denial before cloud invocation",
            ),
            ValidationDemo.flows.map { it.title },
        )
    }

    @Test
    fun localSuccess_servesLocally_withoutFallbackOrCloud() {
        val t = ValidationDemo.localSuccess.trace
        assertEquals(FinalStatus.Succeeded, t.finalStatus)
        assertEquals(1, t.attempts.size)
        assertEquals(ProviderKind.Local, t.attempts.single().providerKind)
        assertEquals(AttemptOutcome.Succeeded, t.attempts.single().outcome)
        assertTrue(t.fallbackReasons.isEmpty())
        assertTrue(t.rejectedProviders.isEmpty())
        assertEquals("litertlm-local", t.finalProvider)
    }

    @Test
    fun cloudFallback_fallsBackToCloud_onLocalUnavailable() {
        val t = ValidationDemo.cloudFallback.trace
        assertEquals(FinalStatus.Succeeded, t.finalStatus)
        assertEquals(2, t.attempts.size)
        assertEquals(ProviderKind.Local, t.attempts[0].providerKind)
        assertEquals(AttemptOutcome.Failed, t.attempts[0].outcome)
        assertEquals(ProviderKind.Cloud, t.attempts[1].providerKind)
        assertEquals(AttemptOutcome.Succeeded, t.attempts[1].outcome)
        assertEquals(listOf(FallbackReason.ProviderUnavailable), t.fallbackReasons)
        assertEquals("openai-cloud", t.finalProvider)
    }

    @Test
    fun schemaRepair_fallsBackToCloud_onValidationFailure() {
        val t = ValidationDemo.schemaRepair.trace
        assertEquals(FinalStatus.Succeeded, t.finalStatus)
        assertEquals(AttemptOutcome.Failed, t.attempts.first().outcome)
        assertContains(t.fallbackReasons, FallbackReason.SchemaInvalid)
        assertEquals("openai-cloud", t.finalProvider)
    }

    @Test
    fun privacyDenial_rejectsCloudBeforeInvocation() {
        val t = ValidationDemo.privacyDenial.trace
        // Cloud is recorded as rejected by policy and is NEVER invoked (not in attempts).
        assertEquals(1, t.rejectedProviders.size)
        val rejected = t.rejectedProviders.single()
        assertEquals("openai-cloud", rejected.providerId)
        assertEquals(FallbackReason.PolicyViolation, rejected.reason)
        assertTrue(t.attempts.none { it.providerId == "openai-cloud" })
        assertEquals("litertlm-local", t.finalProvider)
    }

    @Test
    fun render_printsHumanSummaryAndCanonicalTrace_thatRoundTrips() {
        val rendered = ValidationDemo.render(ValidationDemo.localSuccess)
        assertContains(rendered, "Local success")
        assertContains(rendered, "demo:summarize:local-success")

        // The printed trace is the canonical, serializable RouteTrace: decode the JSON
        // body back and confirm it equals the source trace.
        val jsonBody = rendered.substringAfter("{").let { "{$it" }.trim()
        val decoded = Json.decodeFromString(RouteTrace.serializer(), jsonBody)
        assertEquals(ValidationDemo.localSuccess.trace, decoded)
    }

    @Test
    fun renderAll_includesAllFourFlows() {
        val all = ValidationDemo.renderAll()
        ValidationDemo.flows.forEach { assertContains(all, it.title) }
    }
}
