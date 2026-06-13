# Security and privacy

Updated: 2026-06-13

## Principle

Privacy must be a type-level concern, not a README warning.

Inference requests often contain personal, sensitive, or proprietary data. The library must make it hard to accidentally route sensitive prompts to cloud providers, logs, caches, or telemetry.

## Normative source

`docs/technical/privacy-model.md` is the source of truth for privacy classes, cloud permission, persistence defaults, telemetry defaults, provider boundary metadata, and enforcement behavior.

This file explains the security posture and checklist around that model.

## Privacy policy model

Canonical shape:

```kotlin
data class PrivacyPolicy(
    val classification: PrivacyClass,
    val cloud: CloudPermission,
    val persistence: PersistencePermission,
    val telemetry: TelemetryPermission,
    val redaction: RedactionPolicy = RedactionPolicy.Default,
    val providerBoundary: ProviderBoundaryRequirement = ProviderBoundaryRequirement.Default
)
```

Built-in classes:

- `Public`
- `Internal`
- `Personal`
- `Sensitive`
- `LocalOnly`
- `Custom(value)`

`AllowsCloud` is not a class. Cloud behavior is controlled by `CloudPermission`.

## Default posture

`PrivacyPolicy.Default` is strict:

- class: `Personal`;
- cloud: denied;
- prompt persistence: false;
- output persistence: false;
- trace persistence: redacted only;
- telemetry: metadata/hash only.

Demos may use `PrivacyPolicy.publicData()` for harmless prompts. Production examples should set privacy explicitly.

## Enforcement

The execution controller must enforce privacy before provider invocation.

Provider adapters should not be trusted to enforce privacy.

```kotlin
val decision = request.privacy.allowsProvider(provider.metadata)
if (decision is PrivacyDecision.Deny) {
    trace.recordProviderRejected(provider.id, decision.reason)
    return decision.reason
}
```

Denied providers are not invoked, not warmed up with request content, and not allowed to inspect prompts.

## Logging

Default:

- no raw prompts;
- no raw outputs;
- no headers/secrets;
- provider/model IDs allowed;
- request IDs allowed;
- fingerprints allowed;
- route reasons allowed;
- error categories allowed.

Debug logging with raw content must require explicit opt-in and should be impossible to enable accidentally through generic log-level changes.

## Secrets

Provider adapters must:

- accept API keys through configuration or app-provided token providers;
- avoid storing keys in route traces;
- avoid exposing keys in errors;
- support app-specific secure storage patterns;
- document platform-specific requirements;
- recommend backend token brokering for public mobile clients when appropriate.

Core should not invent universal Keychain/Keystore abstractions in MVP. Adapter docs should include recipes.

## Cloud boundary

Every provider declares a `ProviderPrivacyBoundary`. This is metadata, not a legal guarantee, but it is required for routing and auditability.

Examples:

- LiteRT-LM local adapter: `LocalProcess`.
- Apple on-device model: `PlatformOnDevice`.
- Firebase hybrid convenience provider: `PlatformHybrid`.
- OpenAI-compatible cloud adapter: `ThirdPartyCloud` unless configured as an app-local server.
- App backend proxy: `AppBackend`.

## User consent and UX

The library should expose enough metadata for apps to build UX such as:

- “This answer was generated on-device.”
- “Cloud was used because the on-device model was unavailable.”
- “Private notes never leave this device.”
- “Cloud fallback was blocked by privacy settings.”

## Deletion

Storage interfaces must support:

- delete by key;
- delete by fingerprint;
- delete all;
- delete by privacy class if implemented;
- artifact pruning;
- route trace pruning.

## Threat model

### Accidental cloud routing

Mitigation:

- request privacy policy;
- execution-controller privacy gate;
- tests that assert provider not called;
- route trace rejected-provider records.

### Prompt leakage through logs

Mitigation:

- redacted monitor events;
- no raw content in errors by default;
- secret redaction in adapters.

### Persistent sensitive output

Mitigation:

- persistence permissions;
- cache write opt-in;
- storage redaction;
- fingerprint invalidation on privacy changes.

### Provider metadata confusion

Mitigation:

- provider kind and privacy boundary required;
- route trace shows provider used;
- hybrid providers must disclose whether cloud may be used.

### Cross-user cache leak

Mitigation:

- app includes user/account scope in `InferenceKey` or fingerprint metadata;
- docs warn about multi-user environments;
- privacy class/policy version included in fingerprint.

### API-key exposure

Mitigation:

- no secrets in traces or errors;
- app-owned secure storage recipes;
- recommend backend proxy/token broker for third-party cloud APIs.

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
    containsNoSecrets()
}
```

## Security checklist for adapters

- [ ] API keys are not logged.
- [ ] Raw request/response logging is opt-in.
- [ ] Errors are mapped and redacted.
- [ ] Provider kind is accurate.
- [ ] Provider privacy boundary is accurate.
- [ ] Cloud/local/hybrid behavior is documented.
- [ ] Cancellation is supported or documented.
- [ ] Timeouts are mapped correctly.
- [ ] Telemetry payloads are redacted.
- [ ] Tests cover redaction.

## Resolved questions

1. Should privacy defaults be strict enough to annoy developers? **Yes.** Strict defaults plus explicit demo helpers.
2. Should cloud be denied by default? **Yes for `PrivacyPolicy.Default`; `Public` helper may allow cloud.**
3. Should provider privacy metadata be required? **Yes.**
4. Should storage encryption be built in? **No in core; provide hooks and recipes.**
