# RFC-0003: Policy engine

Status: Accepted for MVP  
Updated: 2026-06-13

## Summary

Define route policy as a suspending function from request + provider candidates + context to route plan.

## Motivation

The primary value of InferenceStore is making local/cloud decisions explicit and testable.

## Proposal

```kotlin
fun interface InferencePolicy {
    suspend fun plan(
        request: InferenceRequest<*>,
        candidates: List<ProviderCandidate>,
        context: InferenceContext
    ): InferenceRoute
}
```

## Built-ins

- `localOnly`
- `cloudOnly`
- `preferLocalThenCloud`
- `preferCloudThenLocal`
- `validateLocalThenCloudRepair`

## Guardrails

The execution controller enforces privacy regardless of policy bugs.

## Decisions

- Policy is suspending.
- Provider candidate reports are precomputed before policy when possible.
- Optional route journal context is allowed.
- Fallback rules are represented in the route plan and executed by the controller.
- Privacy is not a built-in policy preset; privacy is enforced by `PrivacyPolicy` before provider invocation.
