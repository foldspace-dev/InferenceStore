# inferencestore-provider-firebase-android

A **prototype** InferenceStore adapter over [Firebase AI Logic](https://firebase.google.com/docs/ai-logic),
which routes between on-device (Gemini Nano) and cloud (Gemini) itself. **Experimental**
maturity — runtime-injected and unit-tested against a fake; not validated against the SDK.

## Design decision: hybrid vs. separate providers

The issue (OSS-32) asks whether to model Firebase as **separate** on-device/cloud
providers or a **single hybrid** convenience provider.

**This prototype uses a single hybrid `FirebaseAiLogicProvider`**, because Firebase owns
the on-device/cloud routing decision; splitting it into two InferenceStore providers
would duplicate that logic and fight the SDK. The provider reports the source Firebase
actually used per request (see *Route trace* below).

The trade-off is privacy modeling. The alternative — **separate providers** — gives the
privacy gate clean per-provider semantics (a local-only provider with a `LocalProcess`
boundary; a cloud provider with a `ThirdPartyCloud` boundary). Choose that shape when an
app needs a hard local-only guarantee *and* must still reach Firebase cloud under a
different policy. For the common "use the best available, prefer on-device" case, the
hybrid is simpler.

## Privacy boundary behavior

The hybrid declares a **cloud-like** boundary (`platformHybrid`). Because it *may* use
cloud, the privacy gate treats it conservatively:

- Under `PrivacyPolicy.Default` / `localOnly()` (cloud denied), the hybrid is **refused
  before any call** — even if this particular request would have stayed on-device. The
  boundary reflects the worst case, not the per-request source.
- Under a cloud-allowing policy, it routes in the local tier (Firebase prefers
  on-device) and Firebase decides the source.

If you need a request that can *only* run on-device, use a dedicated on-device provider
rather than the hybrid — the gate cannot grant a "maybe-cloud" provider a local-only
exemption.

## Capability limits

`TextGeneration`, `Chat`, `Streaming`, `StructuredOutput` (Gemini guided/JSON output).
**Not** `Offline` — the cloud path requires network, so the hybrid never advertises
offline capability. This prototype emits text output only.

## Route trace records the source

`availability()`/error mapping are real. Each generation reports whether Firebase served
it on-device or from cloud via the completion metadata's `modelId` (e.g.
`gemini (OnDevice)` / `gemini (Cloud)`) and `extra["firebase.source"]`, so the
`RouteTrace` attempt records which path was used — no core API change.

## Usage

```kotlin
val provider = FirebaseAiLogicProvider(
    config = FirebaseAiConfig(modelId = "gemini-2.5-flash", allowCloud = true),
    runtime = myFirebaseAiRuntime, // implement FirebaseAiRuntime against the Firebase SDK
)
```

The adapter is SDK-agnostic: implement `FirebaseAiRuntime` (a bounded `probe` + a
`generate` that streams `FirebaseAiChunk`s tagged with their source) against the native
library. Run SDK calls off the caller's dispatcher and honor cancellation.
