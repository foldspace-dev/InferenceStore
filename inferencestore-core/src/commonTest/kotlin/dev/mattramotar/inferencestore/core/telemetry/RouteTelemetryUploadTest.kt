package dev.mattramotar.inferencestore.core.telemetry

import dev.mattramotar.inferencestore.core.event.AttemptOutcome
import dev.mattramotar.inferencestore.core.event.FinalStatus
import dev.mattramotar.inferencestore.core.event.ProviderAttemptTrace
import dev.mattramotar.inferencestore.core.event.RouteTrace
import dev.mattramotar.inferencestore.core.policy.TelemetryPermission
import dev.mattramotar.inferencestore.core.provider.ProviderKind
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Meeseeks telemetry-upload worker's core logic and redaction (OSS-39). */
class RouteTelemetryUploadTest {

    private fun trace(id: String, model: String = "gemma-secret-model") = RouteTrace(
        requestId = id,
        key = "notes:summarize:$id",
        finalStatus = FinalStatus.Succeeded,
        policyId = "prefer-local",
        attempts = listOf(
            ProviderAttemptTrace(
                providerId = "local-runtime",
                providerKind = ProviderKind.Local,
                outcome = AttemptOutcome.Succeeded,
                modelId = model,
                firstTokenAtMillis = 5,
                completedAtMillis = 9,
            ),
        ),
        finalProvider = "local-runtime",
        startedAtMillis = 1,
        completedAtMillis = 10,
    )

    /** A configurable, recording [TelemetryUploader] for assertions. */
    private class RecordingUploader(
        private val outcome: UploadOutcome = UploadOutcome.Success,
        private val throwError: Throwable? = null,
    ) {
        var calls: Int = 0
            private set
        var lastBatch: List<TelemetryRecord> = emptyList()
            private set

        fun asUploader(): TelemetryUploader = TelemetryUploader { batch ->
            calls++
            lastBatch = batch
            throwError?.let { throw it }
            outcome
        }
    }

    // --- Redaction -----------------------------------------------------------

    @Test
    fun toTelemetryRecord_default_includesHashedKeyAndMetadata_butNoRawKey() {
        val record = trace("r1").toTelemetryRecord() // default permission

        assertEquals("r1", record.requestId)
        assertEquals("local-runtime", record.finalProvider)
        assertEquals("local-runtime", record.attempts.single().providerId)
        assertEquals("gemma-secret-model", record.attempts.single().modelId)
        // The key is hashed, never carried raw.
        val keyHash = record.keyHash
        assertNotNull(keyHash)
        assertTrue(keyHash.isNotEmpty())
        assertFalse(keyHash.contains("notes:summarize"))
    }

    @Test
    fun toTelemetryRecord_withoutProviderMetadata_dropsProviderAndModelIds() {
        val permission = TelemetryPermission(emitProviderMetadata = false)
        val record = trace("r1").toTelemetryRecord(permission)

        assertNull(record.finalProvider)
        val attempt = record.attempts.single()
        assertNull(attempt.providerId)
        assertNull(attempt.modelId)
        // Non-identifying fields survive redaction.
        assertEquals(ProviderKind.Local, attempt.providerKind)
        assertEquals(AttemptOutcome.Succeeded, attempt.outcome)
    }

    @Test
    fun toTelemetryRecord_withoutHashes_dropsKeyHash() {
        val record = trace("r1").toTelemetryRecord(TelemetryPermission(emitHashes = false))
        assertNull(record.keyHash)
    }

    @Test
    fun serializedRecord_neverContainsRawKeyOrModel_whenRedacted() {
        // Strongest redaction proof: the wire form leaks neither the raw key nor,
        // when metadata is disabled, the model id.
        val permission = TelemetryPermission(emitProviderMetadata = false, emitHashes = false)
        val json = Json.encodeToString(trace("r1").toTelemetryRecord(permission))

        assertFalse(json.contains("notes:summarize")) // raw key never serialized
        assertFalse(json.contains("gemma-secret-model")) // model id redacted
        assertContains(json, "r1") // request id is retained
    }

    // --- Upload orchestration ------------------------------------------------

    @Test
    fun upload_success_uploadsAndRemovesRecords() = runTest {
        val store = MemoryRouteTelemetryStore()
        store.record(trace("a"))
        store.record(trace("b"))
        val uploader = RecordingUploader(UploadOutcome.Success)
        val worker = RouteTelemetryUploader(store, uploader.asUploader())

        val result = worker.upload(UploadTelemetryPayload())

        assertEquals(TelemetryUploadStatus.Uploaded, result.status)
        assertEquals(2, result.uploadedCount)
        assertEquals(1, uploader.calls)
        assertEquals(0, store.size()) // uploaded records are removed
    }

    @Test
    fun upload_transientFailure_retainsRecordsForRetry() = runTest {
        val store = MemoryRouteTelemetryStore()
        store.record(trace("a"))
        val worker = RouteTelemetryUploader(store, RecordingUploader(UploadOutcome.TransientFailure).asUploader())

        val result = worker.upload()

        assertEquals(TelemetryUploadStatus.RetryScheduled, result.status)
        assertEquals(0, result.uploadedCount)
        assertEquals(1, store.size()) // retained: will retry next run
    }

    @Test
    fun upload_thrownUploader_isTreatedAsTransient() = runTest {
        val store = MemoryRouteTelemetryStore()
        store.record(trace("a"))
        val worker = RouteTelemetryUploader(
            store,
            RecordingUploader(throwError = IllegalStateException("network down")).asUploader(),
        )

        val result = worker.upload()

        assertEquals(TelemetryUploadStatus.RetryScheduled, result.status)
        assertEquals(1, store.size())
    }

    @Test
    fun upload_permanentFailure_dropsPoisonBatch() = runTest {
        val store = MemoryRouteTelemetryStore()
        store.record(trace("a"))
        val worker = RouteTelemetryUploader(store, RecordingUploader(UploadOutcome.PermanentFailure).asUploader())

        val result = worker.upload()

        assertEquals(TelemetryUploadStatus.Dropped, result.status)
        assertEquals(0, store.size()) // dropped so it can't block the queue forever
    }

    @Test
    fun upload_permissionDenied_doesNotUploadOrDrain() = runTest {
        val store = MemoryRouteTelemetryStore()
        store.record(trace("a"))
        val uploader = RecordingUploader(UploadOutcome.Success)
        val worker = RouteTelemetryUploader(store, uploader.asUploader(), TelemetryPermission(emitMetrics = false))

        val result = worker.upload()

        assertEquals(TelemetryUploadStatus.PermissionDenied, result.status)
        assertEquals(0, uploader.calls) // nothing left the device
        assertEquals(1, store.size()) // records untouched
    }

    @Test
    fun upload_emptyStore_isEmpty() = runTest {
        val uploader = RecordingUploader(UploadOutcome.Success)
        val worker = RouteTelemetryUploader(MemoryRouteTelemetryStore(), uploader.asUploader())

        val result = worker.upload()

        assertEquals(TelemetryUploadStatus.Empty, result.status)
        assertEquals(0, uploader.calls)
    }

    @Test
    fun upload_respectsMaxBatchSize_andDrainsAcrossRuns() = runTest {
        val store = MemoryRouteTelemetryStore()
        repeat(5) { store.record(trace("r$it")) }
        val worker = RouteTelemetryUploader(store, RecordingUploader(UploadOutcome.Success).asUploader())

        val first = worker.upload(UploadTelemetryPayload(maxBatchSize = 2))
        assertEquals(2, first.uploadedCount)
        assertEquals(3, store.size())

        val second = worker.upload(UploadTelemetryPayload(maxBatchSize = 2))
        assertEquals(2, second.uploadedCount)
        assertEquals(1, store.size())
    }

    @Test
    fun memoryStore_isBounded_dropsOldestBeyondCapacity() = runTest {
        val store = MemoryRouteTelemetryStore(maxRetained = 2)
        store.record(trace("old"))
        store.record(trace("mid"))
        store.record(trace("new")) // evicts "old"

        assertEquals(2, store.size())
        val ids = store.peek(10).map { it.trace.requestId }
        assertEquals(listOf("mid", "new"), ids)
    }

    @Test
    fun upload_duplicateRequestIds_onlyRemovesTheUploadedEntry() = runTest {
        // requestId is the (non-unique) request key string. Two traces can share it;
        // uploading the first must NOT drop the second unsent one.
        val store = MemoryRouteTelemetryStore()
        store.record(trace("dup"))
        store.record(trace("dup"))
        val worker = RouteTelemetryUploader(store, RecordingUploader(UploadOutcome.Success).asUploader())

        val result = worker.upload(UploadTelemetryPayload(maxBatchSize = 1))

        assertEquals(TelemetryUploadStatus.Uploaded, result.status)
        assertEquals(1, result.uploadedCount)
        assertEquals(1, store.size()) // the second same-id entry survives
    }

    @Test
    fun peek_assignsDistinctSequencesToDuplicateRequestIds() = runTest {
        val store = MemoryRouteTelemetryStore()
        store.record(trace("dup"))
        store.record(trace("dup"))
        val sequences = store.peek(10).map { it.sequence }
        assertEquals(2, sequences.toSet().size) // unique per buffered entry
    }

    @Test
    fun serializedRecord_multiAttempt_redactsEveryAttempt_whenMetadataDisabled() = runTest {
        // Guard against a regression that redacts only the first attempt's metadata.
        val multi = RouteTrace(
            requestId = "r-multi",
            key = "notes:summarize:r-multi",
            finalStatus = FinalStatus.Failed,
            attempts = listOf(
                ProviderAttemptTrace("local-runtime", ProviderKind.Local, AttemptOutcome.Failed, modelId = "gemma-secret"),
                ProviderAttemptTrace("cloud-vendor", ProviderKind.Cloud, AttemptOutcome.Succeeded, modelId = "gpt-secret"),
            ),
            finalProvider = "cloud-vendor",
        )
        val permission = TelemetryPermission(emitProviderMetadata = false, emitHashes = false)
        val json = Json.encodeToString(multi.toTelemetryRecord(permission))

        assertFalse(json.contains("local-runtime"))
        assertFalse(json.contains("cloud-vendor"))
        assertFalse(json.contains("gemma-secret"))
        assertFalse(json.contains("gpt-secret"))
        assertFalse(json.contains("notes:summarize"))
        // Non-identifying structure is retained.
        assertContains(json, "r-multi")
        assertContains(json, ProviderKind.Cloud.name)
    }

    @Test
    fun serializedRecord_withHashesEnabled_stillNeverContainsRawKey() = runTest {
        // Default permission keeps emitHashes=true; the hash must replace the raw key.
        val json = Json.encodeToString(trace("r1").toTelemetryRecord())
        assertFalse(json.contains("notes:summarize")) // raw key never serialized
        assertContains(json, "keyHash")
    }
}
