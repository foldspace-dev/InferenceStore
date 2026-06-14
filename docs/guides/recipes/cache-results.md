# Cache results

**Goal:** reuse a previous result for an equivalent request instead of running a provider
again — with a privacy-safe, fingerprint-keyed cache and a TTL.

## 1. Attach a cache to the store

```kotlin
val store = InferenceStore.build {
    provider(onDevice); provider(cloud)
    cache = MemoryInferenceCache(maxEntries = 1_000)   // in-memory, bounded
}
```

The engine reads the cache **before** running a provider and writes **after** a successful
result. The key is a content-free [fingerprint](../../concepts/artifact-store.md) — input
hash, prompt/output/policy/privacy versions — never the raw input.

## 2. Opt into writes (and allow persistence)

Reads are allowed by default; writes are denied by default and gated on privacy. Enable
both for a cacheable request:

```kotlin
val privacy = PrivacyPolicy(
    classification = PrivacyClass.Public,
    cloud = CloudPermission.Allowed,
    persistence = PersistencePermission(persistOutput = true),  // permit storing the output
)

val request = InferenceRequest
    .text(InferenceKey("notes.summary", note.id), note.body, privacy = privacy)
    .copy(cache = CachePolicy(read = CacheAccess.Allow, write = CacheAccess.Allow, ttl = 1.hours))

store.generate(request)   // miss → runs a provider, then writes the result
store.generate(request)   // hit  → served from cache
```

## 3. Confirm a cache hit

```kotlin
val result = store.generate(request)
println(result.trace?.servedFromCache)   // true on a hit
```

!!! warning "Scope the key per user"
    The fingerprint does **not** include user identity. In a multi-user app, put a user id
    in the `InferenceKey` (e.g. `InferenceKey("notes.summary", "$userId:${note.id}")`) so
    cached results never leak across users.

!!! tip "Durable persistence"
    `MemoryInferenceCache` is process-local. For durable, cross-launch artifacts, persist
    with the SQLDelight artifact store — see the [storage model](../../technical/storage-model.md).

## See also

- [Artifact store](../../concepts/artifact-store.md)
- [Storage model](../../technical/storage-model.md)
- [Privacy model](../../technical/privacy-model.md) — persistence permissions
