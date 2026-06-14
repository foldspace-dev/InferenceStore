package dev.mattramotar.inferencestore.provider.firebase

import dev.mattramotar.inferencestore.core.provider.ErrorCategory
import dev.mattramotar.inferencestore.core.provider.ErrorSource
import dev.mattramotar.inferencestore.core.provider.ProviderId
import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Where Firebase AI Logic actually served a generation. Surfaced in the route trace. */
public enum class FirebaseAiSource { OnDevice, Cloud }

/** Configuration for a [FirebaseAiLogicProvider]. */
public data class FirebaseAiConfig(
    public val modelId: String,
    public val providerId: ProviderId = ProviderId(FirebaseAiLogicProvider.ID),
    /**
     * Whether the hybrid may fall through to the cloud model when on-device is
     * unavailable. Even when false, the provider keeps a cloud-like privacy boundary
     * (it COULD use cloud), so the privacy gate stays conservative — for a hard
     * local-only guarantee use a dedicated on-device provider instead (see README).
     */
    public val allowCloud: Boolean = true,
    public val initializationTimeout: Duration = 10.seconds,
) {
    init {
        require(modelId.isNotBlank()) { "modelId must not be blank" }
        require(initializationTimeout > Duration.ZERO) { "initializationTimeout must be positive" }
    }
}

/** Result of a readiness probe. */
public sealed interface FirebaseAiStatus {
    public data object Ready : FirebaseAiStatus
    public data class Unavailable(public val failure: FirebaseAiFailure) : FirebaseAiStatus
}

/** Why Firebase AI Logic is unavailable. */
public enum class FirebaseAiFailure {
    NotConfigured,
    NetworkUnavailable,
    OnDeviceModelMissing,
    QuotaExceeded,
    InitializationTimeout,
    Unknown,
}

/** One streamed chunk, tagged with the [source] Firebase actually used for it. */
public data class FirebaseAiChunk(public val text: String, public val source: FirebaseAiSource)

/**
 * A runtime error mapped to a stable [category]/[source]. Default [source] is
 * `ProviderSpecific` so a runtime-classified permanent error can still fall back.
 */
public class FirebaseAiException(
    public val category: ErrorCategory,
    message: String? = null,
    cause: Throwable? = null,
    public val source: ErrorSource = ErrorSource.ProviderSpecific,
) : RuntimeException(message, cause)

/**
 * The boundary between the adapter and the actual Firebase AI Logic SDK. Integrators
 * implement this with the native library; the adapter stays SDK-agnostic and fully
 * testable. The implementation OWNS its threading (run SDK calls off the caller's
 * dispatcher) and MUST honor cancellation by closing the active generation.
 */
public interface FirebaseAiRuntime {
    /** Bounded readiness probe — does NOT run a full generation. */
    public suspend fun probe(config: FirebaseAiConfig): FirebaseAiStatus

    /**
     * Streams generated chunks for [prompt]. Cold: work starts on collection. Each
     * [FirebaseAiChunk] reports whether Firebase served it on-device or from cloud.
     * Implementations map errors to [FirebaseAiException].
     */
    public fun generate(config: FirebaseAiConfig, prompt: String): Flow<FirebaseAiChunk>
}
