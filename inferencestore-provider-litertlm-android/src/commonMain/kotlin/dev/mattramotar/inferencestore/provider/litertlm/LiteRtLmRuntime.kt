package dev.mattramotar.inferencestore.provider.litertlm

import dev.mattramotar.inferencestore.core.provider.ErrorCategory
import dev.mattramotar.inferencestore.core.provider.ErrorSource
import dev.mattramotar.inferencestore.core.provider.ProviderId
import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Backend selection for LiteRT-LM (`litert-lm-adapter.md`). */
public enum class LiteRtLmBackend { Auto, Cpu, Gpu }

/** Configuration for a [LiteRtLmProvider]. `modelPath` points to an already-present `.litertlm` model. */
public data class LiteRtLmProviderConfig(
    public val modelPath: String,
    public val modelId: String,
    public val providerId: ProviderId = ProviderId(LiteRtLmProvider.ID),
    public val backend: LiteRtLmBackend = LiteRtLmBackend.Auto,
    public val initializationTimeout: Duration = 15.seconds,
) {
    init {
        require(modelPath.isNotBlank()) { "modelPath must not be blank" }
        require(modelId.isNotBlank()) { "modelId must not be blank" }
        require(initializationTimeout > Duration.ZERO) { "initializationTimeout must be positive" }
    }
}

/** Why a LiteRT-LM model/runtime is unavailable (`litert-lm-adapter.md` availability table). */
public enum class LiteRtLmFailure {
    MissingModel,
    ModelUnreadable,
    UnsupportedBackend,
    InitializationTimeout,
    InsufficientMemory,
    RuntimeInitializationFailed,
}

/** Result of a readiness probe. */
public sealed interface LiteRtLmStatus {
    public data object Ready : LiteRtLmStatus
    public data class Unavailable(public val failure: LiteRtLmFailure) : LiteRtLmStatus
}

/**
 * A runtime error raised during generation, already mapped to a stable [category]
 * and [source]. The default [source] is `ProviderSpecific` so a runtime-classified
 * `PermanentProviderError` can fall back (per `error-fallback-mapping.md`).
 */
public class LiteRtLmException(
    public val category: ErrorCategory,
    message: String? = null,
    cause: Throwable? = null,
    public val source: ErrorSource = ErrorSource.ProviderSpecific,
) : RuntimeException(message, cause)

/**
 * The boundary between the adapter and the actual LiteRT-LM (Google AI Edge)
 * runtime. Integrators implement this with the native library; the adapter stays
 * runtime-agnostic and fully testable.
 *
 * The implementation OWNS its threading: it MUST run engine initialization and
 * synchronous native calls off the caller's dispatcher (e.g. on a dedicated
 * thread or `Dispatchers.Default`) so collecting from a UI scope never blocks,
 * and it MUST release native resources on completion, failure, and cancellation.
 */
public interface LiteRtLmRuntime {
    /** Bounded readiness probe: validates model path/backend without full generation. */
    public suspend fun probe(modelPath: String, backend: LiteRtLmBackend): LiteRtLmStatus

    /**
     * Streams generated text tokens for [prompt]. Cold: work starts on collection.
     * Implementations map runtime errors to [LiteRtLmException] and must honor
     * cancellation by closing the active conversation/engine.
     */
    public fun generate(modelPath: String, backend: LiteRtLmBackend, prompt: String): Flow<String>
}
