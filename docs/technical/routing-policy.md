# Routing policy

Generated: 2026-06-13

## Purpose

Routing policy decides which provider should handle a request and when fallback or repair is allowed.

Policy must be explicit, inspectable, and testable.

## Inputs to routing

- request key
- input/output type
- required capabilities
- privacy classification
- cache policy
- provider availability
- provider capability report
- network status
- device status
- battery/thermal status if provided
- estimated latency
- estimated cost
- route journal / cooldowns
- remote config if provided

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

## Built-in policies

### `localOnly`

Use only local/platform providers.

Fails if no provider is available or capable.

Use for:

- sensitive data
- offline-only features
- enterprise restrictions
- user-selected privacy mode

### `cloudOnly`

Use only cloud/remote providers.

Use for:

- high-quality generation
- long context
- capabilities local providers do not support
- server-authoritative outputs

### `preferLocalThenCloud`

Try local/platform first. Fall back to cloud when allowed.

Fallback reasons:

- local unavailable
- model missing
- unsupported capability
- timeout
- transient error
- validation failure

### `preferCloudThenLocal`

Try cloud first. Fall back local when cloud unavailable.

Use for:

- cloud-quality default
- offline degradation
- rate limit fallback
- cloud outage fallback

### `validateLocalRepairWithCloud`

Try local first. If validation fails, call cloud for repair or regeneration.

Use for:

- structured extraction
- data transformation
- deterministic formatting
- lower-cost first pass

### `shadowLocal`

Use cloud result for the user while running local in shadow mode for evaluation.

Use for:

- adapter validation
- model rollout
- quality comparison

MVP should not implement shadow mode, but the policy model should not block it.

### `raceLocalAndCloud`

Start local and cloud concurrently, return whichever satisfies policy first.

Use for:

- latency-sensitive features
- speculative execution

Not MVP.

## Fallback categories

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
}
```

## Fallback guardrails

A fallback must never violate privacy policy.

Examples:

- `LocalOnly` request cannot fall back to cloud.
- `Sensitive` request cannot fall back to providers without approved privacy boundary.
- `NoPersistence` request cannot write artifact traces containing prompt/output content.

## Route explanation

Every route should include a machine-readable explanation.

Example:

```json
{
  "policy": "preferLocalThenCloud",
  "attempts": [
    {
      "provider": "apple-foundation-models",
      "decision": "selected",
      "reason": "local provider available and supports textGeneration"
    },
    {
      "provider": "openai-compatible",
      "decision": "fallback",
      "reason": "local output failed JsonSchema validator"
    }
  ]
}
```

## Policy DSL future

A future DSL could look like:

```kotlin
val policy = policy {
    require {
        whenPrivacy(LocalOnly) {
            allowOnly(ProviderKind.Local, ProviderKind.Platform)
        }
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
            .whenPrivacyAllowsCloud()
    }
}
```

## Policy test examples

```kotlin
@Test
fun localOnlyDoesNotCallCloud() = runTest {
    val result = store.generate(request.copy(privacy = PrivacyPolicy.LocalOnly))

    assertRoute(result.trace) {
        attempted("local")
        didNotAttempt("cloud")
        failedBecause<ProviderUnavailable>()
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

- local model timed out repeatedly -> cooldown local provider for 10 minutes
- cloud provider rate limited -> prefer local temporarily
- model unavailable -> avoid availability probe for a configured TTL

MVP can keep this in memory; persistent journal is post-MVP.
