# inferencestore-monitor-opentelemetry

An optional [OpenTelemetry](https://opentelemetry.io/) exporter for InferenceStore: an
`InferenceMonitor` that turns each request's route into a span with provider attempts,
fallbacks, retries, and the outcome (OSS-27). JVM/Android (OpenTelemetry's SDK is Java).

## Setup

Register an `OpenTelemetryMonitor` built from your configured `Tracer`:

```kotlin
val tracer = openTelemetry.getTracer("inferencestore")   // your OTel SDK / exporter pipeline
val store = InferenceStore.build {
    provider(local); provider(cloud)
    monitor(OpenTelemetryMonitor(tracer))
}
```

Each request emits one span named `inference.request`. Provider attempts, validation,
fallback, and retries are span **events**; the route outcome is span **attributes**. A
failed request sets the span status to `ERROR`.

To move export off the collector thread, set
`InferenceExecutionConfig.monitorContext`.

## Redaction

**No prompts or outputs are exported.** The exporter reads only the redacted
`MonitorEvent` projection, which carries none — token telemetry is a count, failures a
category. Nothing you summarize can leak to your tracing backend through this exporter.

## Attributes

Span attributes:

| Key | Type | Meaning |
| --- | --- | --- |
| `inferencestore.key` | string | the request `InferenceKey` (an app identifier, not content) |
| `inferencestore.route.provider` | string | provider the policy selected |
| `inferencestore.final.provider` | string | provider that produced the result |
| `inferencestore.final.status` | string | `Succeeded` / `Failed` / `Cancelled` / `PrivacyDenied` |
| `inferencestore.token.count` | long | cumulative token count (no text) |
| `inferencestore.error.category` | string | terminal error category (on failure) |

Event attributes (`provider.attempt.started` / `.completed`, `validation.completed`,
`fallback.started`, `retry.scheduled`):

| Key | Type | Meaning |
| --- | --- | --- |
| `inferencestore.provider` | string | provider for the attempt |
| `inferencestore.attempt` | long | attempt number |
| `inferencestore.outcome` | string | attempt outcome |
| `inferencestore.validation` | string | `pass` / `fail:<category>` |
| `inferencestore.fallback.reason` | string | why routing fell back |
| `inferencestore.retry.delay_ms` | long | scheduled retry delay |
