# Artifact store

Generated output is a cached computation, not authoritative truth — it's invalidated by
model/prompt/policy changes. So the public API is an **artifact cache/store**, not a
Store `SourceOfTruth` (the Store analogy still helps: read-through with a fingerprint
key).

- `InferenceCache` — fingerprint-keyed `read`/`write`/`clear`/`clearAll`.
  `MemoryInferenceCache` is the in-memory implementation (TTL, optional size cap).
- `InferenceArtifactStore` — a more explicit persistent source-of-truth-like store
  (interface; SQLDelight implementation is post-MVP).
- `InferenceArtifact` — the stored result with provider/model/trace/validation metadata;
  output/raw text can be omitted for a redacted artifact.

Keys are content-free **fingerprints** (hashes/ids/versions, never raw input). The
engine reads before provider execution and writes after success — writes happen **only
when both** the cache policy and `PrivacyPolicy.persistence.persistOutput` allow.

!!! warning "Multi-user scoping"
    The fingerprint does not include user identity — scope the `InferenceKey` (e.g.
    include a user id) so cached artifacts never leak across users.

Learn more: [storage model](../technical/storage-model.md),
[Security & privacy](../guides/security-and-privacy.md).
