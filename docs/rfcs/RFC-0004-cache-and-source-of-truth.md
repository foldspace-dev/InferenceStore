# RFC-0004: Cache and artifact store

Status: Draft  
Generated: 2026-06-13

## Summary

Define cache and persistent artifact interfaces without claiming generated output is authoritative truth.

## Motivation

Store's `SourceOfTruth` is central, but inference artifacts are different from data. They are versioned generated outputs whose validity depends on prompt, input, model, schema, and policy.

## Proposal

Use:

- `InferenceCache` for simple read/write.
- `InferenceArtifactStore` for observable persistent artifacts.
- `RouteJournal` for attempts/failures/cooldowns.

Avoid naming public API `SourceOfTruth`.

## Artifact fingerprint

```kotlin
data class InferenceFingerprint(
    val key: InferenceKey,
    val inputHash: String,
    val promptVersion: String?,
    val outputVersion: String?,
    val privacyClass: String,
    val policyVersion: String?
)
```

## MVP scope

- interfaces
- in-memory implementation
- cache outcome events
- no SQLDelight implementation yet

## Post-MVP

- SQLDelight implementation
- encrypted recipes
- route journal persistence
- provider inventory persistence
