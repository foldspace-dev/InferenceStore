# Provider adapters

Updated: 2026-06-13

## Purpose

Provider adapters connect InferenceStore to actual inference systems: local runtimes, platform APIs, cloud APIs, app backends, and test fakes.

Core should not depend on adapter implementation details.

## Adapter contract

```kotlin
interface InferenceProvider {
    val id: ProviderId
    val kind: ProviderKind
    val boundary: ProviderPrivacyBoundary

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

Core maps provider events into the canonical `InferenceEvent` lifecycle in `event-model.md`.

## Adapter responsibilities

Adapters must:

- map provider-specific errors to stable categories from `error-fallback-mapping.md`;
- report availability and capabilities honestly;
- declare an accurate `ProviderPrivacyBoundary`;
- respect cancellation if possible;
- stream tokens/events when supported;
- avoid logging prompts/outputs by default;
- expose model/runtime metadata;
- support timeouts if the underlying client/runtime supports them;
- document unsupported capabilities;
- provide tests with fake server/provider where possible;
- keep blocking initialization off UI dispatchers.

Adapters should not:

- decide global routing policy;
- persist prompts/results directly;
- silently call cloud from a local provider unless explicitly documented and represented in metadata;
- hide provider fallback from the route trace;
- throw raw provider exceptions without mapping them;
- store API keys or secrets in route traces.

## MVP adapters

### Fake/test providers

Why first:

- deterministic route tests;
- no model/API dependency;
- supports failure injection;
- makes policy behavior easy to review.

Fake providers are not enough for MVP proof; they complement the real local adapter.

### OpenAI-compatible HTTP

Why MVP:

- works against many cloud providers and gateways;
- can also work with local servers if configured as such;
- easy to test with mock HTTP;
- provides immediate cloud fallback demo.

Capabilities:

- text generation;
- chat;
- streaming;
- structured output depending on backend;
- token usage depending on backend.

Security requirement:

- API key handling is app-supplied. The adapter accepts keys/config but does not provide a cross-platform secret store in core. Docs must include Keychain/Keystore guidance and warn that public mobile clients should generally call an app backend for third-party cloud APIs.

### LiteRT-LM Android/JVM

Why MVP:

- exercises real local runtime behavior;
- Kotlin API is suitable for Android/JVM integration;
- streaming and engine lifecycle test the core contracts;
- keeps InferenceStore above the runtime layer.

MVP capabilities:

- text generation;
- streaming;
- offline/local execution;
- model-path availability;
- model/runtime metadata;
- stable local-runtime error mapping.

See `docs/technical/litert-lm-adapter.md`.

## Post-MVP adapter candidates

### Firebase AI Logic Android/iOS

Why useful:

- official hybrid path;
- good comparison point;
- useful for Gemini/Firebase teams.

Design question:

Should this adapter expose Firebase's hybrid mode as one provider, or expose on-device and cloud as separate providers?

Recommendation:

- expose separate logical providers when possible so InferenceStore can own route traces;
- optionally support a `FirebaseHybridProvider` convenience adapter that reports Firebase's own source metadata.

### Apple Foundation Models iOS

Why useful:

- native Apple integration;
- important for iOS local/platform model support.

Recommendation:

- keep common contract in Kotlin;
- implement platform provider in iOS source set or Swift-facing module;
- expose minimal bridging API for Swift-only model setup if required;
- do not let Apple-only APIs shape core semantics prematurely.

### Llamatik

Why useful:

- KMP-friendly local/remote runtime path;
- aligns with Kotlin-first audience.

Recommendation:

- adapter module may depend on Llamatik;
- core must not.

### Cactus

Why useful:

- already exposes KMP local/remote/local-first modes;
- useful for comparison and early adopter feedback.

Risk:

- overlaps the simple MVP headline.

Differentiation for InferenceStore:

- privacy gate independent of policy;
- validation-triggered repair;
- cache/artifact fingerprinting;
- route trace/testkit/golden semantics;
- provider-neutral architecture across Cactus, LiteRT-LM, Firebase, Apple, and cloud gateways.

### MLC LLM

Why useful:

- OpenAI-compatible engine across REST, Python, JS, iOS, Android;
- can be used through OpenAI-compatible adapter in some deployment shapes.

Recommendation:

- do not build native MLC adapter until users ask;
- document use through OpenAI-compatible local server first.

## Adapter module naming

```text
inferencestore-provider-openai-compatible
inferencestore-provider-litertlm-android
inferencestore-provider-litertlm-jvm
inferencestore-provider-firebase-android
inferencestore-provider-apple-foundationmodels-ios
inferencestore-provider-llamatik
inferencestore-provider-cactus
inferencestore-provider-mlc
```

## Capability mapping example

```kotlin
class OpenAICompatibleProvider(...) : InferenceProvider {
    override val id = ProviderId("openai-compatible")
    override val kind = ProviderKind.Cloud
    override val boundary = ProviderPrivacyBoundary.thirdPartyCloud(vendor = "app-configured")

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
    val boundary: ProviderPrivacyBoundary,
    val modelId: String?,
    val modelVersion: String?,
    val runtimeVersion: String?,
    val capabilities: Set<Capability>,
    val extra: Map<String, String> = emptyMap()
)
```

## API-key and secret handling

Core does not provide universal secret storage. Adapter docs must give safe integration guidance:

- API keys must not be logged, traced, or included in thrown errors.
- For mobile apps, prefer an app backend or token broker for third-party cloud APIs.
- Android recipes should use Keystore-backed storage or app-owned secure storage where appropriate.
- Apple recipes should use Keychain or app-owned secure storage where appropriate.
- Test/demo configs can use environment variables, but docs must label that as non-production guidance.

## Provider testing expectations

Each adapter should include:

- success test;
- streaming test;
- cancellation test;
- timeout test;
- availability test;
- unsupported capability test;
- error mapping test;
- privacy boundary test;
- redaction/monitoring test;
- no-main-thread-blocking test for local runtimes.

## Adapter maturity levels

### Experimental

- compiles;
- basic success path;
- known missing capabilities.

### Alpha

- documented capability mapping;
- tests for errors/cancellation;
- sample usage.

### Beta

- stable configuration API;
- production-minded docs;
- tested against real provider/runtime versions.

### Stable

- compatibility policy;
- version matrix;
- migration docs.
