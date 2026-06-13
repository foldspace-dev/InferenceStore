# Caching, validation, and deduplication

Updated: 2026-06-13

## Principle

Inference artifacts are not ordinary cached data.

They are outputs of a model under a specific request, prompt version, model version, policy, and privacy context. They may be reused only when the application explicitly allows it.

## Request fingerprint

A fingerprint should include:

- key namespace/id/version;
- normalized input hash;
- prompt template ID/version;
- output spec/schema version;
- policy-relevant request metadata;
- privacy class;
- privacy policy version;
- policy version;
- model/provider constraints if policy requires them.

Example:

```kotlin
data class InferenceFingerprint(
    val key: InferenceKey,
    val inputHash: String,
    val promptVersion: String?,
    val outputVersion: String?,
    val privacyClass: String,
    val privacyPolicyVersion: String?,
    val policyVersion: String?
)
```

Raw input, raw prompts, and raw outputs are never part of the stored fingerprint.

## Cache policy

```kotlin
data class CachePolicy(
    val read: CacheRead = CacheRead.Allow,
    val write: CacheWrite = CacheWrite.Deny,
    val dedupe: DedupePolicy = DedupePolicy.Allow,
    val ttl: Duration? = null,
    val allowStaleWhileRevalidate: Boolean = false
)
```

Persistence flags live in `PrivacyPolicy.persistence`, not in ad hoc cache flags. A write happens only when:

```text
CachePolicy.write == Allow
AND PrivacyPolicy.persistence permits the content being written
AND artifact store accepts the artifact
```

## Privacy-sensitive defaults

The canonical defaults are in `docs/technical/privacy-model.md`.

Short version:

- `Public`: cloud allowed; prompt/output persistence still requires cache opt-in.
- `Internal`: approved cloud only; no prompt persistence by default.
- `Personal`: cloud denied by default; no prompt/output persistence by default.
- `Sensitive`: cloud denied by default; no prompt/output persistence by default.
- `LocalOnly`: cloud hard-denied; no prompt/output persistence by default.

## Cache layers

### Memory cache

Fast, process-local, optional.

Use for:

- repeated UI requests;
- deduping final outputs;
- route metadata;
- temporary artifacts.

### Artifact store

Persistent, optional.

Use for:

- generated summaries;
- structured extraction results;
- model availability status;
- route journal;
- validation outcomes.

### Semantic cache

Post-MVP only.

Semantic cache is difficult because similar prompts may not be equivalent. Defer until concrete use cases emerge.

## Artifact model

```kotlin
data class InferenceArtifact<Output : Any>(
    val fingerprint: InferenceFingerprint,
    val output: Output?,
    val rawText: String?,
    val provider: ProviderMetadata,
    val trace: RouteTrace,
    val validation: ValidationResult?,
    val createdAt: Instant,
    val expiresAt: Instant?,
    val metadata: Map<String, String>
)
```

`output` and `rawText` may be null/redacted when privacy permits only trace persistence.

## Artifact validity

An artifact is valid only if:

- fingerprint matches;
- TTL not expired;
- validator passes;
- model/provider version is acceptable;
- privacy policy allows reuse;
- privacy policy version is compatible;
- output parser still accepts it;
- app-defined validator accepts it.

## Validators

### Predicate validator

```kotlin
val nonBlank = OutputValidator<String> { text, _ ->
    if (text.isNotBlank()) ValidationResult.Pass
    else ValidationResult.Fail("blank output")
}
```

### JSON/schema validator

Validates final JSON output against schema or serializer.

### Domain validator

Examples:

- summary length under 150 words;
- extracted tasks have due dates;
- classification is one of allowed labels;
- citations are present;
- no placeholder values.

### Evaluator hook

A more expensive evaluator can score output quality. This may be local, cloud, or custom.

Not MVP unless implemented as a generic interface.

## Validation timing

MVP:

- final-output validation only.

Post-MVP:

- partial JSON validation;
- streaming guardrails;
- evaluator-based repair;
- side-by-side candidate selection.

## Fallback on validation failure

Example flow:

1. Local provider generates output.
2. Parser fails or schema invalid.
3. Validator emits `Fail`.
4. Policy checks whether fallback is allowed.
5. Privacy gate checks whether fallback provider may receive the request.
6. Cloud provider repairs/regenerates.
7. Cloud output validates.
8. Result trace includes both attempts.

## Deduplication

Dedupe means concurrent equivalent requests share execution.

Equivalent means:

- same fingerprint;
- same policy or compatible policy;
- same privacy/cache settings;
- same output spec;
- dedupe allowed;
- side-effect-free provider request.

Example:

```text
Screen A asks for note summary
Screen B asks for same note summary 20ms later before first token
=> one provider call, two collectors
```

## Dedupe fan-out contract

The canonical fan-out and cancellation rules live in `docs/technical/threading-dispatchers.md`.

MVP summary:

- `stream()` collectors can join an in-flight dedupe group only before first content event;
- late `stream()` collectors start a new provider call or read completed cache;
- `generate()` can join an in-flight compatible request until terminal result;
- upstream cancellation is reference counted;
- cancellation of one collector does not cancel upstream while other joined collectors remain.

## Dedupe constraints

Do not dedupe when:

- request has `dedupe = Deny`;
- request includes non-shareable metadata;
- privacy policy forbids sharing;
- streaming consumer joins after first content in MVP;
- provider has side effects;
- request uses tools with side effects.

## Store-inspired semantics

Store's request dedupe reduces duplicate network calls. InferenceStore dedupe reduces duplicate model invocations. The underlying reason is similar; the validity constraints are stricter.

## Cache invalidation examples

### Prompt version changed

Old artifact invalid.

### Output schema changed

Old artifact invalid.

### Model regression

Policy may invalidate artifacts from a model version.

### Privacy mode changed

If user toggles “private,” previously cloud-generated artifacts may become unusable for that feature.

### Privacy policy version changed

Cache reuse is denied unless app supplies a compatibility rule.

### Input changed

Input hash changes, artifact invalid.

## Resolved questions

1. Should raw prompts ever be persisted by default? **No.**
2. Should raw text be persisted when typed output exists? **Opt-in only.**
3. Should route traces be persisted separately from outputs? **Yes, redacted.**
4. Should cache be in MVP? **Interfaces and in-memory minimal implementation; persistent store later.**
5. Should semantic cache be part of strategy? **Later, use-case-driven.**
