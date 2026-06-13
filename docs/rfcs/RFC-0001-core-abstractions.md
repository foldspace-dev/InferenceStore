# RFC-0001: Core abstractions

Status: Draft  
Generated: 2026-06-13

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
    val timeout: Duration? = null,
    val prompt: PromptSpec? = null,
    val metadata: Map<String, String> = emptyMap()
)
```

## Decision points

1. Should `key` be required?
2. Should `OutputSpec` include serializer?
3. Should privacy be required?
4. Should `generate` be implemented as first `Done` from `stream`?

## Recommendation

- Require `key` for core request.
- Provide convenience APIs that generate ephemeral keys.
- Require privacy default.
- Make `stream` primary; `generate` is convenience.
