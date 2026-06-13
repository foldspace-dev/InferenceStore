# RFC-0002: Provider adapter model

Status: Draft  
Generated: 2026-06-13

## Summary

Define provider adapters as capability-reporting execution endpoints. Providers can be local runtimes, platform APIs, cloud APIs, app backends, or test fakes.

## Motivation

Inference runtimes are volatile and platform-specific. The core library should isolate this volatility behind optional adapter modules.

## Proposal

```kotlin
interface InferenceProvider {
    val id: ProviderId
    val kind: ProviderKind

    suspend fun availability(context: InferenceContext): ProviderAvailability

    suspend fun capabilities(
        request: InferenceRequest<*>,
        context: InferenceContext
    ): CapabilityReport

    fun <Output : Any> stream(
        request: ProviderRequest<Output>,
        context: InferenceContext
    ): Flow<ProviderEvent<Output>>
}
```

## Provider kind

```kotlin
enum class ProviderKind {
    Local,
    Cloud,
    Platform,
    Remote,
    Test
}
```

## Design decision: provider-owned fallback?

Some SDKs already provide local/cloud fallback internally.

Recommendation: InferenceStore should own fallback when possible. If an adapter wraps an SDK-level hybrid provider, it must report the actual source used in metadata.

## Adapter maturity

Adapters should be marked Experimental/Alpha/Beta/Stable independently of core.

## Acceptance criteria

- Fake provider can implement interface in common code.
- OpenAI-compatible adapter can implement it.
- Platform-specific adapters can implement it without core changes.
- Capability unsupported can be represented without throwing.
