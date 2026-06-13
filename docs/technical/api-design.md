# API design

Generated: 2026-06-13

## Design goals

- KMP common API.
- Coroutine and Flow native.
- Streaming-first with non-streaming convenience.
- Strong enough types for policy and tests.
- Adapter-friendly.
- Store-inspired but not Store-copying.
- Privacy-safe defaults.

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
    val timeout: Duration? = null,
    val prompt: PromptSpec? = null,
    val metadata: Map<String, String> = emptyMap()
)
```

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

MVP can support `Text` and `Messages`; multimodal inputs can be added later.

## Output spec

```kotlin
sealed interface OutputSpec<Output : Any> {
    data object Text : OutputSpec<String>

    data class Json<Output : Any>(
        val serializer: KSerializer<Output>,
        val schema: JsonSchema? = null
    ) : OutputSpec<Output>

    data class Custom<Output : Any>(
        val parser: OutputParser<Output>,
        val validators: List<OutputValidator<Output>> = emptyList()
    ) : OutputSpec<Output>
}
```

## Events

```kotlin
sealed interface InferenceEvent<out Output : Any> {
    data class Started(val requestId: RequestId) : InferenceEvent<Nothing>
    data class CacheChecked(val outcome: CacheOutcome) : InferenceEvent<Nothing>
    data class RoutePlanned(val route: InferenceRoute) : InferenceEvent<Nothing>
    data class ProviderSelected(val attempt: ProviderAttempt) : InferenceEvent<Nothing>
    data class Token(val text: String) : InferenceEvent<Nothing>
    data class Partial<Output : Any>(val value: Output) : InferenceEvent<Output>
    data class ValidationCompleted(val result: ValidationResult) : InferenceEvent<Nothing>
    data class FallbackStarted(val reason: FallbackReason, val next: ProviderId?) : InferenceEvent<Nothing>
    data class Done<Output : Any>(val result: InferenceResult<Output>) : InferenceEvent<Output>
    data class Failed(val error: InferenceError, val trace: RouteTrace) : InferenceEvent<Nothing>
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

## Provider

```kotlin
interface InferenceProvider {
    val id: ProviderId
    val kind: ProviderKind

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

## Provider IDs

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

## Availability

```kotlin
sealed interface ProviderAvailability {
    data object Available : ProviderAvailability
    data class Unavailable(val reason: AvailabilityReason) : ProviderAvailability
    data class Unknown(val reason: String? = null) : ProviderAvailability
}

sealed interface AvailabilityReason {
    data object MissingModel : AvailabilityReason
    data object DownloadRequired : AvailabilityReason
    data object NetworkUnavailable : AvailabilityReason
    data object UnsupportedDevice : AvailabilityReason
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
    val cache: CachePolicy = CachePolicy.Default
)

data class RouteAttempt(
    val providerId: ProviderId,
    val reason: RouteReason,
    val timeout: Duration? = null
)
```

## Built-in policies

```kotlin
object Policies {
    fun localOnly(): InferencePolicy
    fun cloudOnly(): InferencePolicy
    fun preferLocalThenCloud(): InferencePolicy
    fun preferCloudThenLocal(): InferencePolicy
    fun privacyFirst(): InferencePolicy
    fun validateLocalRepairWithCloud(): InferencePolicy
}
```

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

Monitor events should be redacted by default.

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

val cloud = FakeInferenceProvider("cloud") {
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

assertThat(result.trace).attempted("local")
assertThat(result.trace).completedWith("local")
```

## API questions to resolve in RFC

1. Should `InferenceStore` be generic?  
   Recommendation: no at the root; requests carry output specs.

2. Should `OutputSpec` require serialization?  
   Recommendation: only for typed JSON; text remains simple.

3. Should provider events expose provider-specific data?  
   Recommendation: through typed `metadata`, not public sealed classes.

4. Should policies be composable DSL or plain functions first?  
   Recommendation: plain functions first, DSL after patterns emerge.

5. Should privacy be hardcoded enum or open type?  
   Recommendation: built-in classes plus custom tags.
