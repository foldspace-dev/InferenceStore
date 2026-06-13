# API design

Updated: 2026-06-13

## Design goals

- KMP common API.
- Coroutine and Flow native.
- Streaming-first with non-streaming convenience.
- Strong enough types for policy and tests.
- Adapter-friendly.
- Store-inspired but not Store-copying.
- Privacy-safe defaults.
- Real local-runtime behavior represented in core contracts without making core a runtime.

## Package sketch

```text
dev.mattramotar.inferencestore
dev.mattramotar.inferencestore.policy
dev.mattramotar.inferencestore.provider
dev.mattramotar.inferencestore.cache
dev.mattramotar.inferencestore.validation
dev.mattramotar.inferencestore.monitoring
dev.mattramotar.inferencestore.testkit
```

## Core API

The root `InferenceStore` is not generic. Output type travels with each request.

```kotlin
interface InferenceStore {
    fun <Output : Any> stream(
        request: InferenceRequest<Output>
    ): Flow<InferenceEvent<Output>>

    suspend fun <Output : Any> generate(
        request: InferenceRequest<Output>
    ): InferenceResult<Output>

    suspend fun clear(key: InferenceKey)
    suspend fun clearAll()
}
```

## Request

```kotlin
data class InferenceRequest<Output : Any>(
    val key: InferenceKey,
    val input: InferenceInput,
    val output: OutputSpec<Output>,
    val policy: InferencePolicy? = null,
    val privacy: PrivacyPolicy = PrivacyPolicy.Default,
    val cache: CachePolicy = CachePolicy.Default,
    val timeout: TimeoutPolicy = TimeoutPolicy.Default,
    val retry: RetryPolicy = RetryPolicy.Default,
    val prompt: PromptSpec? = null,
    val metadata: Map<String, String> = emptyMap()
)
```

`InferenceKey` is required for durable cache/dedupe/artifact/trace semantics. Convenience APIs may create ephemeral keys only when cache and dedupe are disabled.

## Key

```kotlin
data class InferenceKey(
    val namespace: String,
    val id: String,
    val version: String? = null
)
```

Examples:

```kotlin
InferenceKey("notes.summary", note.id, version = "v1")
InferenceKey("tasks.extract", message.id, version = "prompt-2026-06")
```

## Input

```kotlin
sealed interface InferenceInput {
    data class Text(val value: String) : InferenceInput
    data class Messages(val messages: List<ChatMessage>) : InferenceInput
    data class Structured<T : Any>(
        val value: T,
        val serializerName: String
    ) : InferenceInput
}
```

MVP supports `Text` and `Messages`; multimodal inputs are post-MVP.

## Output spec

```kotlin
sealed interface OutputSpec<Output : Any> {
    data object Text : OutputSpec<String>

    data class Json<Output : Any>(
        val serializer: KSerializer<Output>,
        val schema: JsonSchema? = null,
        val validators: List<OutputValidator<Output>> = emptyList()
    ) : OutputSpec<Output>

    data class Custom<Output : Any>(
        val parser: OutputParser<Output>,
        val validators: List<OutputValidator<Output>> = emptyList()
    ) : OutputSpec<Output>
}
```

MVP validation is final-output only.

## Events

The canonical event taxonomy lives in `docs/technical/event-model.md`. API snippets in other docs should stay aligned with that file.

```kotlin
sealed interface InferenceEvent<out Output : Any> {
    data class Started(val requestId: RequestId, val key: InferenceKey) : InferenceEvent<Nothing>
    data class CacheChecked(val requestId: RequestId, val outcome: CacheOutcome) : InferenceEvent<Nothing>
    data class ProvidersEvaluated(val requestId: RequestId, val candidates: List<ProviderCandidateSummary>) : InferenceEvent<Nothing>
    data class RouteSelected(val requestId: RequestId, val route: InferenceRouteSummary) : InferenceEvent<Nothing>
    data class ProviderAttemptStarted(val requestId: RequestId, val attempt: ProviderAttemptSummary) : InferenceEvent<Nothing>
    data class Token(val requestId: RequestId, val text: String) : InferenceEvent<Nothing>
    data class Partial<Output : Any>(val requestId: RequestId, val value: Output) : InferenceEvent<Output>
    data class ValidationCompleted(val requestId: RequestId, val result: ValidationResult) : InferenceEvent<Nothing>
    data class ProviderAttemptCompleted(val requestId: RequestId, val attempt: ProviderAttemptSummary) : InferenceEvent<Nothing>
    data class FallbackStarted(val requestId: RequestId, val reason: FallbackReason, val next: ProviderId?) : InferenceEvent<Nothing>
    data class ArtifactStored(val requestId: RequestId, val outcome: ArtifactWriteOutcome) : InferenceEvent<Nothing>
    data class Done<Output : Any>(val requestId: RequestId, val result: InferenceResult<Output>) : InferenceEvent<Output>
    data class Failed(val requestId: RequestId, val error: InferenceError, val trace: RouteTrace) : InferenceEvent<Nothing>
}
```

## Result

```kotlin
data class InferenceResult<Output : Any>(
    val key: InferenceKey,
    val output: Output,
    val rawText: String?,
    val trace: RouteTrace,
    val cache: CacheOutcome?,
    val validation: ValidationResult?,
    val metadata: Map<String, String> = emptyMap()
)
```

`rawText` should not be persisted or emitted through telemetry unless privacy and cache policies explicitly allow it.

## Provider

```kotlin
interface InferenceProvider {
    val id: ProviderId
    val kind: ProviderKind
    val boundary: ProviderPrivacyBoundary

    suspend fun availability(
        context: InferenceContext
    ): ProviderAvailability

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

## Provider IDs and kinds

```kotlin
@JvmInline
value class ProviderId(val value: String)

enum class ProviderKind {
    Local,
    Cloud,
    Platform,
    Remote,
    Test
}
```

`Platform` providers must still declare whether they are on-device, cloud-backed, or hybrid through `ProviderPrivacyBoundary`.

## Availability

```kotlin
sealed interface ProviderAvailability {
    data object Available : ProviderAvailability
    data class Unavailable(val reason: AvailabilityReason) : ProviderAvailability
    data class Unknown(val reason: String? = null) : ProviderAvailability
}

sealed interface AvailabilityReason {
    data object MissingModel : AvailabilityReason
    data object ModelUnreadable : AvailabilityReason
    data object DownloadRequired : AvailabilityReason
    data object NetworkUnavailable : AvailabilityReason
    data object UnsupportedDevice : AvailabilityReason
    data object InsufficientMemory : AvailabilityReason
    data object InitializationTimeout : AvailabilityReason
    data object DisabledByConfig : AvailabilityReason
    data object RateLimited : AvailabilityReason
    data class Other(val message: String) : AvailabilityReason
}
```

## Capability report

```kotlin
data class CapabilityReport(
    val supported: Boolean,
    val capabilities: Set<Capability>,
    val unsupported: Set<Capability> = emptySet(),
    val limits: ProviderLimits = ProviderLimits()
)
```

## Capabilities

```kotlin
sealed interface Capability {
    data object TextGeneration : Capability
    data object Chat : Capability
    data object Streaming : Capability
    data object StructuredOutput : Capability
    data object JsonSchema : Capability
    data object Embeddings : Capability
    data object ImageInput : Capability
    data object AudioInput : Capability
    data object ToolCalling : Capability
    data object Offline : Capability
    data object LocalExecution : Capability
}
```

## Policy

```kotlin
fun interface InferencePolicy {
    suspend fun plan(
        request: InferenceRequest<*>,
        candidates: List<ProviderCandidate>,
        context: InferenceContext
    ): InferenceRoute
}
```

## Route

```kotlin
data class InferenceRoute(
    val attempts: List<RouteAttempt>,
    val maxAttempts: Int = attempts.size,
    val fallback: FallbackPolicy = FallbackPolicy.Default,
    val timeout: TimeoutPolicy = TimeoutPolicy.Default,
    val retry: RetryPolicy = RetryPolicy.Default,
    val cache: CachePolicy = CachePolicy.Default
)

data class RouteAttempt(
    val providerId: ProviderId,
    val reason: RouteReason,
    val timeout: TimeoutPolicy? = null
)
```

## Built-in policies

Five MVP presets:

```kotlin
object Policies {
    fun localOnly(): InferencePolicy
    fun cloudOnly(): InferencePolicy
    fun preferLocalThenCloud(): InferencePolicy
    fun preferCloudThenLocal(): InferencePolicy
    fun validateLocalThenCloudRepair(): InferencePolicy
}
```

No separate `privacyFirst` preset. Privacy behavior comes from `PrivacyPolicy` and the execution controller.

## Validator

```kotlin
fun interface OutputValidator<Output : Any> {
    suspend fun validate(
        output: Output,
        context: ValidationContext
    ): ValidationResult
}
```

## Monitor

```kotlin
interface InferenceMonitor {
    fun onEvent(event: MonitorEvent)
}
```

Monitor events are redacted projections of canonical stream events. They must not introduce separate lifecycle semantics.

## Builder

```kotlin
class InferenceStoreBuilder {
    fun provider(provider: InferenceProvider)
    fun providers(block: ProviderRegistryBuilder.() -> Unit)
    fun policy(policy: InferencePolicy)
    fun cache(cache: InferenceCache)
    fun artifactStore(store: InferenceArtifactStore)
    fun validatorFactory(factory: ValidatorFactory)
    fun monitor(monitor: InferenceMonitor)
    fun execution(config: InferenceExecutionConfig)
}

fun InferenceStore.Companion.build(
    block: InferenceStoreBuilder.() -> Unit
): InferenceStore
```

## Convenience APIs

```kotlin
suspend fun InferenceStore.generateText(
    key: InferenceKey,
    input: String,
    policy: InferencePolicy? = null,
    privacy: PrivacyPolicy = PrivacyPolicy.Default
): String
```

## Testkit API

```kotlin
val local = FakeInferenceProvider("local") {
    availability = Available
    supports(Capability.TextGeneration, Capability.Streaming)
    stream("hello", " world")
}

val cloud = FakeInferenceProvider("cloud", kind = ProviderKind.Cloud) {
    availability = Available
    supports(Capability.TextGeneration, Capability.StructuredOutput)
    complete("{\"summary\":\"hello world\"}")
}

val store = testInferenceStore {
    provider(local)
    provider(cloud)
    policy(Policies.preferLocalThenCloud())
}

val result = store.generate(request)

assertRoute(result.trace) {
    attempted("local")
    completedWith("local")
}
```

## Resolved API questions

1. Root `InferenceStore` generic? **No.** Requests carry output specs.
2. `OutputSpec` requires serialization? **Only for typed JSON.** Text remains simple.
3. Provider events expose provider-specific data? **Only via redacted metadata.**
4. Policies composable DSL or functions first? **Plain functions/presets first; DSL after patterns emerge.**
5. Privacy hardcoded enum or open type? **Five built-ins plus `Custom`, owned by `privacy-model.md`.**
6. Partial validation in MVP? **No.** Final-output validation only.
