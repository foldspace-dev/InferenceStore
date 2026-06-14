# Security & privacy guide

How InferenceStore keeps prompts and outputs where they belong, and how to configure
it. The normative model is [`privacy-model.md`](../technical/privacy-model.md) and
[`security-privacy.md`](../technical/security-privacy.md); this is the practical guide.

The one-line guarantee: **the privacy gate runs before any provider work and cannot be
bypassed by a routing policy.** A disallowed provider is never probed, never invoked,
and is recorded as rejected in the trace.

## The privacy model

Every request carries a `PrivacyPolicy`:

```kotlin
data class PrivacyPolicy(
    val classification: PrivacyClass = PrivacyClass.Personal,
    val cloud: CloudPermission = CloudPermission.Denied,
    val persistence: PersistencePermission = PersistencePermission(),
    val telemetry: TelemetryPermission = TelemetryPermission(),
    val redaction: RedactionPolicy = RedactionPolicy.Default,
    val providerBoundary: ProviderBoundaryRequirement = ProviderBoundaryRequirement.Default,
)
```

- **`classification`** — `Public`, `Internal`, `Personal`, `Sensitive`, `LocalOnly`, or
  `Custom(label)`. Folded into the cache fingerprint, so a classification change never
  reuses a cached result across classes.
- **`cloud`** — `Denied`, `Allowed`, or `ApprovedBoundaries(setOf(boundaryId, …))` to
  allow only specific vetted boundaries (e.g. your own app backend).
- **`persistence`** — `persistPrompt` / `persistOutput` / `persistTrace` /
  `persistTraceContent` (defaults: trace only, no prompt/output, no trace content).
- **`telemetry`** — what monitors may emit (defaults: metrics + hashes + provider
  metadata; **no** prompt/output).
- **`redaction`** — `redactPrompts` / `redactOutputs` (both default true).
- **`providerBoundary`** — minimum boundary requirement a provider must meet.

### Profiles

```kotlin
PrivacyPolicy.Default      // Personal, cloud DENIED, no prompt/output persistence — the strict default
PrivacyPolicy.publicData() // Public, cloud ALLOWED — for harmless/demo content; be explicit in production
PrivacyPolicy.localOnly()  // LocalOnly, cloud can NEVER be reached, whatever the routing policy
```

Compose a custom profile for your case — e.g. sensitive content that may use a vetted
backend but must never persist output:

```kotlin
PrivacyPolicy(
    classification = PrivacyClass.Sensitive,
    cloud = CloudPermission.ApprovedBoundaries(setOf(ProviderPrivacyBoundaryId("app-backend:acme"))),
    persistence = PersistencePermission(persistOutput = false),
)
```

## Local-only: cloud denial is enforced, not advisory

With `PrivacyPolicy.Default` (or `localOnly()`), cloud-capable providers
(`boundary.isCloudLike`) are refused by the gate before invocation — even under a
`preferLocalThenCloud` policy. This is verifiable: make the cloud provider the only
fallback and the local one unavailable; a zero invocation count proves the gate
refused cloud (the request fails rather than leaking), as in
`samples/notes-summary` (`NoteSummarizerTest.privacyGate_blocksCloud_evenWhenLocalUnavailable`):

```kotlin
val cloud = fakeProvider("cloud", ProviderKind.Cloud, ProviderPrivacyBoundary.thirdPartyCloud("acme")) { complete("nope") }
val store = InferenceStore.build {
    provider(fakeProvider("on-device", ProviderKind.Local) { availability = ProviderAvailability.Unavailable(UnavailableReason.ModelMissing) })
    provider(cloud)
    policy = Policies.preferLocalThenCloud()
}
assertFailsWith<InferenceException> { store.generate(request.copy(privacy = PrivacyPolicy.Default)) }
assertEquals(0, cloud.invocations) // cloud was never called
```

The trace records `rejected cloud -> PolicyViolation`, so denials are auditable without
any provider call.

## Redaction defaults

Defaults assume the worst — content stays out of telemetry and traces unless you opt in:

- **Monitor events** (`MonitorEvent`) are a redacted projection: `TokenEmitted` carries
  a cumulative *count*, never token text; `RequestFailed` carries only the
  `ErrorCategory`; no event carries a raw prompt or output. (See the observability
  guide / OSS-19.)
- **`RouteTrace`** holds ids, categories, statuses, and timings — never prompts/outputs.
  Safe to log and persist.
- **`ProviderError` / `InferenceError` `toString()` are redacted**: they print
  `category`/`source`/`retryAfter` only. `message`/`cause` may carry a raw provider body,
  so they are omitted from the string form — don't log them yourself.
- **Fingerprints** store only hashes/ids/versions, never raw input.

Tighten further by setting `telemetry`/`redaction` on the request; nothing loosens the
defaults implicitly.

## Cache persistence advice

- A result is written to the cache **only when both** the cache policy allows writes
  **and** `persistence.persistOutput` is true. Default privacy persists no prompt/output
  (only a redacted trace), so by default the cache never stores output.
- Caching is best-effort and never fails a request; the fingerprint key stores no raw
  content.
- **Multi-user scoping is the app's responsibility.** The fingerprint does NOT include
  user/account identity — scope the `InferenceKey` (e.g. include a user id in the key
  id) so cached artifacts never leak across users.
- The in-memory `MemoryInferenceCache` is process-local (demos/tests/UI reuse). For a
  persistent store, encrypt at rest, honor per-key/`clearAll` deletion, and persist
  redacted traces only (no prompt/output) unless `persistTraceContent` is set.

## API keys and secrets

- API keys are **app-supplied** via a suspend supplier (e.g.
  `OpenAiConfig.apiKey: suspend () -> String?`) — the library has no secret store.
- Keys are **never logged, traced, or placed in errors**. Keep them out of any
  `ProviderError.message` you populate from a raw response body.
- On public mobile/desktop clients, prefer routing third-party cloud calls through
  **your own app backend** (a vetted `appBackend` boundary, allowed via
  `CloudPermission.ApprovedBoundaries`) rather than shipping vendor keys in the app.

## Adapter checklist

When writing a provider adapter (see
[Writing a provider adapter](writing-a-provider-adapter.md)):

- [ ] Declare an **honest** `ProviderPrivacyBoundary` — a cloud endpoint must report a
      cloud-like boundary so the gate can refuse it. Mis-declaring it defeats privacy.
- [ ] Never log, trace, or embed API keys/secrets; read them from the app-supplied supplier.
- [ ] Map raw failures to `ErrorCategory` without leaking raw bodies into logged fields.
- [ ] Don't emit telemetry the request's `telemetry` policy forbids; don't echo prompts/outputs.
- [ ] Don't persist anything yourself — persistence is the engine/cache's gated concern.
