# Security and privacy

Generated: 2026-06-13

## Principle

Privacy must be a type-level concern, not a README warning.

Inference requests often contain personal, sensitive, or proprietary data. The library must make it hard to accidentally route sensitive prompts to cloud providers, logs, caches, or telemetry.

## Privacy policy model

```kotlin
data class PrivacyPolicy(
    val classification: PrivacyClassification,
    val cloud: CloudPermission,
    val persistence: PersistencePermission,
    val telemetry: TelemetryPermission,
    val redaction: RedactionPolicy = RedactionPolicy.Default
)
```

## Classification

```kotlin
sealed interface PrivacyClassification {
    data object Public : PrivacyClassification
    data object Internal : PrivacyClassification
    data object Personal : PrivacyClassification
    data object Sensitive : PrivacyClassification
    data object LocalOnly : PrivacyClassification
    data class Custom(val value: String) : PrivacyClassification
}
```

## Cloud permission

```kotlin
sealed interface CloudPermission {
    data object Allowed : CloudPermission
    data object Denied : CloudPermission
    data class AllowedProviders(val providers: Set<ProviderId>) : CloudPermission
}
```

## Persistence permission

```kotlin
data class PersistencePermission(
    val persistPrompt: Boolean = false,
    val persistOutput: Boolean = false,
    val persistTrace: Boolean = true,
    val persistTraceContent: Boolean = false
)
```

## Telemetry permission

```kotlin
data class TelemetryPermission(
    val emitMetrics: Boolean = true,
    val emitPrompt: Boolean = false,
    val emitOutput: Boolean = false,
    val emitHashes: Boolean = true
)
```

## Default privacy profiles

### Public

- cloud allowed
- output persistence allowed if cache policy allows
- prompt persistence opt-in
- metrics allowed

### Personal

- cloud allowed only through approved providers
- prompt persistence false
- output persistence opt-in
- redacted telemetry

### Sensitive

- cloud denied by default
- prompt persistence false
- output persistence false by default
- trace hash-only

### LocalOnly

- cloud denied
- remote providers denied
- prompt persistence false
- telemetry hash-only
- result cache opt-in

## Policy enforcement

The execution controller must enforce privacy before provider invocation.

Provider adapters should not be trusted to enforce privacy.

```kotlin
if (!privacy.allows(provider)) {
    return PolicyViolation.CloudNotAllowed
}
```

## Logging

Default:

- no raw prompts
- no raw outputs
- no headers/secrets
- provider/model IDs allowed
- request IDs allowed
- fingerprints allowed
- route reasons allowed

Debug logging with raw content must require explicit opt-in.

## Secrets

Provider adapters must:

- accept API keys through configuration
- avoid storing keys in route traces
- avoid exposing keys in errors
- support app-specific secure storage patterns
- document platform-specific requirements

## Cloud boundary

A provider should declare:

```kotlin
data class ProviderPrivacyBoundary(
    val local: Boolean,
    val cloud: Boolean,
    val vendor: String?,
    val dataRetention: DataRetention?,
    val trainingUse: TrainingUse?,
    val region: String?
)
```

This is metadata, not a legal guarantee.

## User consent

The library should expose enough metadata for apps to build UX such as:

- “This answer was generated on-device.”
- “Cloud was used because the on-device model was unavailable.”
- “Private notes never leave this device.”

## Deletion

Storage interfaces must support:

- delete by key
- delete by fingerprint
- delete all
- delete by privacy class if implemented
- artifact pruning

## Threat model

### Accidental cloud routing

Mitigation:

- request privacy policy
- policy guardrail
- tests that assert provider not called

### Prompt leakage through logs

Mitigation:

- redacted monitor events
- no raw content in errors by default

### Persistent sensitive output

Mitigation:

- persistence permissions
- cache write opt-in
- storage redaction

### Provider metadata confusion

Mitigation:

- provider kind and privacy boundary required
- route trace shows provider used

### Cross-user cache leak

Mitigation:

- app includes user/account scope in `InferenceKey` or fingerprint metadata
- docs warn about multi-user environments

## Testkit privacy assertions

```kotlin
assertRoute(result.trace) {
    didNotAttempt("cloud")
    rejected("cloud", reason = PolicyViolation.CloudNotAllowed)
}
```

```kotlin
assertMonitorEvents(monitor) {
    containsNoRawPrompt()
    containsNoRawOutput()
}
```

## Security checklist for adapters

- [ ] API keys are not logged.
- [ ] Raw request/response logging is opt-in.
- [ ] Errors are mapped and redacted.
- [ ] Provider kind is accurate.
- [ ] Cloud/local boundary is documented.
- [ ] Cancellation is supported or documented.
- [ ] Telemetry payloads are redacted.
- [ ] Tests cover redaction.

## Open questions

1. Should privacy defaults be strict enough to annoy developers? Recommendation: yes, with clear opt-in.
2. Should cloud be denied by default? Recommendation: no for `Default`, yes for `Sensitive` and `LocalOnly`.
3. Should provider privacy metadata be required? Recommendation: yes.
4. Should storage encryption be built-in? Recommendation: no in core; provide hooks and recipes.
