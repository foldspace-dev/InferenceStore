# Export traces to OpenTelemetry

**Goal:** turn InferenceStore's redacted route telemetry into OpenTelemetry spans, so
inference shows up in your existing tracing backend.

## 1. Add the monitor module (JVM)

```kotlin
dependencies {
    implementation("dev.mattramotar.inferencestore:inferencestore-monitor-opentelemetry:<version>")
}
```

## 2. Register the monitor with a Tracer

```kotlin
val tracer: Tracer = openTelemetry.getTracer("inferencestore")

val store = InferenceStore.build {
    provider(onDevice); provider(cloud)
    monitor(OpenTelemetryMonitor(tracer))
}
```

That's it — every request now emits an `inference.request` span.

## 3. What lands on the span

`OpenTelemetryMonitor` maps the redacted [`MonitorEvent`](../../concepts/monitor.md) stream
to span attributes and a final status. All keys are stable and **content-free** — no
prompts or outputs ever leave:

| Attribute | Meaning |
|---|---|
| `inferencestore.key` | the request key |
| `inferencestore.route.provider` | the selected provider |
| `inferencestore.attempt` / `inferencestore.provider` | per-attempt provider + number |
| `inferencestore.fallback.reason` | why a hop occurred |
| `inferencestore.final.provider` / `inferencestore.final.status` | terminal outcome |
| `inferencestore.token.count` | cumulative token *count* (never the text) |
| `inferencestore.error.category` | the stable error category on failure |

## 4. Verify

In a test you can use the OpenTelemetry in-memory exporter and assert the span was
recorded with the expected attributes — no collector required:

```kotlin
val spans = InMemorySpanExporter.create()
// … build an SdkTracerProvider with a SimpleSpanProcessor(spans) …
// run a request, then:
assertEquals("inference.request", spans.finishedSpanItems.single().name)
```

!!! note "Why content-free by design"
    The monitor only ever sees `MonitorEvent`s, which carry counts and categories — not raw
    content. So the exporter physically *cannot* leak prompts or outputs, no matter how it's
    configured.

## See also

- [Monitor](../../concepts/monitor.md)
- [Observability &amp; evals](../../technical/observability-evals.md)
- [Telemetry upload](../../concepts/telemetry-upload.md) — batching traces to your own backend
