package dev.mattramotar.inferencestore.core.cache

import dev.mattramotar.inferencestore.core.event.RouteTrace
import dev.mattramotar.inferencestore.core.model.InferenceKey
import dev.mattramotar.inferencestore.core.model.OutputSpec
import dev.mattramotar.inferencestore.core.provider.ProviderMetadata
import dev.mattramotar.inferencestore.core.validation.ValidationResult
import kotlinx.coroutines.flow.Flow

/**
 * Result of consulting the cache for a request (surfaced to monitors).
 *
 * NOTE on terminology (`storage-model.md`): this is an **artifact** cache, not a
 * Store `SourceOfTruth`. Generated output is a cached computation, not
 * authoritative truth — it is invalidated by model/prompt/policy changes (the
 * fingerprint), so the public API uses "cache"/"artifact store" rather than
 * "source of truth".
 */
public enum class CacheOutcome { Hit, Miss, Stale, Disabled }

/**
 * A stored inference result keyed by [fingerprint]. [output]/[rawText] may be null
 * when privacy permits only trace persistence (redacted artifact).
 */
public data class InferenceArtifact<Output : Any>(
    public val fingerprint: InferenceFingerprint,
    public val output: Output?,
    public val rawText: String?,
    public val provider: ProviderMetadata,
    public val trace: RouteTrace?,
    public val validation: ValidationResult? = null,
    public val createdAtMillis: Long? = null,
    public val expiresAtMillis: Long? = null,
    public val metadata: Map<String, String> = emptyMap(),
)

/**
 * A cache keyed by [InferenceFingerprint]. Implementations enforce validity (TTL,
 * version compatibility) — a [read] returns only a still-valid artifact, or null.
 * Implementations must honor `PrivacyPolicy.persistence` (the engine only writes
 * when the policy allows).
 */
public interface InferenceCache {
    public suspend fun <Output : Any> read(
        fingerprint: InferenceFingerprint,
        output: OutputSpec<Output>,
    ): InferenceArtifact<Output>?

    public suspend fun <Output : Any> write(artifact: InferenceArtifact<Output>)

    public suspend fun clear(key: InferenceKey)
    public suspend fun clearAll()
}

/**
 * A more explicit persistent artifact store (`storage-model.md`). Interface only in
 * MVP; the SQLDelight implementation lands in OSS-34.
 */
public interface InferenceArtifactStore {
    public fun reader(fingerprint: InferenceFingerprint): Flow<InferenceArtifact<*>?>
    public suspend fun write(artifact: InferenceArtifact<*>)
    public suspend fun delete(fingerprint: InferenceFingerprint)
    public suspend fun deleteAll()
}
