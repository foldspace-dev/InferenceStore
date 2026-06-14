package dev.mattramotar.inferencestore.core.cache

import dev.mattramotar.inferencestore.core.event.RouteTrace
import dev.mattramotar.inferencestore.core.model.InferenceKey
import dev.mattramotar.inferencestore.core.model.OutputSpec
import dev.mattramotar.inferencestore.core.policy.CachePolicy
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
 * A cache keyed by [InferenceFingerprint].
 *
 * The request's [CachePolicy] is passed to both calls so implementations can honor
 * request-level expiry and staleness:
 * - [read] should return a still-valid artifact, or null on a miss. When
 *   [CachePolicy.allowStaleWhileRevalidate] is set it may also return an expired
 *   artifact (the engine treats any returned artifact with non-null output as a hit).
 * - [write] should derive expiry from [CachePolicy.ttl] (e.g. stamp
 *   [InferenceArtifact.expiresAtMillis]) using its own clock.
 *
 * The engine only ever calls [write] when the privacy policy permits persisting
 * output, so implementations do not re-check privacy; they own clock/TTL/eviction.
 */
public interface InferenceCache {
    public suspend fun <Output : Any> read(
        fingerprint: InferenceFingerprint,
        output: OutputSpec<Output>,
        policy: CachePolicy,
    ): InferenceArtifact<Output>?

    public suspend fun <Output : Any> write(artifact: InferenceArtifact<Output>, policy: CachePolicy)

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
