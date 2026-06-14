# Provider

An `InferenceProvider` wraps one runtime or endpoint (on-device model, cloud gateway).
It reports **availability** and **capabilities**, executes a single attempt as a cold
stream of `ProviderEvent`s, and declares a **privacy boundary** and **kind** (`Local`,
`Cloud`, `Platform`, `Remote`, `Test`).

```kotlin
interface InferenceProvider {
    val id: ProviderId
    val kind: ProviderKind
    val boundary: ProviderPrivacyBoundary
    suspend fun availability(context: InferenceContext): ProviderAvailability
    suspend fun capabilities(request: InferenceRequest<*>, context: InferenceContext): CapabilityReport
    fun <Output : Any> stream(request: ProviderRequest<Output>, context: InferenceContext): Flow<ProviderEvent<Output>>
}
```

The engine probes availability/capability, the policy orders providers, and the engine
runs the route with fallback. Adapters map raw failures to a stable `ErrorCategory` and
own their threading. Shipped adapters: OpenAI-compatible (cloud) and LiteRT-LM
(on-device).

Learn more: [Writing a provider adapter](../guides/writing-a-provider-adapter.md),
[provider adapters spec](../technical/provider-adapters.md).
