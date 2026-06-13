# Observability and evals

Updated: 2026-06-13

## Purpose

Hybrid inference cannot be trusted if teams cannot see what happened.

Every request should produce a trace:

- which providers were considered;
- which provider was selected;
- which providers were rejected by privacy or capability checks;
- why fallback happened;
- which model generated the result;
- how long it took;
- whether validation passed;
- whether cache was used;
- whether privacy constraints shaped the route.

## Canonical lifecycle

Stream events, monitor events, and route traces are based on the canonical lifecycle in `docs/technical/event-model.md`. Monitor events are redacted projections; they must not define a parallel lifecycle.

## Monitor API

```kotlin
interface InferenceMonitor {
    fun onEvent(event: MonitorEvent)
}
```

Monitor hooks are non-suspending in core. Implementations should hand off to non-blocking sinks if they perform I/O.

## Monitor projection examples

```kotlin
sealed interface MonitorEvent {
    data class RequestStarted(val requestId: RequestId, val key: InferenceKey) : MonitorEvent
    data class CacheChecked(val requestId: RequestId, val outcome: CacheOutcome) : MonitorEvent
    data class ProvidersEvaluated(val requestId: RequestId, val candidates: List<ProviderCandidateSummary>) : MonitorEvent
    data class RouteSelected(val requestId: RequestId, val route: InferenceRouteSummary) : MonitorEvent
    data class ProviderAttemptStarted(val requestId: RequestId, val attempt: ProviderAttemptSummary) : MonitorEvent
    data class TokenEmitted(val requestId: RequestId, val count: Int) : MonitorEvent // no token text by default
    data class FallbackStarted(val requestId: RequestId, val reason: FallbackReason) : MonitorEvent
    data class RetryScheduled(val requestId: RequestId, val provider: ProviderId, val attemptNumber: Int, val delay: Duration) : MonitorEvent
    data class ValidationCompleted(val requestId: RequestId, val result: ValidationResult) : MonitorEvent
    data class ProviderAttemptCompleted(val requestId: RequestId, val attempt: ProviderAttemptSummary) : MonitorEvent
    data class RequestCompleted(val requestId: RequestId, val summary: RequestSummary) : MonitorEvent
    data class RequestFailed(val requestId: RequestId, val error: InferenceError) : MonitorEvent
}
```

## Redaction

Monitor events must not include raw prompts or outputs by default.

Recommended monitor payload:

- include fingerprints/hashes;
- include metadata keys, not values unless opted in;
- include token counts, not token text;
- include provider/model IDs;
- include provider privacy boundary;
- include error categories;
- include timing.

## Route trace

```kotlin
data class RouteTrace(
    val requestId: RequestId,
    val policyId: String?,
    val attempts: List<ProviderAttemptTrace>,
    val rejectedCandidates: List<RejectedProviderTrace>,
    val cache: CacheOutcome?,
    val validation: List<ValidationResult>,
    val startedAt: Instant,
    val completedAt: Instant?,
    val finalProvider: ProviderId?,
    val finalStatus: FinalStatus
)
```

## Attempt trace

```kotlin
data class ProviderAttemptTrace(
    val provider: ProviderMetadata,
    val selectedAt: Instant,
    val firstTokenAt: Instant?,
    val completedAt: Instant?,
    val status: AttemptStatus,
    val fallbackReason: FallbackReason?,
    val usage: Usage?,
    val timeoutSource: TimeoutSource? = null
)
```

## Usage model

```kotlin
data class Usage(
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val totalTokens: Long? = null,
    val estimatedCostMicros: Long? = null,
    val energyEstimate: EnergyEstimate? = null
)
```

Token counts may be unavailable or inconsistent across providers. The API should mark them optional.

## Exporters

Core should define monitor hooks only. Optional modules can export to:

- logs;
- OpenTelemetry;
- Firebase Performance;
- Datadog;
- custom analytics;
- file/debug console.

## Debug UI

A sample app should include a simple route trace view:

```text
Request: notes.summary/123
Policy: preferLocalThenCloud
Attempt 1: local/litertlm — validation failed: SchemaInvalid
Attempt 2: cloud/openai-compatible — success
TTFT: 320ms
Total: 1.8s
Cache: miss
Privacy: Personal, cloud approved for openai-compatible
```

## Evals

Evals answer: “Did the output behave correctly?”

For MVP, support simple validators. For post-MVP, define evaluator hooks:

```kotlin
interface InferenceEvaluator<Output : Any> {
    suspend fun evaluate(
        request: InferenceRequest<Output>,
        output: Output,
        trace: RouteTrace
    ): EvaluationResult
}
```

## Evaluation result

```kotlin
data class EvaluationResult(
    val score: Double?,
    val passed: Boolean,
    val dimensions: Map<String, Double> = emptyMap(),
    val reason: String? = null
)
```

## Eval use cases

- local model rollout comparison;
- prompt regression testing;
- route policy tuning;
- cost/quality tradeoff measurement;
- schema reliability;
- safety compliance;
- offline degraded mode quality.

## Shadow evaluation

Post-MVP:

- user sees cloud result;
- local result generated in shadow;
- evaluator compares outputs;
- telemetry records local readiness.

Do not expose shadow mode until privacy semantics and cancellation are solid.

## Metrics taxonomy

### Routing

- local selected;
- cloud selected;
- fallback count;
- fallback reason;
- provider unavailable;
- capability unsupported;
- privacy denied provider count.

### Performance

- time to first token;
- total latency;
- tokens per second;
- availability probe latency;
- model initialization latency;
- validation latency.

### Cost

- estimated cloud cost;
- avoided cloud calls;
- token usage;
- cache hit savings.

### Quality

- validation pass rate;
- repair rate;
- evaluator score;
- user feedback.

### Reliability

- timeout rate;
- cancellation rate;
- transient error rate;
- permanent error rate;
- rate limit rate;
- local initialization failure rate.

## Privacy audit

For privacy-sensitive apps, monitor should answer:

- Was cloud allowed?
- Was cloud attempted?
- Was prompt persisted?
- Was output persisted?
- Which redaction policy was used?
- Which provider privacy boundary applied?
- Which providers were rejected before invocation?

## Resolved questions

1. Should OpenTelemetry be a first-party module? **Yes, post-MVP.**
2. Should route traces be returned in every result? **Yes.**
3. Should monitor hooks be suspending? **No for core.**
4. Should evals run inline? **Validators inline; expensive evals async/post-MVP.**
