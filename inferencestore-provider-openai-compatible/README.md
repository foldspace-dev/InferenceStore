# inferencestore-provider-openai-compatible

An `InferenceProvider` for any OpenAI-compatible `/chat/completions` endpoint
(hosted gateways, or a local server that speaks the same API).

## Usage

The adapter is engine-agnostic: you supply a configured Ktor `HttpClient` with the
engine for your platform (CIO/OkHttp on JVM/Android, Darwin on iOS).

```kotlin
val provider = OpenAiCompatibleProvider(
    config = OpenAiConfig(
        baseUrl = "https://api.openai.com/v1",
        model = "gpt-4o-mini",
        apiKey = { secureStore.openAiKey() }, // suspend; resolved per request
    ),
    httpClient = HttpClient(/* your engine */),
)

val store = InferenceStore.build {
    provider(localProvider)
    provider(provider)
    policy = Policies.preferLocalThenCloud()
}
```

## API keys and secrets

API keys are **app-supplied** via `OpenAiConfig.apiKey`. This module provides no
cross-platform secret store. Keys are sent as a `Bearer` header and are **never**
logged, traced, or placed in errors (the raw response body is never read into an
error message either).

- **Public mobile clients should generally not embed third-party cloud API keys.**
  Prefer calling an **app backend / token broker** that holds the key, and point
  `baseUrl` at your backend.
- **Android**: store keys with the Keystore-backed mechanisms or app-owned secure
  storage; do not ship keys in the APK.
- **iOS**: use the Keychain or app-owned secure storage.
- **Demos/tests** may read keys from environment variables — this is explicitly
  non-production guidance.

The full security model is in `docs/technical/security-privacy.md` (and the guide
landing in OSS-28).

## MVP limitations

- The response body is read as text and parsed for SSE chunks. Socket-level live
  streaming via the Ktor SSE plugin is a follow-up; the engine still surfaces
  tokens as `InferenceEvent`s.
