# Writing a provider adapter

How to wrap an inference runtime or cloud endpoint as an InferenceStore
`InferenceProvider`. The contract is defined in
[`provider-adapters.md`](../technical/provider-adapters.md); this guide is the
practical how-to. Two shipped adapters are worked references:
[`inferencestore-provider-openai-compatible`](../../inferencestore-provider-openai-compatible)
(cloud, Ktor) and
[`inferencestore-provider-litertlm-android`](../../inferencestore-provider-litertlm-android)
(on-device, runtime-injected).

## Responsibilities vs. non-responsibilities

An adapter is a thin, honest translation layer for **one** runtime/endpoint.

**It owns:**
- reporting **availability** (is the model/endpoint usable right now) and
  **capabilities** (what this model can do for a given request);
- executing a single attempt as a **cold stream** of `ProviderEvent`s;
- mapping raw failures to a stable `ErrorCategory` (+ `ErrorSource` when known);
- declaring an accurate **privacy boundary**;
- owning its **threading** and releasing native/network resources on completion,
  failure, and cancellation.

**It does NOT own** (the engine does):
- routing, fallback, retries, or timeouts across providers;
- the privacy *gate* (the engine refuses disallowed providers before calling them —
  the adapter only *describes* its boundary);
- caching, dedupe, or validation;
- request-level deadlines (the engine bounds attempts).

If you find yourself adding retry loops, provider selection, or cache lookups in an
adapter, that logic belongs in the engine.

## The contract

```kotlin
interface InferenceProvider {
    val id: ProviderId
    val kind: ProviderKind          // Local, Cloud, Platform, Remote, Test
    val boundary: ProviderPrivacyBoundary

    suspend fun availability(context: InferenceContext): ProviderAvailability
    suspend fun capabilities(request: InferenceRequest<*>, context: InferenceContext): CapabilityReport
    fun <Output : Any> stream(request: ProviderRequest<Output>, context: InferenceContext): Flow<ProviderEvent<Output>>
}
```

## A minimal provider

```kotlin
class EchoProvider : InferenceProvider {
    override val id = ProviderId("echo")
    override val kind = ProviderKind.Local
    override val boundary = ProviderPrivacyBoundary.localDevice()

    private val supported = setOf(Capability.TextGeneration, Capability.Streaming)

    override suspend fun availability(context: InferenceContext): ProviderAvailability =
        ProviderAvailability.Available

    override suspend fun capabilities(request: InferenceRequest<*>, context: InferenceContext): CapabilityReport =
        CapabilityReport(
            supported = request.requiredCapabilities().all { it in supported },
            capabilities = supported,
        )

    override fun <Output : Any> stream(
        request: ProviderRequest<Output>,
        context: InferenceContext,
    ): Flow<ProviderEvent<Output>> = flow {
        val metadata = ProviderMetadata(id, kind, boundary, modelId = "echo-1")
        emit(ProviderEvent.Started(metadata))

        val prompt = when (val input = request.input) {
            is InferenceInput.Text -> input.value
            is InferenceInput.Messages -> input.messages.joinToString("\n") { it.content }
        }
        val text = "echo: $prompt"
        emit(ProviderEvent.Token(text)) // stream deltas as Token events

        @Suppress("UNCHECKED_CAST")
        emit(ProviderEvent.Completed(text as Output, rawText = text, metadata = metadata))
    }
}
```

The `as Output` cast is sound here only because `capabilities()` reports `Streaming` +
`TextGeneration` and nothing else, so a `Json`/`Custom` request (which requires
`StructuredOutput`) is reported unsupported and never routed to this provider. A
structured-output adapter must instead **parse** `rawText` against `request.output`
(`OutputSpec.Json`/`Custom`) and emit `ParsingFailed` on failure — see the OpenAI
adapter's `parseOutput`.

### Event order

Emit exactly one terminal event per attempt:

`Started` → (`Token`* and/or `Partial`*) → (`Completed` **or** `Failed`).

`Token` carries text deltas; `Partial` carries an interim typed value; `Completed`
carries the final typed `output`, the `rawText`, optional `Usage`, and metadata.

## Availability and capabilities

- `availability()` is a **bounded readiness probe** — never run a full generation in
  it. Respect `context.timeout.availabilityTimeout`; treat a timeout as
  `Unavailable`. Return `ProviderAvailability.Unavailable(reason)` with the right
  `UnavailableReason` (`ModelMissing`, `NetworkUnavailable`, `Unsupported`,
  `Disabled`, `Unknown`).
- `capabilities()` reports whether **this request** is serviceable. Build the
  supported `Capability` set (`TextGeneration`, `Chat`, `Streaming`,
  `StructuredOutput`, `JsonSchema`, `Embeddings`, `ImageInput`, `AudioInput`,
  `ToolCalling`, `Offline`) and check `request.requiredCapabilities().all { it in set }`.
- Both may be **probed defensively** by the engine: throwing is treated as
  unavailable/incapable, so you may throw, but returning a typed result is clearer.

## Threading / dispatcher requirements

See [`threading-dispatchers.md`](../technical/threading-dispatchers.md).

- `stream()` returns a **cold** flow — do no work until collection.
- The **adapter owns its threading**. Move blocking init and synchronous native/CPU
  calls off the collector (e.g. `flowOn(Dispatchers.Default)` or a dedicated thread)
  so a UI scope can collect without blocking. Core is dispatcher-neutral and will not
  do this for you.
- **Honor cancellation.** When collection is cancelled, stop work and release the
  engine/connection/native handles. Use `try/finally` (or `Flow` `onCompletion`)
  around native resources. Never swallow `CancellationException`.
- Do not block in `availability()`/`capabilities()` beyond their bounded budgets.

## Error mapping checklist

Map every raw failure to a `ProviderError(category, source?, retryAfter?, cause?)`.
Emit it as `ProviderEvent.Failed` (or throw — the engine maps an escaped throwable to
`Unknown`). Choose the `ErrorCategory`:

- [ ] **Network/transport blip, 5xx, connection reset** → `TransientProviderError`
- [ ] **Rate limited / 429** → `RateLimited` (set `retryAfter` from the header if present)
- [ ] **Auth/quota/permanent 4xx, content filter, empty result** → `PermanentProviderError`
- [ ] **Malformed request you built / 400 invalid** → `PermanentProviderError` with
      `source = ErrorSource.RequestInvalid` (terminal — won't fall back)
- [ ] **Output didn't parse to the requested schema** → `ParsingFailed`
- [ ] **Your own attempt timed out** → `Timeout` with `source = ErrorSource.AttemptTimeout`
- [ ] **Model/endpoint not usable** → surface via `availability()`, not a failed stream
- [ ] **Unknown/unclassified** → `Unknown` (terminal)

Set `source` whenever it changes disposition: an `AttemptTimeout` may fall back while
a `RequestDeadlineExceeded` is terminal; a `ProviderSpecific` permanent error may fall
back while a `RequestInvalid` one is terminal (`error-fallback-mapping.md`). The engine
owns the category→fallback decision; your job is an accurate category + source.

**Never** put secrets in `ProviderError.message`/`cause` paths that get logged.
`ProviderError.toString()` is already redacted (category/source/retryAfter only), but
`message` may carry a raw provider body — keep it out of logs/traces.

## Privacy boundary requirements

- Declare an honest `ProviderPrivacyBoundary`: `localDevice()`, `platform(vendor)`,
  `platformHybrid(vendor)`, `appBackend(vendor)`, or `thirdPartyCloud(vendor)`. Its
  `execution` drives `isCloudLike`, which the privacy gate uses to refuse cloud-bound
  requests **before** your `stream()` is ever called.
- Mis-declaring a cloud endpoint as local would defeat the privacy guarantee — the
  boundary is a security contract, not cosmetic.
- API keys are **app-supplied** (e.g. `OpenAiConfig.apiKey: suspend () -> String?`);
  an adapter never embeds a secret store and never logs/traces/places keys in errors.
- Honor the request's privacy where it constrains transport (e.g. don't attach
  telemetry the policy forbids).

## Testing

Use `inferencestore-testkit` and an engine-agnostic seam (inject the `HttpClient` /
native runtime) so tests run with no network or model:

- assert event order (`Started` → tokens → `Completed`/`Failed`);
- assert each raw failure maps to the expected `ErrorCategory`/`ErrorSource`;
- assert `availability()`/`capabilities()` for ready, unavailable, and
  unsupported-capability cases;
- assert cancellation releases resources;
- drive routing/fallback with the testkit `fakeProvider` alongside your adapter.

The OpenAI adapter's `MockEngine` tests and the LiteRT-LM adapter's fake-runtime tests
are templates.

## Maturity levels

Label an adapter so adopters can judge risk:

- **Experimental** — compiles and is unit-tested against a fake seam, but not
  validated against the live runtime (e.g. LiteRT-LM, pending the native binding).
- **Beta** — exercised against the real runtime/endpoint; API may still shift.
- **Stable** — validated, versioned, and supported.

### Known adapter candidates

Cloud (OpenAI-compatible gateways): OpenAI, Azure OpenAI, Together, Groq, Fireworks,
local OpenAI-compatible servers (llama.cpp, vLLM, LM Studio, Ollama). On-device:
LiteRT-LM (Google AI Edge), ExecuTorch, MLC LLM, llama.cpp bindings, Apple Foundation
Models, Android AICore / Gemini Nano. Platform: Firebase AI Logic. Contributions
welcome — start from the contract above and a worked reference adapter.
