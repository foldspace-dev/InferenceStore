# Quickstart

From install to a first policy-routed inference request. Every snippet below is
exercised by the runnable [`samples/notes-summary`](../samples/notes-summary) module
(`NoteSummarizer`, `DemoProviders`, `Main`, and `NoteSummarizerTest`), so it compiles
and runs today:

```bash
./gradlew :samples:notes-summary:run
```

## 1. Add the dependency

> Publishing to Maven Central lands with OSS-8 (release automation). Until then, build
> from source or use the in-repo `:inferencestore-*` modules (as the sample does). The
> coordinates below show the shape once published — group `dev.mattramotar.inferencestore`,
> current version `0.1.0-dev`.

`gradle/libs.versions.toml` (version catalog):

```toml
[versions]
inferencestore = "0.1.0-dev"

[libraries]
inferencestore-core = { module = "dev.mattramotar.inferencestore:inferencestore-core", version.ref = "inferencestore" }
inferencestore-testkit = { module = "dev.mattramotar.inferencestore:inferencestore-testkit", version.ref = "inferencestore" }
inferencestore-provider-openai = { module = "dev.mattramotar.inferencestore:inferencestore-provider-openai-compatible", version.ref = "inferencestore" }
inferencestore-provider-litertlm = { module = "dev.mattramotar.inferencestore:inferencestore-provider-litertlm-android", version.ref = "inferencestore" }
```

Module `build.gradle.kts` (Kotlin DSL):

```kotlin
dependencies {
    implementation(libs.inferencestore.core)
    // Cloud + on-device adapters, as needed:
    implementation(libs.inferencestore.provider.openai)
    implementation(libs.inferencestore.provider.litertlm)
    // The testkit's fake providers let you build and test routing with no model/API:
    testImplementation(libs.inferencestore.testkit)
}
```

## 2. Local-first, cloud fallback (fake providers)

Build a store from providers and a policy. The testkit's `fakeProvider` simulates a
provider with no model or network — perfect for wiring and tests.

```kotlin
val onDevice = fakeProvider("on-device", ProviderKind.Local) {
    complete("Local summary: ship caching, draft the RFC, demo Friday.")
}
val cloud = fakeProvider("cloud", ProviderKind.Cloud, ProviderPrivacyBoundary.thirdPartyCloud("acme")) {
    complete("Cloud summary.")
}

val store = InferenceStore.build {
    provider(onDevice)
    provider(cloud)
    policy = Policies.preferLocalThenCloud()
}

val result = store.generate(
    InferenceRequest.text(
        key = InferenceKey("notes.summary", "demo"),
        input = "Summarize this note in one line:\n$note",
        privacy = PrivacyPolicy.publicData(),
        validator = OutputValidators.nonBlankText,
    ),
)
println(result.output)
```

Make the local provider unavailable (`availability = ProviderAvailability.Unavailable(UnavailableReason.ModelMissing)`)
and the same request transparently falls back to `cloud`.

## 3. See the route trace

Every result (and failure) carries a redacted `RouteTrace` — what ran, what was
rejected and why, and any fallback reasons:

```kotlin
val trace = result.trace
println("final=${trace?.finalProvider} status=${trace?.finalStatus}")
trace?.attempts?.forEach { println("  attempt ${it.providerId} (${it.providerKind}) -> ${it.outcome}") }
trace?.rejectedProviders?.forEach { println("  rejected ${it.providerId} -> ${it.reason}") }
```

In the fallback scenario this prints `rejected on-device -> ProviderUnavailable` and
`final=cloud`.

## 4. Streaming

`generate()` returns the terminal result; `stream()` is the cold, main-safe flow of
events (tokens, validation, fallback, completion):

```kotlin
store.stream(request).collect { event ->
    when (event) {
        is InferenceEvent.Token -> print(event.text)
        is InferenceEvent.Done -> println("\n${event.result.trace?.finalProvider}")
        is InferenceEvent.Failed -> println("failed: ${event.error.category}")
        else -> {}
    }
}
```

## 5. Privacy: local-only

`PrivacyPolicy.Default` is strict — `Personal` class, cloud denied. The privacy gate
runs **before** any provider work, so a cloud provider is refused outright and never
invoked, no matter the policy:

```kotlin
val result = store.generate(request.copy(privacy = PrivacyPolicy.Default))
// Cloud is cloud-like, so the gate rejects it: trace shows `rejected cloud -> PolicyViolation`.
```

This is verifiable. With the cloud provider as the only fallback and local unavailable,
a zero invocation count proves the gate refused cloud (the request fails rather than
leaking) — see `NoteSummarizerTest.privacyGate_blocksCloud_evenWhenLocalUnavailable`:

```kotlin
assertFailsWith<InferenceException> { store.generate(request.copy(privacy = PrivacyPolicy.Default)) }
assertEquals(0, cloud.invocations)
```

## 6. On-device with LiteRT-LM

The LiteRT-LM adapter is runtime-injected: you provide the native Google AI Edge
binding via `LiteRtLmRuntime` (the sample ships a `DemoLiteRtLmRuntime` stand-in so it
runs without the native library). Point it at a model and route to it:

```kotlin
val provider = LiteRtLmProvider(
    config = LiteRtLmProviderConfig(modelPath = modelPath, modelId = "gemma", backend = LiteRtLmBackend.Auto),
    runtime = myLiteRtLmRuntime, // native binding (or DemoLiteRtLmRuntime in the sample)
)
val store = InferenceStore.single(provider)
```

Run the sample's LiteRT-LM path by supplying a model path:

```bash
INFERENCESTORE_LITERTLM_MODEL=/path/to/model.task ./gradlew :samples:notes-summary:run
```

## 7. Cloud with an OpenAI-compatible endpoint

The adapter is engine-agnostic — you supply a configured Ktor `HttpClient` (swap in
`ktor-client-cio`/`okhttp` for real calls; the sample uses a mock engine to stay offline):

```kotlin
val provider = OpenAiCompatibleProvider(
    config = OpenAiConfig(baseUrl = "https://api.openai.com/v1", model = "gpt-4o-mini", apiKey = { System.getenv("OPENAI_API_KEY") }),
    httpClient = httpClient,
)
```

## Next steps

- Routing presets and fallback: `docs/technical/routing-policy.md`
- Privacy model: `docs/technical/privacy-model.md`
- Structured output (JSON/schema) and repair: `docs/rfcs/RFC-0006-structured-output.md`
- Caching and dedupe: `docs/technical/storage-model.md`
