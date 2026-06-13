# Storage model

Generated: 2026-06-13

## Purpose

Storage supports:

- memory cache
- persistent artifact store
- route journal
- provider availability cache
- model inventory metadata
- validation/evaluation records

Storage must be privacy-aware and optional.

## Storage layers

### `InferenceCache`

A simple in-memory or persistent cache keyed by fingerprint.

```kotlin
interface InferenceCache {
    suspend fun <Output : Any> read(
        fingerprint: InferenceFingerprint,
        output: OutputSpec<Output>
    ): InferenceArtifact<Output>?

    suspend fun <Output : Any> write(
        artifact: InferenceArtifact<Output>
    )

    suspend fun clear(key: InferenceKey)
    suspend fun clearAll()
}
```

### `InferenceArtifactStore`

More explicit persistent source-of-truth-like store.

```kotlin
interface InferenceArtifactStore {
    fun reader(fingerprint: InferenceFingerprint): Flow<InferenceArtifactRecord?>
    suspend fun write(record: InferenceArtifactRecord)
    suspend fun delete(fingerprint: InferenceFingerprint)
    suspend fun deleteAll()
}
```

### `RouteJournal`

Tracks route attempts, failures, cooldowns, and model/provider health.

```kotlin
interface RouteJournal {
    suspend fun recordAttempt(trace: ProviderAttemptTrace)
    suspend fun recentFailures(providerId: ProviderId): List<RouteFailure>
    suspend fun cooldown(providerId: ProviderId): Cooldown?
    suspend fun clear(providerId: ProviderId)
}
```

### `ProviderInventory`

Stores known model/provider availability.

```kotlin
interface ProviderInventory {
    suspend fun get(providerId: ProviderId): ProviderInventoryRecord?
    suspend fun put(record: ProviderInventoryRecord)
}
```

## SQLDelight schema sketch

```sql
CREATE TABLE inference_artifact (
    fingerprint TEXT NOT NULL PRIMARY KEY,
    key_namespace TEXT NOT NULL,
    key_id TEXT NOT NULL,
    key_version TEXT,
    input_hash TEXT NOT NULL,
    prompt_version TEXT,
    output_version TEXT,
    privacy_class TEXT NOT NULL,
    provider_id TEXT NOT NULL,
    provider_kind TEXT NOT NULL,
    model_id TEXT,
    model_version TEXT,
    raw_output TEXT,
    typed_output_json TEXT,
    trace_json TEXT NOT NULL,
    validation_json TEXT,
    created_at_epoch_ms INTEGER NOT NULL,
    expires_at_epoch_ms INTEGER
);

CREATE TABLE route_attempt (
    id TEXT NOT NULL PRIMARY KEY,
    request_id TEXT NOT NULL,
    fingerprint TEXT,
    provider_id TEXT NOT NULL,
    model_id TEXT,
    status TEXT NOT NULL,
    fallback_reason TEXT,
    started_at_epoch_ms INTEGER NOT NULL,
    first_token_at_epoch_ms INTEGER,
    completed_at_epoch_ms INTEGER,
    error_category TEXT,
    usage_json TEXT
);

CREATE TABLE provider_inventory (
    provider_id TEXT NOT NULL PRIMARY KEY,
    availability TEXT NOT NULL,
    reason TEXT,
    model_id TEXT,
    model_version TEXT,
    capabilities_json TEXT NOT NULL,
    checked_at_epoch_ms INTEGER NOT NULL,
    expires_at_epoch_ms INTEGER
);
```

## Privacy considerations

Storage must support:

- no prompt persistence
- no output persistence
- hash-only traces
- encrypted store implementation hook
- per-key deletion
- delete all
- metadata redaction

## Artifact retention

Retention should be app-defined.

Examples:

- summaries: 30 days
- embeddings: until input changes
- route traces: 7 days
- sensitive request traces: hash-only, 24 hours
- model inventory: 1 day

## Source of truth terminology

Use `ArtifactStore` rather than `SourceOfTruth` in public API.

Reason:

- Generated output is often not authoritative truth.
- It may be a cached computation artifact.
- It can be invalidated by model/prompt/policy changes.

Docs can explain the Store analogy.

## Storage in MVP

MVP should include interfaces and in-memory implementation only.

Post-MVP:

- SQLDelight artifact store
- encrypted storage recipe
- route journal persistence
- migration docs

## Open questions

1. Should artifact store support partial streamed outputs? Recommendation: no in MVP.
2. Should route traces persist if output is not persisted? Recommendation: yes, but redacted.
3. Should provider inventory be separate from route journal? Recommendation: yes.
4. Should storage include prompts? Recommendation: opt-in only.
