# Monitor

An `InferenceMonitor` observes a request's lifecycle for telemetry — route decisions,
attempts, fallbacks, validation, latency, token counts. Register one (or several) on
the store:

```kotlin
val store = InferenceStore.build {
    provider(local); provider(cloud)
    monitor { event ->
        when (event) {
            is MonitorEvent.FallbackStarted -> metrics.increment("fallback", event.reason.name)
            is MonitorEvent.RequestFailed -> logger.warn("failed: ${event.error}")
            else -> {}
        }
    }
}
```

`MonitorEvent` is the **redacted projection** of the canonical stream events: it never
carries raw prompts or outputs. `TokenEmitted` carries a cumulative *count* (not text);
`RequestFailed` carries only the `ErrorCategory`. Monitors must not block; a throwing
monitor never breaks a request. Move emission off the collector with
`InferenceExecutionConfig.monitorContext`.

Learn more: [observability & evals](../technical/observability-evals.md),
[event model](../technical/event-model.md).
