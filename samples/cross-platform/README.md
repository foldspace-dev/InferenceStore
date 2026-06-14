# Sample: cross-platform shared inference

Demonstrates the KMP payoff: **one piece of common code** defines the request, policy,
and summarize logic, while each platform supplies its own provider. The Android and iOS
apps share `CrossPlatformSummarizer` and differ only in the `platformProvider()`
`actual` — so the same feature code routes to LiteRT-LM on Android, Apple Foundation
Models on iOS, etc., and the route trace records which one ran.

## What's shared vs. platform-specific

| | Location | Contents |
| --- | --- | --- |
| **Shared** | `commonMain` | `CrossPlatformSummarizer` (request + policy + `summarize`), the validator, and `DemoTextProvider` |
| **Per platform** | `jvmMain` / `iosMain` / `androidMain` | the `platformProvider()` `actual` — the single seam |

The providers here are runnable **demo stand-ins** (no model/SDK) so the module builds
and tests everywhere. Production swaps the one `actual`:

- **Android** (`androidMain`) → `LiteRtLmProvider` (OSS-29).
- **iOS** (`iosMain`) → the Apple Foundation Models adapter (ADR-0006 / OSS-41).
- **JVM/desktop** (`jvmMain`) → any JVM-capable adapter (e.g. OpenAI-compatible).

## Using it from an app

The shared module is consumed by thin platform app shells (not built in this repo —
they need the Android SDK / an Xcode project):

```kotlin
// Android (Activity/Compose) and iOS (SwiftUI via the KMP framework) both call:
val result = CrossPlatformSummarizer.summarize(noteText)
println("${result.output}  via ${result.trace?.finalProvider}")
```

To wire a real provider, replace the platform `actual`:

```kotlin
// androidMain
public actual fun platformProvider(): InferenceProvider =
    LiteRtLmProvider(LiteRtLmProviderConfig(modelPath, modelId = "gemma"), nativeRuntime)
```

## Platform differences

- **Providers**: each platform's local stack differs (LiteRT-LM vs Apple Foundation
  Models); only the `actual` changes, never the feature code.
- **Privacy**: the shared request uses `PrivacyPolicy.Default` (cloud denied), so a
  cloud-capable provider would be refused by the gate on every platform identically.
- **Trace**: `result.trace?.finalProvider` reflects the actual platform provider
  (`android-litertlm`, `ios-foundation-models`, `jvm-local`, …), so observability is
  uniform across platforms.

Verified by `CrossPlatformSummarizerTest` (common + JVM + iOS).
