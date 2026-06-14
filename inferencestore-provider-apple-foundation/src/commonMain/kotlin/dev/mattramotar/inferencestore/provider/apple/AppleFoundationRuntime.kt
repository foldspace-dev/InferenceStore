package dev.mattramotar.inferencestore.provider.apple

import dev.mattramotar.inferencestore.core.provider.ErrorCategory
import dev.mattramotar.inferencestore.core.provider.ErrorSource
import dev.mattramotar.inferencestore.core.provider.ProviderId
import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Configuration for an [AppleFoundationModelsProvider]. */
public data class AppleFoundationConfig(
    public val modelId: String = "apple-foundation-system",
    public val providerId: ProviderId = ProviderId(AppleFoundationModelsProvider.ID),
    public val availabilityTimeout: Duration = 5.seconds,
) {
    init {
        require(modelId.isNotBlank()) { "modelId must not be blank" }
        require(availabilityTimeout > Duration.ZERO) { "availabilityTimeout must be positive" }
    }
}

/**
 * Why the Apple on-device model is unavailable, mirroring the cases of
 * `SystemLanguageModel.Availability.unavailable` (`adr/0006`).
 */
public enum class AppleModelUnavailability {
    /** The OS is older than iOS/iPadOS/macOS/visionOS 26. */
    OsTooOld,

    /** The hardware can't run Apple Intelligence. */
    DeviceNotEligible,

    /** The user hasn't turned on Apple Intelligence. */
    AppleIntelligenceNotEnabled,

    /** The on-device model assets aren't ready yet (still downloading). */
    ModelNotReady,

    /** Availability couldn't be determined. */
    Unknown,
}

/** Result of an availability probe (`SystemLanguageModel.availability`). */
public sealed interface AppleModelAvailability {
    public data object Available : AppleModelAvailability
    public data class Unavailable(public val reason: AppleModelUnavailability) : AppleModelAvailability
}

/** One generation request handed to the runtime. */
public data class AppleGenerationRequest(
    public val prompt: String,
    /** Request guided generation (structured/JSON output) when true. */
    public val structured: Boolean,
    /** Optional schema name hint for `@Generable` guided generation; null for free text. */
    public val schemaName: String? = null,
)

/**
 * A runtime error raised during generation, already mapped to a stable [category]
 * and [source]. The default [source] is `ProviderSpecific` so a runtime-classified
 * `PermanentProviderError` can still fall back (per `error-fallback-mapping.md`).
 */
public class AppleFoundationException(
    public val category: ErrorCategory,
    message: String? = null,
    cause: Throwable? = null,
    public val source: ErrorSource = ErrorSource.ProviderSpecific,
) : RuntimeException(message, cause)

/**
 * The boundary between the adapter and Apple's `FoundationModels` framework. Integrators
 * implement this with a thin Swift shim bridged into Kotlin/Native (`adr/0006`); the
 * adapter stays SDK-agnostic and fully testable with a fake runtime.
 *
 * The implementation OWNS its threading and resource lifecycle: it must not block the
 * collector's dispatcher and must release the `LanguageModelSession` on completion,
 * failure, and cancellation. The runtime only ever sees prompts/outputs — never API keys
 * — because the on-device model needs none.
 */
public interface AppleFoundationRuntime {
    /** Reports on-device model availability. Bounded by the adapter; should not block. */
    public suspend fun availability(): AppleModelAvailability

    /**
     * Streams generated text tokens for [request]. Cold: work starts on collection. For a
     * structured request the streamed text is the guided (JSON) output. Implementations
     * map framework errors to [AppleFoundationException] and must honor cancellation.
     */
    public fun generate(request: AppleGenerationRequest): Flow<String>
}
