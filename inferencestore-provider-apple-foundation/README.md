# inferencestore-provider-apple-foundation

InferenceStore's first **iOS local/platform adapter**, backed by Apple's
[`FoundationModels`](https://developer.apple.com/documentation/foundationmodels)
framework (the on-device model behind Apple Intelligence). Decided in
[ADR-0006](../docs/adr/0006-first-ios-local-adapter.md); symmetric with Android's
LiteRT-LM adapter (OSS-29).

> **Maturity: experimental.** The framework is new and this adapter is unvalidated on a
> device. Pin to an experimental tier and expect API churn.

## Design: runtime injection (no SDK at build time)

The adapter never links `FoundationModels` directly. It depends on an injected
`AppleFoundationRuntime` interface, so all logic — availability gating, streaming,
guided-generation (structured output), error mapping, and the on-device privacy
boundary — is real and unit-tested with a fake runtime, with no SDK and no device.

```kotlin
public interface AppleFoundationRuntime {
    suspend fun availability(): AppleModelAvailability
    fun generate(request: AppleGenerationRequest): Flow<String>
}

val provider = AppleFoundationModelsProvider(AppleFoundationConfig(), runtime = myRuntime)
```

`AppleFoundationModelsProvider` reports `ProviderKind.Platform` with a `platform("apple")`
privacy boundary (on-device, **no network and no API key**), and capabilities
`TextGeneration` / `Chat` / `Streaming` / `StructuredOutput` / `Offline`.

## Swift shim interop

`FoundationModels` is Swift-only, so an integrator implements `AppleFoundationRuntime` on
the iOS target by bridging to a thin Swift shim (via Kotlin/Native cinterop or a
SKIE-style bridge). The shim wraps the framework:

- **`availability()`** → read `SystemLanguageModel.default.availability` and map:
  `.available` → `Available`; `.unavailable(.deviceNotEligible)` → `DeviceNotEligible`;
  `.unavailable(.appleIntelligenceNotEnabled)` → `AppleIntelligenceNotEnabled`;
  `.unavailable(.modelNotReady)` → `ModelNotReady`; an OS below 26 → `OsTooOld`.
  The provider maps these to `UnavailableReason` so ineligible devices report
  `Unavailable` and routing falls back to cloud.
- **`generate(request)`** → open a `LanguageModelSession` and stream tokens via
  `streamResponse(to:)`. When `request.structured` is true, use **guided generation**
  (an `@Generable` type / `respond(to:generating:)`) and stream the JSON for the schema
  named by `request.schemaName`; the provider parses it into the typed output.
- Map framework errors to `AppleFoundationException(category, …)` with a canonical
  `ErrorCategory`; never put prompts, outputs, or secrets in the message.

> Apple's separate **Private Cloud Compute** path (`PrivateCloudComputeLanguageModel`)
> is *not* this provider. If an app opts into it, model it as a distinct cloud-like
> provider with its own privacy boundary so the privacy gate applies.

## Targets

Pure-Kotlin common code published for `jvm`, `iosX64`, `iosArm64`,
`iosSimulatorArm64` (+ Android when an SDK is configured). The Swift shim lives in the
integrator's iOS app/module; this library carries no SDK dependency.

## Status

Prototype for OSS-41. Validate `availability()` mapping and streaming on a real
Apple-Intelligence device before promoting beyond experimental.
