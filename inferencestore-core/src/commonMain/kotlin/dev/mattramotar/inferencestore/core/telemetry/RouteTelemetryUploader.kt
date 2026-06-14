package dev.mattramotar.inferencestore.core.telemetry

import dev.mattramotar.inferencestore.core.policy.TelemetryPermission
import kotlinx.serialization.Serializable
import kotlin.coroutines.cancellation.CancellationException

/** Task payload for a background telemetry upload (`meeseeks-integration.md`). */
@Serializable
public data class UploadTelemetryPayload(
    public val batchId: String = "default",
    public val maxBatchSize: Int = 100,
)

/** Why a [TelemetryUploadResult] ended the way it did. */
public enum class TelemetryUploadStatus {
    /** A batch was uploaded and its records removed from the buffer. */
    Uploaded,

    /** Nothing was pending; no upload attempted. */
    Empty,

    /** Telemetry is disabled by [TelemetryPermission.emitMetrics]; nothing left the device. */
    PermissionDenied,

    /** A transient upload failure; the batch was retained for a later retry. */
    RetryScheduled,

    /** A permanent upload failure; the poison batch was dropped to unblock the queue. */
    Dropped,
}

/** Outcome of one upload run — a record the Meeseeks worker maps to a `TaskResult`. */
public data class TelemetryUploadResult(
    public val status: TelemetryUploadStatus,
    public val uploadedCount: Int,
)

/**
 * Uploads batches of redacted route telemetry in the background — the Meeseeks-agnostic
 * core of the telemetry worker (OSS-39). Wrap [upload] in a Meeseeks
 * `Worker<UploadTelemetryPayload>` (see the telemetry docs) to batch and retry without
 * touching the foreground inference path; tests call it directly.
 *
 * Privacy is enforced two ways. First, the [permission] gate: if
 * [TelemetryPermission.emitMetrics] is off, nothing is uploaded
 * ([TelemetryUploadStatus.PermissionDenied]). Second, every trace is projected through
 * [RouteTrace.toTelemetryRecord] before upload, so only redacted, permission-filtered
 * records — never raw prompts or outputs — leave the device.
 *
 * Reliability: [peek] is non-destructive, so a transient failure retains the batch for
 * the next run ([TelemetryUploadStatus.RetryScheduled]); a permanent failure drops the
 * batch so one poison record can't block the queue forever. An [TelemetryUploader] that
 * throws is treated as a transient failure.
 */
public class RouteTelemetryUploader(
    private val store: RouteTelemetryStore,
    private val uploader: TelemetryUploader,
    private val permission: TelemetryPermission = TelemetryPermission(),
) {
    /** Drain up to [UploadTelemetryPayload.maxBatchSize] pending traces and upload them. */
    public suspend fun upload(
        payload: UploadTelemetryPayload = UploadTelemetryPayload(),
    ): TelemetryUploadResult {
        if (!permission.emitMetrics) {
            return TelemetryUploadResult(TelemetryUploadStatus.PermissionDenied, uploadedCount = 0)
        }

        val pending = store.peek(payload.maxBatchSize)
        if (pending.isEmpty()) {
            return TelemetryUploadResult(TelemetryUploadStatus.Empty, uploadedCount = 0)
        }

        val batch = pending.map { it.trace.toTelemetryRecord(permission) }
        val outcome = try {
            uploader.upload(batch)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            // A thrown uploader is a transient failure: keep the batch and retry later.
            UploadOutcome.TransientFailure
        }

        // Remove exactly the peeked entries by their unique sequence — never by
        // requestId, which is non-unique and could delete an unsent duplicate.
        return when (outcome) {
            UploadOutcome.Success -> {
                store.remove(pending.map { it.sequence })
                TelemetryUploadResult(TelemetryUploadStatus.Uploaded, uploadedCount = batch.size)
            }

            UploadOutcome.TransientFailure ->
                TelemetryUploadResult(TelemetryUploadStatus.RetryScheduled, uploadedCount = 0)

            UploadOutcome.PermanentFailure -> {
                store.remove(pending.map { it.sequence })
                TelemetryUploadResult(TelemetryUploadStatus.Dropped, uploadedCount = batch.size)
            }
        }
    }
}
