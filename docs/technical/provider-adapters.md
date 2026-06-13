# Provider adapters

Generated: 2026-06-13

## Purpose

Provider adapters connect InferenceStore to actual inference systems: local runtimes, platform APIs, cloud APIs, app backends, and test fakes.

Core should not depend on adapter implementation details.

## Adapter contract

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

## Provider event contract

```kotlin
sealed interface ProviderEvent<out Output : Any> {
    data class Started(val metadata: ProviderMetadata) : ProviderEvent<Nothing>
    data class Token(val text: String) : ProviderEvent<Nothing>
    data class Partial<Output : Any>(val value: Output) : ProviderEvent<Output>
    data class Completed<Output : Any>(
        val output: Output,
        val rawText: String?,
        val usage: Usage? = null,
        val metadata: ProviderMetadata
    ) : ProviderEvent<Output>
    data class Failed(val error: ProviderError) : ProviderEvent<Nothing>
}
```

## Adapter responsibilities

Adapters must:

- map provider-specific errors to stable categories
- report availability and capabilities honestly
- respect cancellation if possible
- stream tokens/events when supported
- avoid logging prompts/outputs by default
- expose model/runtime metadata
- support timeouts if the underlying client supports them
- document unsupported capabilities
- provide tests with fake server/provider where possible

Adapters should not:

- decide global routing policy
- persist prompts/results directly
- silently call cloud from a local provider unless explicitly documented and represented in metadata
- hide provider fallback from the route trace
- throw raw provider exceptions without mapping them

## Initial adapter candidates

### OpenAI-compatible HTTP

Why first:

- works against many cloud providers and gateways
- can also work with local servers
- easy to test with mock HTTP
- provides immediate cloud fallback demo

Capabilities:

- text generation
- chat
- streaming
- structured output depending on backend
- token usage depending on backend

### Firebase AI Logic Android

Why useful:

- official Android hybrid path
- existing local/cloud modes
- good comparison point

Design question:

Should this adapter expose Firebase's hybrid mode as one provider, or expose on-device and cloud as separate providers?

Recommendation:

- expose **separate logical providers** when possible so InferenceStore can own route traces
- optionally support a `FirebaseHybridProvider` as a convenience adapter that reports Firebase's own source metadata

### Apple Foundation Models iOS

Why useful:

- native Apple integration
- important for iOS local/platform model support

Design question:

How much Swift interop lives in adapter?

Recommendation:

- keep common contract in Kotlin
- implement platform provider in iOS source set
- expose minimal bridging API for Swift-only model setup if required

### Llamatik

Why useful:

- KMP-friendly local/remote runtime path
- aligns with Kotlin-first audience

Design question:

Should InferenceStore depend on Llamatik types?

Recommendation:

- adapter module may depend on Llamatik
- core must not

### Cactus

Why useful:

- already exposes local/remote/local-first modes
- useful for comparison and early demo

Design question:

Does Cactus already overlap too much?

Recommendation:

- treat as runtime/provider adapter; InferenceStore still adds cross-provider policy, testkit, observability, cache, validators.

### LiteRT-LM

Why useful:

- strong Android/JVM and emerging Swift/iOS support
- open-source edge LLM framework

Design question:

How to support iOS + Android with one adapter?

Recommendation:

- start with Android/JVM if Kotlin APIs are stable
- add iOS Swift adapter separately

### MLC LLM

Why useful:

- OpenAI-compatible engine across REST, Python, JS, iOS, Android
- can be used through OpenAI-compatible adapter in some deployment shapes

Recommendation:

- do not build native MLC adapter until users ask
- document use through OpenAI-compatible local server first

## Adapter module naming

```text
inferencestore-provider-openai-compatible
inferencestore-provider-firebase-android
inferencestore-provider-apple-foundationmodels-ios
inferencestore-provider-llamatik
inferencestore-provider-cactus
inferencestore-provider-litertlm
inferencestore-provider-mlc
```

## Capability mapping example

```kotlin
class OpenAICompatibleProvider(...) : InferenceProvider {
    override val id = ProviderId("openai-compatible")
    override val kind = ProviderKind.Cloud

    override suspend fun availability(context: InferenceContext): ProviderAvailability {
        return if (context.network.isOnline) Available else Unavailable(NetworkUnavailable)
    }

    override suspend fun capabilities(
        request: InferenceRequest<*>,
        context: InferenceContext
    ): CapabilityReport {
        val capabilities = buildSet {
            add(Capability.TextGeneration)
            add(Capability.Chat)
            add(Capability.Streaming)
            if (config.supportsStructuredOutput) add(Capability.StructuredOutput)
        }

        return CapabilityReport(
            supported = request.requiredCapabilities.all { it in capabilities },
            capabilities = capabilities
        )
    }
}
```

## Provider metadata

```kotlin
data class ProviderMetadata(
    val providerId: ProviderId,
    val providerKind: ProviderKind,
    val modelId: String?,
    val modelVersion: String?,
    val runtimeVersion: String?,
    val local: Boolean,
    val cloud: Boolean,
    val extra: Map<String, String> = emptyMap()
)
```

## Provider testing expectations

Each adapter should include:

- success test
- streaming test
- cancellation test
- availability test
- unsupported capability test
- error mapping test
- redaction/monitoring test

## Adapter maturity levels

### Experimental

- compiles
- basic success path
- known missing capabilities

### Alpha

- documented capability mapping
- tests for errors/cancellation
- sample usage

### Beta

- stable configuration API
- production-minded docs
- tested against real provider/runtime versions

### Stable

- compatibility policy
- version matrix
- migration docs
