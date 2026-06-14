# Sample: private note summarization

A runnable demo of InferenceStore's value: route a note-summarization request across
on-device and cloud providers, with privacy enforced and the routing decision visible.

Run it (no model or API key required — uses fake providers + a mock HTTP engine):

```bash
./gradlew :samples:notes-summary:run
```

## What it shows

| Scenario | Demonstrates |
| --- | --- |
| **Local-first** | Local model available → it serves the request; cloud untouched. |
| **Cloud fallback** | Local unavailable → routing falls back to cloud (trace shows `on-device` rejected `ProviderUnavailable`). |
| **Private (local-only)** | `PrivacyPolicy.Default` denies cloud → cloud is refused by the gate and **invoked zero times**; local serves. |
| **LiteRT-LM on-device** | The `LiteRtLmProvider` path via a bundled demo runtime (env-gated, see below). |
| **OpenAI-compatible cloud** | The real OpenAI-compatible adapter parsing a streamed completion, driven by a mock HTTP engine. |

Every scenario prints the `RouteTrace` — final provider, per-attempt outcomes, rejected
providers and why, and any fallback reasons.

## LiteRT-LM path

LiteRT-LM needs Google's native AI Edge runtime, which isn't bundled. The sample ships a
`DemoLiteRtLmRuntime` stand-in so the adapter path is exercised offline — a real integrator
replaces that one class with the native binding; the adapter and routing code are identical.
Supply a model path to run it:

```bash
INFERENCESTORE_LITERTLM_MODEL=/path/to/model.task ./gradlew :samples:notes-summary:run
```

## Going live

- Swap the OpenAI-compatible adapter's mock `HttpClient` for a real engine
  (`ktor-client-cio` / `okhttp`) and provide a real `baseUrl` + `apiKey`.
- Replace `DemoLiteRtLmRuntime` with the native LiteRT-LM binding.

The verifiable behavior (fallback, privacy zero-invocation, validation, both adapter paths)
is covered by `NoteSummarizerTest`.
