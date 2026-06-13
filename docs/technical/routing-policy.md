# Routing policy

Updated: 2026-06-13

## Purpose

Routing policy decides which provider should handle a request and when fallback or repair is allowed.

Policy must be explicit, inspectable, and testable. Privacy is not merely a policy preference: the execution controller enforces privacy before provider invocation.

## Inputs to routing

- request key;
- input/output type;
- required capabilities;
- privacy policy and provider boundary;
- cache policy;
- provider availability;
- provider capability report;
- network status;
- device status;
- battery/thermal status if provided;
- estimated latency;
- estimated cost;
- timeout/retry budget;
- route journal / cooldowns;
- remote config if provided by app code.

## Policy outputs

A policy returns an `InferenceRoute`:

```kotlin
data class InferenceRoute(
    val attempts: List<RouteAttempt>,
    val fallback: FallbackPolicy,
    val constraints: RouteConstraints,
    val explanation: RouteExplanation
)
```

## Canonical MVP built-in policies

There are five built-in policy presets for MVP.

### `localOnly`

Use only local or on-device platform providers.

Fails if no allowed provider is available or capable.

Use for:

- sensitive data with no cloud permission;
- offline-only features;
- enterprise restrictions;
- user-selected local-only mode.

`PrivacyClass.LocalOnly` also rejects cloud providers even if a different policy is accidentally selected.

### `cloudOnly`

Use only cloud or remote providers that pass privacy checks.

Use for:

- high-quality generation;
- long context;
- capabilities local providers do not support;
- server-authoritative outputs.

### `preferLocalThenCloud`

Try local/platform first. Fall back to cloud when privacy, deadline, and fallback policy allow.

Default fallback reasons:

- provider unavailable;
- model missing;
- unsupported capability;
- attempt timeout;
- transient error;
- rate limit;
- parser/validator failure when configured.

### `preferCloudThenLocal`

Try cloud first. Fall back to local when cloud is unavailable, rate limited, timed out, or disallowed by connectivity and local can satisfy the request.

Use for:

- cloud-quality default;
- offline degradation;
- rate-limit fallback;
- cloud outage fallback.

### `validateLocalThenCloudRepair`

Try local first. If final validation or parsing fails, call cloud for repair or regeneration when privacy allows.

Use for:

- structured extraction;
- data transformation;
- deterministic formatting;
- lower-cost first pass.

## Post-MVP policies

Not MVP:

- `shadowLocal` for evaluation;
- `raceLocalAndCloud` for speculative latency;
- remote-configured policy control plane;
- complex cost-aware policy DSL.

## Fallback categories

Fallback categories and default behavior are canonical in `docs/technical/error-fallback-mapping.md`.

```kotlin
sealed interface FallbackReason {
    data object ProviderUnavailable : FallbackReason
    data object CapabilityUnsupported : FallbackReason
    data object Timeout : FallbackReason
    data object TransientError : FallbackReason
    data object RateLimited : FallbackReason
    data object SchemaInvalid : FallbackReason
    data object ValidatorRejected : FallbackReason
    data object OutputParserFailed : FallbackReason
    data object QualityBelowThreshold : FallbackReason
    data object PolicyRequested : FallbackReason
    data object PermanentError : FallbackReason
    data object Unknown : FallbackReason
}
```

## Fallback guardrails

A fallback must never violate privacy policy.

Examples:

- `PrivacyClass.LocalOnly` request cannot fall back to cloud.
- `Personal` default cannot fall back to cloud unless approved cloud is explicitly configured.
- `Sensitive` request cannot fall back to cloud without explicit app-level approval.
- `NoPersistence` settings cannot write prompt/output artifacts.
- Caller cancellation cannot fallback.

## Route explanation

Every route should include a machine-readable explanation.

Example:

```json
{
  "policy": "preferLocalThenCloud",
  "attempts": [
    {
      "provider": "litertlm-local",
      "decision": "selected",
      "reason": "local provider available and supports textGeneration"
    },
    {
      "provider": "openai-compatible",
      "decision": "fallback",
      "reason": "local output failed JsonSchema validator"
    }
  ],
  "privacy": {
    "class": "Personal",
    "cloud": "ApprovedProviders(openai-compatible)"
  }
}
```

## Policy DSL future

A future DSL could look like:

```kotlin
val policy = policy {
    require {
        privacyGate() // invokes canonical PrivacyPolicy checks
    }

    prefer {
        providers(kind = ProviderKind.Local)
            .whenCapability(Capability.TextGeneration)
            .whenAvailable()
            .whenLatencyBelow(2.seconds)
    }

    fallback {
        to(kind = ProviderKind.Cloud)
            .whenReason(FallbackReason.SchemaInvalid)
            .whenPrivacyAllowsProvider()
            .whenWithinRequestDeadline()
    }
}
```

## Policy test examples

```kotlin
@Test
fun localOnlyDoesNotCallCloud() = runTest {
    val result = store.generate(request.copy(privacy = PrivacyPolicy.localOnly()))

    assertRoute(result.trace) {
        didNotAttempt("cloud")
        failedBecause(PolicyViolation.CloudNotAllowed)
    }
}
```

```kotlin
@Test
fun schemaFailureFallsBackToCloud() = runTest {
    val result = store.generate(structuredRequest)

    assertRoute(result.trace) {
        attempted("local")
        fellBackTo("cloud", FallbackReason.SchemaInvalid)
        completedWith("cloud")
    }
}
```

## Policy configuration and remote config

Do not build remote config into core. Provide hooks:

```kotlin
interface PolicyProvider {
    suspend fun currentPolicy(request: InferenceRequest<*>): InferencePolicy
}
```

App teams can integrate Firebase Remote Config, LaunchDarkly, Statsig, or their own config systems.

## Route journal

A route journal can prevent repeated bad decisions.

Examples:

- local model timed out repeatedly -> cooldown local provider for 10 minutes;
- cloud provider rate limited -> prefer local temporarily;
- model unavailable -> avoid availability probe for a configured TTL.

MVP can keep this in memory; persistent journal is post-MVP.
