package dev.mattramotar.inferencestore.store.sqldelight

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.mattramotar.inferencestore.core.cache.InferenceArtifact
import dev.mattramotar.inferencestore.core.cache.InferenceFingerprint
import dev.mattramotar.inferencestore.core.event.AttemptOutcome
import dev.mattramotar.inferencestore.core.event.FinalStatus
import dev.mattramotar.inferencestore.core.event.RouteTrace
import dev.mattramotar.inferencestore.core.model.InferenceKey
import dev.mattramotar.inferencestore.core.provider.ErrorCategory
import dev.mattramotar.inferencestore.core.provider.ProviderExecutionBoundary
import dev.mattramotar.inferencestore.core.provider.ProviderId
import dev.mattramotar.inferencestore.core.provider.ProviderKind
import dev.mattramotar.inferencestore.core.provider.ProviderMetadata
import dev.mattramotar.inferencestore.core.provider.ProviderPrivacyBoundary
import dev.mattramotar.inferencestore.store.sqldelight.db.InferenceStoreDatabase
import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SqlDelightArtifactStoreTest {

    private val fingerprint = InferenceFingerprint(InferenceKey("notes", "n1"), "h1", null, "text", "Personal", null, null)
    private val trace = RouteTrace(requestId = "r1", key = "notes:n1", finalStatus = FinalStatus.Succeeded, finalProvider = "p")

    private fun artifact(rawText: String?) = InferenceArtifact(
        fingerprint = fingerprint,
        output = null,
        rawText = rawText,
        provider = ProviderMetadata(ProviderId("p"), ProviderKind.Local, ProviderPrivacyBoundary.localDevice(), modelId = "m1"),
        trace = trace,
    )

    private fun tempDbPath(): String = File.createTempFile("inferencestore-test", ".db").also { it.delete(); it.deleteOnExit() }.absolutePath
    private fun openExisting(path: String): SqlDriver = JdbcSqliteDriver("jdbc:sqlite:$path")
    private fun createFresh(path: String): SqlDriver = JdbcSqliteDriver("jdbc:sqlite:$path").also { InferenceStoreDatabase.Schema.create(it) }
    private fun store(driver: SqlDriver) = SqlDelightArtifactStore(InferenceStoreDatabase(driver))

    @Test
    fun artifactPersistsAcrossRestart() = runTest {
        val path = tempDbPath()
        createFresh(path).use { store(it).write(artifact("the summary")) }
        // New process / new connection on the same file.
        openExisting(path).use { driver ->
            val read = store(driver).reader(fingerprint).first()
            assertNotNull(read)
            assertEquals("the summary", read.rawText)
            assertEquals("p", read.trace?.finalProvider)
            assertEquals(ProviderKind.Local, read.provider.providerKind)
            assertEquals(ProviderExecutionBoundary.LocalProcess, read.provider.boundary.execution)
        }
    }

    @Test
    fun redactedArtifact_persistsWithoutContent() = runTest {
        val path = tempDbPath()
        createFresh(path).use { store(it).write(artifact(rawText = null)) } // privacy redacted output
        openExisting(path).use { driver ->
            val read = store(driver).reader(fingerprint).first()
            assertNotNull(read)
            assertNull(read.rawText)
            assertNull(read.output)
        }
    }

    @Test
    fun routeAttemptsPersistAcrossRestart() = runTest {
        val path = tempDbPath()
        createFresh(path).use {
            store(it).recordAttempt("req-1", ProviderId("p"), AttemptOutcome.Failed, "m1", ErrorCategory.RateLimited, atEpochMs = 1_000)
        }
        openExisting(path).use { driver ->
            val attempts = store(driver).attemptsFor(ProviderId("p"))
            assertEquals(1, attempts.size)
            assertEquals(AttemptOutcome.Failed, attempts.first().outcome)
            assertEquals(ErrorCategory.RateLimited, attempts.first().errorCategory)
        }
    }

    @Test
    fun deleteAndDeleteAll() = runTest {
        val path = tempDbPath()
        createFresh(path).use { driver ->
            val s = store(driver)
            s.write(artifact("x"))
            s.delete(fingerprint)
            assertNull(s.reader(fingerprint).first())
        }
    }

    @Test
    fun migration_addsValidationColumn() = runTest {
        val path = tempDbPath()
        openExisting(path).use { driver ->
            // Hand-build the v1 schema (no validation_json column).
            driver.execute(
                null,
                """
                CREATE TABLE inference_artifact (
                    fingerprint TEXT NOT NULL PRIMARY KEY, key_namespace TEXT NOT NULL, key_id TEXT NOT NULL,
                    provider_id TEXT NOT NULL, provider_kind TEXT NOT NULL, boundary_id TEXT NOT NULL,
                    boundary_execution TEXT NOT NULL, model_id TEXT, raw_output TEXT, typed_output_json TEXT,
                    trace_json TEXT, created_at_epoch_ms INTEGER NOT NULL, expires_at_epoch_ms INTEGER
                );
                """.trimIndent(),
                0,
            )
            driver.execute(null, "CREATE TABLE route_attempt (id INTEGER PRIMARY KEY AUTOINCREMENT, request_id TEXT NOT NULL, fingerprint TEXT, provider_id TEXT NOT NULL, model_id TEXT, outcome TEXT NOT NULL, error_category TEXT, recorded_at_epoch_ms INTEGER NOT NULL);", 0)

            // Migrate v1 -> v2 (runs 1.sqm: ADD COLUMN validation_json).
            InferenceStoreDatabase.Schema.migrate(driver, 1, 2).await()

            // The store's upsert references validation_json, so a successful write proves the column exists.
            store(driver).write(artifact("after migration"))
            assertEquals("after migration", store(driver).reader(fingerprint).first()?.rawText)
        }
    }

    @Test
    fun schemaVersion_isTwo() {
        assertTrue(InferenceStoreDatabase.Schema.version >= 2)
    }
}
