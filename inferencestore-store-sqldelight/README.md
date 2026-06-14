# inferencestore-store-sqldelight

A **prototype** SQLDelight-backed `InferenceArtifactStore` plus a persistent route-attempt
journal (`storage-model.md`). Artifacts and route attempts survive process restarts.

**Experimental.** Driver-agnostic and JVM-tested with an in-memory JDBC driver.

## Usage

The caller supplies an `InferenceStoreDatabase` built from a platform `SqlDriver`:

```kotlin
// JVM:     JdbcSqliteDriver("jdbc:sqlite:inference.db")
// Android: AndroidSqliteDriver(InferenceStoreDatabase.Schema, context, "inference.db")
// iOS:     NativeSqliteDriver(InferenceStoreDatabase.Schema, "inference.db")
val driver = /* platform driver */
InferenceStoreDatabase.Schema.create(driver) // first run only

val store = SqlDelightArtifactStore(InferenceStoreDatabase(driver))
store.write(artifact)                 // persist (privacy-redacted artifacts persist with null content)
store.reader(fingerprint).first()     // read back across restarts
store.recordAttempt(/* ... */)        // persist a route attempt
```

## What persists

- **Artifacts**: fingerprint, provider id/kind/boundary/model, `rawText`, trace, timestamps.
  Privacy is the caller's — a redacted artifact (null `output`/`rawText`) persists with
  null content (`redactedArtifact_persistsWithoutContent`).
- **Route attempts**: request id, provider, outcome, error category, timestamp.

**Prototype scope**: the typed `output` is not persisted (only `rawText`; the caller
re-decodes), and provider `capabilities`/`extra` are not round-tripped.

## Migrations

The schema is versioned with SQLDelight migrations (`*.sqm`). `1.sqm` adds
`validation_json`; `InferenceStoreDatabase.Schema.migrate(driver, old, new)` applies them.
The migration is tested (`migration_addsValidationColumn`): a v1 database is upgraded and
the new column verified.

## Retention guidance

The store does **not** auto-evict — retention is app-defined. Run a periodic cleanup, and
use `expires_at_epoch_ms` for TTL. Suggested policies (`storage-model.md`):

| Data | Retention |
| --- | --- |
| Summaries / general artifacts | ~30 days |
| Embeddings | until the input changes (fingerprint changes) |
| Route traces | ~7 days |
| Sensitive-request traces | hash-only, ~24 hours |
| Model inventory | ~1 day |

For sensitive data, persist redacted artifacts (null output, hash-only traces), encrypt
the database at rest (e.g. SQLCipher on Android/iOS), and honor per-key / delete-all
removal. Persist prompts/outputs only when `PrivacyPolicy.persistence` allows it.
