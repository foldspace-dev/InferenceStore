package dev.mattramotar.inferencestore.core.telemetry

import dev.mattramotar.inferencestore.core.cache.fnv1aHex
import dev.mattramotar.inferencestore.core.event.AttemptOutcome
import dev.mattramotar.inferencestore.core.event.FallbackReason
import dev.mattramotar.inferencestore.core.event.FinalStatus
import dev.mattramotar.inferencestore.core.event.ProviderAttemptTrace
import dev.mattramotar.inferencestore.core.event.RouteTrace
import dev.mattramotar.inferencestore.core.policy.TelemetryPermission
import dev.mattramotar.inferencestore.core.provider.ErrorCategory
import dev.mattramotar.inferencestore.core.provider.ProviderKind
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

/**
 * The redacted, uploadable projection of a [RouteTrace] (`observability-evals.md`).
 *
 * A [RouteTrace] is already content-free — it holds ids, categories, and timings,
 * never raw prompts or outputs — so this type *cannot* carry user content by
 * construction. On top of that, [RouteTrace.toTelemetryRecord] applies the caller's
 * [TelemetryPermission]: provider/model identity is dropped unless
 * `emitProviderMetadata`, and even the hashed key is dropped unless `emitHashes`.
 */
@Serializable
public data class TelemetryRecord(
    public val requestId: String,
    /** A stable hash of the request key — never the raw key. Null unless `emitHashes`. */
    public val keyHash: String?,
    public val finalStatus: FinalStatus,
    public val policyId: String?,
    /** Final provider id. Null unless `emitProviderMetadata`. */
    public val finalProvider: String?,
    public val attempts: List<TelemetryAttempt>,
    public val fallbackReasons: List<FallbackReason>,
    public val rejectedReasons: List<FallbackReason>,
    public val startedAtMillis: Long?,
    public val completedAtMillis: Long?,
    public val servedFromCache: Boolean,
)

/** One attempt within a [TelemetryRecord]; provider identity is permission-gated. */
@Serializable
public data class TelemetryAttempt(
    /** Provider id. Null unless `emitProviderMetadata`. */
    public val providerId: String?,
    public val providerKind: ProviderKind,
    public val outcome: AttemptOutcome?,
    /** Model id. Null unless `emitProviderMetadata`. */
    public val modelId: String?,
    public val errorCategory: ErrorCategory?,
    public val firstTokenAtMillis: Long?,
    public val completedAtMillis: Long?,
)

/** Projects a [RouteTrace] into a [TelemetryRecord], applying [permission]'s redaction. */
public fun RouteTrace.toTelemetryRecord(
    permission: TelemetryPermission = TelemetryPermission(),
): TelemetryRecord {
    val includeMetadata = permission.emitProviderMetadata
    return TelemetryRecord(
        requestId = requestId,
        keyHash = if (permission.emitHashes) fnv1aHex(key) else null,
        finalStatus = finalStatus,
        policyId = policyId,
        finalProvider = finalProvider?.takeIf { includeMetadata },
        attempts = attempts.map { it.toTelemetryAttempt(includeMetadata) },
        fallbackReasons = fallbackReasons,
        rejectedReasons = rejectedProviders.map { it.reason },
        startedAtMillis = startedAtMillis,
        completedAtMillis = completedAtMillis,
        servedFromCache = servedFromCache,
    )
}

private fun ProviderAttemptTrace.toTelemetryAttempt(includeMetadata: Boolean): TelemetryAttempt =
    TelemetryAttempt(
        providerId = providerId.takeIf { includeMetadata },
        providerKind = providerKind,
        outcome = outcome,
        modelId = modelId?.takeIf { includeMetadata },
        errorCategory = errorCategory,
        firstTokenAtMillis = firstTokenAtMillis,
        completedAtMillis = completedAtMillis,
    )

/** Result of a [TelemetryUploader.upload] attempt — drives the worker's retain/drop decision. */
public enum class UploadOutcome {
    /** The batch was accepted; records can be removed from the buffer. */
    Success,

    /** A transient failure (network, 5xx); keep the batch and retry later. */
    TransientFailure,

    /** A permanent failure (malformed batch, 4xx); drop the batch — retrying won't help. */
    PermanentFailure,
}

/**
 * Uploads a batch of redacted [TelemetryRecord]s to a sink the integrator provides
 * (their analytics backend). Implementations do the network I/O and classify the
 * result; a thrown exception is treated as a transient failure by the worker.
 */
public fun interface TelemetryUploader {
    public suspend fun upload(batch: List<TelemetryRecord>): UploadOutcome
}

/**
 * One buffered trace plus a unique, monotonically-increasing [sequence] assigned when
 * it was recorded. Removal keys off [sequence], not the trace's `requestId` — a
 * `requestId` is the request key string and so is *not* unique across requests, which
 * would let a successful upload delete a not-yet-uploaded duplicate-key trace.
 */
public data class PendingTrace(
    public val sequence: Long,
    public val trace: RouteTrace,
)

/**
 * A buffer of pending [RouteTrace]s awaiting upload. Apps record a trace after each
 * request (e.g. `store.record(result.trace)`); the [RouteTelemetryUploader] drains
 * batches in the background. [peek] is non-destructive so a failed upload can be
 * retried; successful/permanently-failed entries are [remove]d by their unique
 * [PendingTrace.sequence].
 */
public interface RouteTelemetryStore {
    public suspend fun record(trace: RouteTrace)
    public suspend fun peek(max: Int): List<PendingTrace>
    public suspend fun remove(sequences: List<Long>)
    public suspend fun size(): Int
}

/**
 * In-memory [RouteTelemetryStore]; coroutine-safe via a [Mutex]. Bounded at
 * [maxRetained] — telemetry is best-effort, so when full the oldest pending trace is
 * dropped rather than growing without limit (e.g. if uploads keep failing offline).
 */
public class MemoryRouteTelemetryStore(
    private val maxRetained: Int = 1_000,
) : RouteTelemetryStore {
    init {
        require(maxRetained > 0) { "maxRetained must be positive" }
    }

    private val mutex = Mutex()
    private val pending = ArrayDeque<PendingTrace>()

    // Guarded by [mutex]; monotonic so every buffered entry has a unique id even when
    // two traces share a requestId. 2^63 records is unreachable, so overflow is moot.
    private var nextSequence = 0L

    override suspend fun record(trace: RouteTrace) {
        mutex.withLock {
            pending.addLast(PendingTrace(nextSequence++, trace))
            while (pending.size > maxRetained) pending.removeFirst()
        }
    }

    override suspend fun peek(max: Int): List<PendingTrace> = mutex.withLock {
        if (max <= 0) emptyList() else pending.take(max)
    }

    override suspend fun remove(sequences: List<Long>) {
        if (sequences.isEmpty()) return
        val seqs = sequences.toHashSet()
        mutex.withLock { pending.removeAll { it.sequence in seqs } }
    }

    override suspend fun size(): Int = mutex.withLock { pending.size }
}
