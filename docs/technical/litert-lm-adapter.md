# LiteRT-LM adapter plan

Updated: 2026-06-13

## Decision

LiteRT-LM is the first real local adapter for the MVP vertical slice.

MVP scope:

- `inferencestore-provider-litertlm-android`
- `inferencestore-provider-litertlm-jvm` for CLI/sample validation where practical
- Android/JVM text-generation streaming path
- explicit model-path configuration
- no model download management in the adapter
- iOS Swift support treated as mobile-proof / experimental follow-up

## Why LiteRT-LM first

LiteRT-LM exercises the local-runtime problems that fake providers hide: model path validation, engine initialization, warmup latency, native resource lifecycle, backend selection, streaming through a real runtime, and local failure mapping.

It is also a better MVP learning vehicle than a pure platform hybrid SDK because InferenceStore owns the routing rather than delegating the headline behavior to Firebase or Apple.

## Module shape

```text
inferencestore-provider-litertlm-common
inferencestore-provider-litertlm-android
inferencestore-provider-litertlm-jvm
```

If common code is too thin, collapse into:

```text
inferencestore-provider-litertlm
```

with platform source sets.

## Configuration sketch

```kotlin
data class LiteRtLmProviderConfig(
    val providerId: ProviderId = ProviderId("litertlm-local"),
    val modelPath: String,
    val modelId: String,
    val backend: LiteRtLmBackend = LiteRtLmBackend.Auto,
    val cacheDir: String? = null,
    val initializationTimeout: Duration = 15.seconds,
    val defaultAttemptTimeout: Duration? = null,
    val blockingContext: CoroutineContext = Dispatchers.Default,
    val maxConcurrentConversations: Int = 1
)
```

`modelPath` points to an already-present `.litertlm` model. Downloading, updating, and pruning are Meeseeks/post-MVP concerns.

## Availability mapping

| Observation | ProviderAvailability |
|---|---|
| Model path missing | `Unavailable(MissingModel)` |
| Model path exists but cannot be read | `Unavailable(ModelUnreadable)` |
| Backend unsupported | `Unavailable(UnsupportedDevice)` or `CapabilityUnsupported` |
| Engine initializes within timeout | `Available` |
| Engine initialization times out | `Unavailable(InitializationTimeout)` |
| Native OOM during init | `Unavailable(InsufficientMemory)` |
| Unknown native initialization failure | `Unavailable(RuntimeInitializationFailed)` |

Availability probes must be bounded by `availabilityTimeout` or adapter config. A full model initialization may be cached/persisted in provider inventory, but it must not happen on the UI dispatcher.

## Capability mapping

MVP capabilities:

- `TextGeneration`
- `Chat` if conversation messages can be mapped safely
- `Streaming`
- `Offline`
- `LocalExecution`

Explicitly not MVP:

- tool calling
- multimodal input
- schema-constrained decoding
- embeddings
- model download management

If the underlying LiteRT-LM model/runtime supports more features, the adapter should report them only after tests exist.

## Streaming behavior

The adapter maps LiteRT-LM stream output to canonical events through provider events:

```text
LiteRT-LM Flow<Message/TextChunk>
  -> ProviderEvent.Token(text)
  -> InferenceEvent.Token(text)
```

The adapter should not parse structured output by default. Structured parsing belongs in `OutputSpec` and validators.

## Threading and resource lifecycle

Requirements:

- engine initialization runs on `blockingContext`;
- synchronous calls are not made from the collector context;
- native resources are closed on completion, failure, timeout, and cancellation;
- concurrency is limited until runtime thread-safety is proven;
- engine pooling is allowed but must be explicit and observable;
- warmup is optional and later belongs to Meeseeks lifecycle workers.

## Error mapping

| LiteRT-LM / runtime condition | InferenceStore category | Fallback default |
|---|---|---|
| Missing `.litertlm` file | `ProviderUnavailable` | Yes |
| Unsupported backend/device | `ProviderUnavailable` or `CapabilityUnsupported` | Yes |
| Initialization timeout | `Timeout` | Yes |
| Native OOM during initialization | `ProviderUnavailable` | Yes if another provider allowed |
| Native runtime error during generation | `TransientProviderError` if recoverable, else `PermanentProviderError` | Depends on metadata |
| Unsupported request capability | `CapabilityUnsupported` | Yes |
| Caller cancellation | `Cancelled` | No |

## Privacy boundary

LiteRT-LM local adapter declares:

```kotlin
ProviderPrivacyBoundary(
    execution = ProviderExecutionBoundary.LocalProcess,
    vendor = "Google AI Edge / LiteRT-LM",
    dataRetention = DataRetention.NoneByProvider,
    trainingUse = TrainingUse.NoneByProvider,
    region = null
)
```

No cloud permission is required for the local LiteRT-LM provider. If the adapter ever talks to a local server or remote service, that must be a separate provider boundary.

## Sample integration

`samples/notes-summary` should support two modes:

1. No model path supplied: fake local provider + fake/cloud provider for deterministic docs.
2. `INFERENCESTORE_LITERTLM_MODEL_PATH` supplied: LiteRT-LM local provider + OpenAI-compatible cloud provider.

This keeps the sample runnable without a large model while making the MVP prove real local behavior.

## Acceptance tests

- missing model path maps to `ProviderUnavailable` and falls back;
- real model path streams text tokens;
- initialization happens off main/test UI dispatcher;
- cancellation closes the active conversation;
- attempt timeout cancels/cleans up;
- unsupported capability maps to `CapabilityUnsupported`;
- route trace includes provider ID, model ID, backend, and local privacy boundary;
- no raw prompt/output appears in monitor events by default.

## Deferred

- iOS Swift adapter;
- Apple Foundation Models adapter;
- Firebase AI Logic adapter;
- model download management;
- model warmup worker;
- benchmark harness;
- multimodal and tool support.
