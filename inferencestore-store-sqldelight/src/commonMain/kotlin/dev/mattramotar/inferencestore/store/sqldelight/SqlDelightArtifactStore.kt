package dev.mattramotar.inferencestore.store.sqldelight

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOneOrNull
import dev.mattramotar.inferencestore.core.cache.InferenceArtifact
import dev.mattramotar.inferencestore.core.cache.InferenceArtifactStore
import dev.mattramotar.inferencestore.core.cache.InferenceFingerprint
import dev.mattramotar.inferencestore.core.event.AttemptOutcome
import dev.mattramotar.inferencestore.core.event.RouteTrace
import dev.mattramotar.inferencestore.core.provider.ErrorCategory
import dev.mattramotar.inferencestore.core.provider.ProviderExecutionBoundary
import dev.mattramotar.inferencestore.core.provider.ProviderId
import dev.mattramotar.inferencestore.core.provider.ProviderKind
import dev.mattramotar.inferencestore.core.provider.ProviderMetadata
import dev.mattramotar.inferencestore.core.provider.ProviderPrivacyBoundary
import dev.mattramotar.inferencestore.core.provider.ProviderPrivacyBoundaryId
import dev.mattramotar.inferencestore.store.sqldelight.db.Inference_artifact
import dev.mattramotar.inferencestore.store.sqldelight.db.InferenceStoreDatabase
import dev.mattramotar.inferencestore.store.sqldelight.db.Route_attempt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.coroutines.CoroutineContext

/** A persisted route attempt (the SQLDelight route-journal prototype). */
public data class PersistedRouteAttempt(
    public val requestId: String,
    public val providerId: ProviderId,
    public val outcome: AttemptOutcome,
    public val modelId: String?,
    public val errorCategory: ErrorCategory?,
    public val recordedAtEpochMs: Long,
)

/**
 * A SQLDelight-backed [InferenceArtifactStore] + route-attempt journal (OSS-34,
 * `storage-model.md`). Persists artifacts and route attempts across process restarts.
 *
 * The caller supplies an [InferenceStoreDatabase] (built from a platform `SqlDriver`),
 * so this stays driver-agnostic and testable with an in-memory JDBC driver. Privacy is
 * the caller's: a redacted artifact (null `output`/`rawText`) persists with null content.
 *
 * Prototype scope: the typed `output` is NOT persisted (only `rawText`), and provider
 * `capabilities`/`extra` are not round-tripped — a read reconstructs id/kind/boundary/model.
 */
public class SqlDelightArtifactStore(
    private val database: InferenceStoreDatabase,
    private val readContext: CoroutineContext = Dispatchers.Default,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : InferenceArtifactStore {

    private val artifacts = database.artifactQueries
    private val routes = database.routeAttemptQueries

    override fun reader(fingerprint: InferenceFingerprint): Flow<InferenceArtifact<*>?> =
        artifacts.selectByFingerprint(fingerprintKey(fingerprint)).asFlow()
            .mapToOneOrNull(readContext)
            .map { row -> row?.let { toArtifact(fingerprint, it) } }

    override suspend fun write(artifact: InferenceArtifact<*>) {
        val provider = artifact.provider
        artifacts.upsert(
            fingerprint = fingerprintKey(artifact.fingerprint),
            key_namespace = artifact.fingerprint.key.namespace,
            key_id = artifact.fingerprint.key.id,
            provider_id = provider.providerId.value,
            provider_kind = provider.providerKind.name,
            boundary_id = provider.boundary.id.value,
            boundary_execution = provider.boundary.execution.name,
            model_id = provider.modelId,
            raw_output = artifact.rawText,
            typed_output_json = null, // typed output is not persisted in the prototype
            trace_json = artifact.trace?.let { json.encodeToString(RouteTrace.serializer(), it) },
            validation_json = null,
            created_at_epoch_ms = artifact.createdAtMillis ?: 0L,
            expires_at_epoch_ms = artifact.expiresAtMillis,
        )
    }

    override suspend fun delete(fingerprint: InferenceFingerprint) {
        artifacts.deleteByFingerprint(fingerprintKey(fingerprint))
    }

    override suspend fun deleteAll() {
        artifacts.deleteAll()
    }

    /** Persist one route attempt. */
    public suspend fun recordAttempt(
        requestId: String,
        providerId: ProviderId,
        outcome: AttemptOutcome,
        modelId: String? = null,
        errorCategory: ErrorCategory? = null,
        fingerprint: InferenceFingerprint? = null,
        atEpochMs: Long,
    ) {
        routes.insert(
            request_id = requestId,
            fingerprint = fingerprint?.let { fingerprintKey(it) },
            provider_id = providerId.value,
            model_id = modelId,
            outcome = outcome.name,
            error_category = errorCategory?.name,
            recorded_at_epoch_ms = atEpochMs,
        )
    }

    /** All persisted attempts for [providerId], most recent first. Rows with an unknown
     *  outcome (e.g. a renamed enum) are skipped rather than failing the whole query. */
    public suspend fun attemptsFor(providerId: ProviderId): List<PersistedRouteAttempt> =
        routes.selectByProvider(providerId.value).executeAsList().mapNotNull { it.toAttemptOrNull() }

    public suspend fun clearRouteAttempts() {
        routes.deleteAll()
    }

    // A content-free, deterministic, COLLISION-PROOF primary key for the fingerprint:
    // JSON-encoding the components escapes separators and distinguishes null from "",
    // unlike a raw delimiter join.
    private fun fingerprintKey(fingerprint: InferenceFingerprint): String = with(fingerprint) {
        json.encodeToString(
            listOf(key.asString(), inputHash, promptVersion, outputVersion, privacyClass, privacyPolicyVersion, policyVersion),
        )
    }

    // A corrupt/forward-incompatible row (e.g. an enum renamed since it was written) must
    // not crash the reader Flow — treat it as a miss.
    private fun toArtifact(fingerprint: InferenceFingerprint, row: Inference_artifact): InferenceArtifact<Any>? {
        val kind = enumOrNull<ProviderKind>(row.provider_kind) ?: return null
        val execution = enumOrNull<ProviderExecutionBoundary>(row.boundary_execution) ?: return null
        val trace = row.trace_json?.let { runCatching { json.decodeFromString(RouteTrace.serializer(), it) }.getOrNull() }
        return InferenceArtifact(
            fingerprint = fingerprint,
            output = null, // typed output is re-decoded by the caller from rawText if needed
            rawText = row.raw_output,
            provider = ProviderMetadata(
                providerId = ProviderId(row.provider_id),
                providerKind = kind,
                boundary = ProviderPrivacyBoundary(ProviderPrivacyBoundaryId(row.boundary_id), execution),
                modelId = row.model_id,
            ),
            trace = trace,
            createdAtMillis = row.created_at_epoch_ms,
            expiresAtMillis = row.expires_at_epoch_ms,
        )
    }

    private fun Route_attempt.toAttemptOrNull(): PersistedRouteAttempt? {
        val parsedOutcome = enumOrNull<AttemptOutcome>(outcome) ?: return null
        return PersistedRouteAttempt(
            requestId = request_id,
            providerId = ProviderId(provider_id),
            outcome = parsedOutcome,
            modelId = model_id,
            errorCategory = error_category?.let { enumOrNull<ErrorCategory>(it) },
            recordedAtEpochMs = recorded_at_epoch_ms,
        )
    }

    private inline fun <reified T : Enum<T>> enumOrNull(name: String): T? =
        runCatching { enumValueOf<T>(name) }.getOrNull()
}
