package dev.mattramotar.inferencestore.monitor.opentelemetry

import dev.mattramotar.inferencestore.core.event.FinalStatus
import dev.mattramotar.inferencestore.core.event.RequestId
import dev.mattramotar.inferencestore.core.monitor.InferenceMonitor
import dev.mattramotar.inferencestore.core.monitor.MonitorEvent
import dev.mattramotar.inferencestore.core.validation.ValidationResult
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import java.util.concurrent.ConcurrentHashMap

/**
 * An [InferenceMonitor] that exports each request's route to OpenTelemetry: one span
 * per request, with provider attempts, validation, fallback, and retries as span events,
 * and the outcome as span attributes (OSS-27, `observability-evals.md`).
 *
 * Redacted by construction: it reads only the redacted [MonitorEvent] projection, which
 * carries no prompts or outputs — token telemetry is a count, failures a category. So no
 * content can reach your tracing backend through this exporter.
 *
 * Supply a configured [Tracer]; the exporter is otherwise pipeline-agnostic. Thread-safe
 * (one span per in-flight request, tracked in a concurrent map).
 *
 * Limitation: a caller-cancelled request emits no terminal monitor event, so its started
 * span is neither ended nor exported, and its tracking-map entry persists for the
 * monitor's lifetime. A long-lived monitor under heavy cancellation should account for
 * that; the engine emits a terminal event for every non-cancelled outcome.
 */
public class OpenTelemetryMonitor(private val tracer: Tracer) : InferenceMonitor {

    private val spans: ConcurrentHashMap<RequestId, Span> = ConcurrentHashMap()

    override fun onEvent(event: MonitorEvent) {
        when (event) {
            is MonitorEvent.RequestStarted -> {
                val span = tracer.spanBuilder(SPAN_NAME).setSpanKind(SpanKind.CLIENT).startSpan()
                span.setAttribute(KEY, event.key.asString())
                spans[event.requestId] = span
            }

            is MonitorEvent.RouteSelected ->
                spans[event.requestId]?.setAttribute(ROUTE_PROVIDER, event.provider.value)

            is MonitorEvent.ProviderAttemptStarted ->
                spans[event.requestId]?.addEvent(
                    "provider.attempt.started",
                    Attributes.builder()
                        .put(PROVIDER, event.attempt.provider.value)
                        .put(ATTEMPT, event.attempt.attemptNumber.toLong())
                        .build(),
                )

            is MonitorEvent.ProviderAttemptCompleted ->
                spans[event.requestId]?.addEvent(
                    "provider.attempt.completed",
                    Attributes.builder()
                        .put(PROVIDER, event.attempt.provider.value)
                        .put(ATTEMPT, event.attempt.attemptNumber.toLong())
                        .apply {
                            event.attempt.outcome?.let { put(OUTCOME, it.name) }
                            event.attempt.error?.let { put(ERROR_CATEGORY, it.name) }
                        }
                        .build(),
                )

            // Token text is never available here; the cumulative count lands on completion.
            is MonitorEvent.TokenEmitted -> Unit

            is MonitorEvent.ValidationCompleted ->
                spans[event.requestId]?.addEvent(
                    "validation.completed",
                    Attributes.of(VALIDATION, validationLabel(event.result)),
                )

            is MonitorEvent.FallbackStarted ->
                spans[event.requestId]?.addEvent(
                    "fallback.started",
                    Attributes.of(FALLBACK_REASON, event.reason.name),
                )

            is MonitorEvent.RetryScheduled ->
                spans[event.requestId]?.addEvent(
                    "retry.scheduled",
                    Attributes.builder()
                        .put(PROVIDER, event.provider.value)
                        .put(ATTEMPT, event.attemptNumber.toLong())
                        .put(RETRY_DELAY_MS, event.delay.inWholeMilliseconds)
                        .build(),
                )

            is MonitorEvent.RequestCompleted ->
                spans.remove(event.requestId)?.apply {
                    event.summary.finalProvider?.let { setAttribute(FINAL_PROVIDER, it) }
                    setAttribute(FINAL_STATUS, event.summary.finalStatus.name)
                    setAttribute(TOKEN_COUNT, event.summary.tokenCount.toLong())
                    end()
                }

            is MonitorEvent.RequestFailed ->
                spans.remove(event.requestId)?.apply {
                    setStatus(StatusCode.ERROR)
                    // Keep the terminal attribute schema consistent with RequestCompleted.
                    setAttribute(FINAL_STATUS, FinalStatus.Failed.name)
                    setAttribute(ERROR_CATEGORY, event.error.name)
                    end()
                }
        }
    }

    private fun validationLabel(result: ValidationResult): String = when (result) {
        ValidationResult.Pass -> "pass"
        is ValidationResult.Fail -> "fail:${result.category.name}"
    }

    public companion object {
        public const val SPAN_NAME: String = "inference.request"

        // Stable, content-free attribute keys (documented in the README).
        public val KEY: AttributeKey<String> = AttributeKey.stringKey("inferencestore.key")
        public val ROUTE_PROVIDER: AttributeKey<String> = AttributeKey.stringKey("inferencestore.route.provider")
        public val PROVIDER: AttributeKey<String> = AttributeKey.stringKey("inferencestore.provider")
        public val ATTEMPT: AttributeKey<Long> = AttributeKey.longKey("inferencestore.attempt")
        public val OUTCOME: AttributeKey<String> = AttributeKey.stringKey("inferencestore.outcome")
        public val VALIDATION: AttributeKey<String> = AttributeKey.stringKey("inferencestore.validation")
        public val FALLBACK_REASON: AttributeKey<String> = AttributeKey.stringKey("inferencestore.fallback.reason")
        public val RETRY_DELAY_MS: AttributeKey<Long> = AttributeKey.longKey("inferencestore.retry.delay_ms")
        public val FINAL_PROVIDER: AttributeKey<String> = AttributeKey.stringKey("inferencestore.final.provider")
        public val FINAL_STATUS: AttributeKey<String> = AttributeKey.stringKey("inferencestore.final.status")
        public val TOKEN_COUNT: AttributeKey<Long> = AttributeKey.longKey("inferencestore.token.count")
        public val ERROR_CATEGORY: AttributeKey<String> = AttributeKey.stringKey("inferencestore.error.category")
    }
}
