package dev.mattramotar.inferencestore.samples.validation

import dev.mattramotar.inferencestore.core.event.AttemptOutcome
import dev.mattramotar.inferencestore.core.event.FinalStatus
import dev.mattramotar.inferencestore.core.event.ProviderAttemptTrace
import dev.mattramotar.inferencestore.core.event.RejectedProviderTrace
import dev.mattramotar.inferencestore.core.event.FallbackReason
import dev.mattramotar.inferencestore.core.event.RouteTrace
import dev.mattramotar.inferencestore.core.provider.ErrorCategory
import dev.mattramotar.inferencestore.core.provider.ProviderKind
import kotlinx.serialization.json.Json

/**
 * A scripted validation demo of InferenceStore's four flagship flows (OSS-6).
 *
 * It constructs the **canonical [RouteTrace]** for each flow by hand and prints it — it
 * does NOT invoke the routing engine, so it runs as a standalone specification artifact,
 * independent of the M1 core implementation. The traces here are the same type the real
 * engine emits, so the demo doubles as living documentation of the trace contract.
 *
 * Run it with: `./gradlew :samples:validation-demo:run`
 */
public object ValidationDemo {

    private const val LOCAL = "litertlm-local"
    private const val CLOUD = "openai-cloud"
    private const val LOCAL_MODEL = "gemma-3n-e2b"
    private const val CLOUD_MODEL = "gpt-4o-mini"
    private const val POLICY = "prefer-local-then-cloud"

    /** One flagship flow: a title, a one-line narrative, and its canonical trace. */
    public data class DemoFlow(
        public val title: String,
        public val narrative: String,
        public val trace: RouteTrace,
    )

    /** 1. Local succeeds on the first attempt; cloud is never needed. */
    public val localSuccess: DemoFlow = DemoFlow(
        title = "Local success",
        narrative = "The on-device model answers directly — no cloud call, nothing leaves the device.",
        trace = RouteTrace(
            requestId = "demo:summarize:local-success",
            key = "demo:summarize",
            finalStatus = FinalStatus.Succeeded,
            policyId = POLICY,
            attempts = listOf(
                ProviderAttemptTrace(
                    providerId = LOCAL,
                    providerKind = ProviderKind.Local,
                    outcome = AttemptOutcome.Succeeded,
                    modelId = LOCAL_MODEL,
                    firstTokenAtMillis = 12,
                    completedAtMillis = 210,
                ),
            ),
            finalProvider = LOCAL,
            startedAtMillis = 0,
            completedAtMillis = 210,
        ),
    )

    /** 2. Local is unavailable, so routing falls back to cloud, which succeeds. */
    public val cloudFallback: DemoFlow = DemoFlow(
        title = "Local unavailable → cloud fallback",
        narrative = "The local model isn't ready (e.g. not downloaded), so routing falls back to cloud.",
        trace = RouteTrace(
            requestId = "demo:summarize:cloud-fallback",
            key = "demo:summarize",
            finalStatus = FinalStatus.Succeeded,
            policyId = POLICY,
            attempts = listOf(
                ProviderAttemptTrace(
                    providerId = LOCAL,
                    providerKind = ProviderKind.Local,
                    outcome = AttemptOutcome.Failed,
                    errorCategory = ErrorCategory.ProviderUnavailable,
                    completedAtMillis = 8,
                ),
                ProviderAttemptTrace(
                    providerId = CLOUD,
                    providerKind = ProviderKind.Cloud,
                    outcome = AttemptOutcome.Succeeded,
                    modelId = CLOUD_MODEL,
                    firstTokenAtMillis = 140,
                    completedAtMillis = 520,
                ),
            ),
            fallbackReasons = listOf(FallbackReason.ProviderUnavailable),
            finalProvider = CLOUD,
            startedAtMillis = 0,
            completedAtMillis = 520,
        ),
    )

    /** 3. Local output fails schema validation, so cloud "repairs" it on the next hop. */
    public val schemaRepair: DemoFlow = DemoFlow(
        title = "Local schema invalid → cloud repair",
        narrative = "The local model streams output that fails schema validation; routing falls back to cloud to produce a valid result.",
        trace = RouteTrace(
            requestId = "demo:extract:schema-repair",
            key = "demo:extract",
            finalStatus = FinalStatus.Succeeded,
            policyId = POLICY,
            attempts = listOf(
                ProviderAttemptTrace(
                    providerId = LOCAL,
                    providerKind = ProviderKind.Local,
                    outcome = AttemptOutcome.Failed,
                    modelId = LOCAL_MODEL,
                    errorCategory = ErrorCategory.ValidationFailed,
                    firstTokenAtMillis = 15,
                    completedAtMillis = 180,
                ),
                ProviderAttemptTrace(
                    providerId = CLOUD,
                    providerKind = ProviderKind.Cloud,
                    outcome = AttemptOutcome.Succeeded,
                    modelId = CLOUD_MODEL,
                    firstTokenAtMillis = 150,
                    completedAtMillis = 560,
                ),
            ),
            fallbackReasons = listOf(FallbackReason.SchemaInvalid),
            finalProvider = CLOUD,
            startedAtMillis = 0,
            completedAtMillis = 560,
        ),
    )

    /**
     * 4. A local-only request: the privacy gate rejects the cloud provider BEFORE it is
     * invoked. Cloud appears in [RouteTrace.rejectedProviders] (PolicyViolation), never
     * in [RouteTrace.attempts], so no prompt ever reaches the network.
     */
    public val privacyDenial: DemoFlow = DemoFlow(
        title = "Local-only privacy denial before cloud invocation",
        narrative = "The request is local-only; the privacy gate denies the cloud provider before any call, so it's recorded as rejected and never invoked.",
        trace = RouteTrace(
            requestId = "demo:summarize:privacy-denial",
            key = "demo:summarize",
            finalStatus = FinalStatus.Succeeded,
            policyId = "local-only",
            attempts = listOf(
                ProviderAttemptTrace(
                    providerId = LOCAL,
                    providerKind = ProviderKind.Local,
                    outcome = AttemptOutcome.Succeeded,
                    modelId = LOCAL_MODEL,
                    firstTokenAtMillis = 12,
                    completedAtMillis = 205,
                ),
            ),
            rejectedProviders = listOf(
                RejectedProviderTrace(providerId = CLOUD, reason = FallbackReason.PolicyViolation),
            ),
            finalProvider = LOCAL,
            startedAtMillis = 0,
            completedAtMillis = 205,
        ),
    )

    /** The four flagship flows, in narrative order. */
    public val flows: List<DemoFlow> = listOf(localSuccess, cloudFallback, schemaRepair, privacyDenial)

    private val json: Json = Json {
        prettyPrint = true
        encodeDefaults = true // show the full canonical shape, including empty lists/defaults
    }

    /** Renders one flow as a header, narrative, and its canonical route trace as JSON. */
    public fun render(flow: DemoFlow): String = buildString {
        appendLine("── ${flow.title} ".padEnd(72, '─'))
        appendLine(flow.narrative)
        appendLine()
        appendLine(json.encodeToString(RouteTrace.serializer(), flow.trace))
    }

    /** Renders all four flows. */
    public fun renderAll(): String = buildString {
        appendLine("InferenceStore — validation demo (canonical route traces, no engine)")
        appendLine()
        flows.forEach { appendLine(render(it)) }
    }
}
