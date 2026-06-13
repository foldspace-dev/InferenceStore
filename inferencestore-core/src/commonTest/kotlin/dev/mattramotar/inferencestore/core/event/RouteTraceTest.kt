package dev.mattramotar.inferencestore.core.event

import dev.mattramotar.inferencestore.core.provider.ErrorCategory
import dev.mattramotar.inferencestore.core.provider.ProviderKind
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Golden traces (`event-model.md`): the route trace must serialize to a stable,
 * redacted JSON shape so it can be persisted/exported safely. Flows that require
 * routing/validation/timeout features (fallback execution, etc.) get their
 * engine-produced golden traces once those features land (OSS-13 / OSS-8 / OSS-21).
 */
class RouteTraceTest {

    private val json = Json

    @Test
    fun successTrace_goldenJson() {
        val trace = RouteTrace(
            requestId = "notes.summary/n1",
            key = "notes.summary/n1",
            finalStatus = FinalStatus.Succeeded,
            attempts = listOf(
                ProviderAttemptTrace(
                    providerId = "litertlm",
                    providerKind = ProviderKind.Local,
                    outcome = AttemptOutcome.Succeeded,
                    modelId = "gemma-2b",
                ),
            ),
            finalProvider = "litertlm",
        )
        assertEquals(
            """{"requestId":"notes.summary/n1","key":"notes.summary/n1","finalStatus":"Succeeded",""" +
                """"attempts":[{"providerId":"litertlm","providerKind":"Local","outcome":"Succeeded","modelId":"gemma-2b"}],""" +
                """"finalProvider":"litertlm"}""",
            json.encodeToString(trace),
        )
    }

    @Test
    fun validationRepairTrace_goldenJson() {
        val trace = RouteTrace(
            requestId = "k",
            key = "k",
            finalStatus = FinalStatus.Succeeded,
            attempts = listOf(
                ProviderAttemptTrace("litertlm", ProviderKind.Local, AttemptOutcome.Failed, errorCategory = ErrorCategory.ValidationFailed),
                ProviderAttemptTrace("openai-compatible", ProviderKind.Cloud, AttemptOutcome.Succeeded),
            ),
            fallbackReasons = listOf(FallbackReason.ValidatorRejected),
            finalProvider = "openai-compatible",
        )
        assertEquals(
            """{"requestId":"k","key":"k","finalStatus":"Succeeded","attempts":[""" +
                """{"providerId":"litertlm","providerKind":"Local","outcome":"Failed","errorCategory":"ValidationFailed"},""" +
                """{"providerId":"openai-compatible","providerKind":"Cloud","outcome":"Succeeded"}],""" +
                """"fallbackReasons":["ValidatorRejected"],"finalProvider":"openai-compatible"}""",
            json.encodeToString(trace),
        )
    }

    @Test
    fun privacyDeniedTrace_recordsRejectionWithoutInvocation() {
        val trace = RouteTrace(
            requestId = "k",
            key = "k",
            finalStatus = FinalStatus.PrivacyDenied,
            rejectedProviders = listOf(RejectedProviderTrace("openai-compatible", FallbackReason.PolicyViolation)),
        )
        assertEquals(
            """{"requestId":"k","key":"k","finalStatus":"PrivacyDenied",""" +
                """"rejectedProviders":[{"providerId":"openai-compatible","reason":"PolicyViolation"}]}""",
            json.encodeToString(trace),
        )
        // A privacy denial is auditable without any provider invocation.
        assertEquals(0, trace.attempts.size)
    }

    @Test
    fun trace_roundTripsThroughJson() {
        val trace = RouteTrace(
            requestId = "k",
            key = "k",
            finalStatus = FinalStatus.Failed,
            attempts = listOf(
                ProviderAttemptTrace("p", ProviderKind.Cloud, AttemptOutcome.Failed, errorCategory = ErrorCategory.RateLimited),
            ),
        )
        assertEquals(trace, json.decodeFromString<RouteTrace>(json.encodeToString(trace)))
    }

    @Test
    fun timeoutTrace_goldenJson() {
        val trace = RouteTrace(
            requestId = "k",
            key = "k",
            finalStatus = FinalStatus.Failed,
            attempts = listOf(
                ProviderAttemptTrace("litertlm", ProviderKind.Local, AttemptOutcome.Failed, errorCategory = ErrorCategory.Timeout),
            ),
        )
        assertEquals(
            """{"requestId":"k","key":"k","finalStatus":"Failed","attempts":[""" +
                """{"providerId":"litertlm","providerKind":"Local","outcome":"Failed","errorCategory":"Timeout"}]}""",
            json.encodeToString(trace),
        )
    }

    @Test
    fun cancellationTrace_goldenJson() {
        // A cancelled request: the in-flight attempt has no terminal outcome.
        val trace = RouteTrace(
            requestId = "k",
            key = "k",
            finalStatus = FinalStatus.Cancelled,
            attempts = listOf(ProviderAttemptTrace("litertlm", ProviderKind.Local)),
        )
        assertEquals(
            """{"requestId":"k","key":"k","finalStatus":"Cancelled","attempts":[""" +
                """{"providerId":"litertlm","providerKind":"Local"}]}""",
            json.encodeToString(trace),
        )
    }
}
