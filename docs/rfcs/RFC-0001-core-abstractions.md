# RFC-0001: Core abstractions

Status: Accepted for MVP  
Updated: 2026-06-13

## Summary

Define the minimal abstractions for InferenceStore core:

- `InferenceStore`
- `InferenceRequest`
- `InferenceProvider`
- `InferencePolicy`
- `InferenceEvent`
- `InferenceResult`
- `OutputValidator`
- `InferenceMonitor`

## Motivation

Hybrid inference code is currently likely to be scattered across feature code. The core library needs stable abstractions that allow provider-neutral request orchestration while preserving real capability differences.

## Proposal

Use request-carried output types instead of generic Store instances.

```kotlin
interface InferenceStore {
    fun <Output : Any> stream(request: InferenceRequest<Output>): Flow<InferenceEvent<Output>>
    suspend fun <Output : Any> generate(request: InferenceRequest<Output>): InferenceResult<Output>
}
```

## Why not `InferenceStore<Key, Output>`?

Store's `Key -> Output` relationship is stable for data retrieval. Inference requests often vary by prompt, privacy, output schema, model constraints, and policy. A request object better captures this.

## Request model

```kotlin
data class InferenceRequest<Output : Any>(
    val key: InferenceKey,
    val input: InferenceInput,
    val output: OutputSpec<Output>,
    val policy: InferencePolicy? = null,
    val privacy: PrivacyPolicy = PrivacyPolicy.Default,
    val cache: CachePolicy = CachePolicy.Default,
    val timeout: TimeoutPolicy = TimeoutPolicy.Default,
    val retry: RetryPolicy = RetryPolicy.Default,
    val prompt: PromptSpec? = null,
    val metadata: Map<String, String> = emptyMap()
)
```

## Decisions

- `key` is required for core request when cache, dedupe, artifacts, or durable traces are enabled. Convenience APIs may generate ephemeral keys only when cache and dedupe are disabled.
- `OutputSpec` includes serializers only for typed JSON/custom outputs. Text remains simple.
- Every request carries `PrivacyPolicy`; `privacy-model.md` owns defaults.
- `stream` is primary; `generate` is convenience over the terminal `Done` result.
- MVP validates final output only. Partial validation is post-MVP.
