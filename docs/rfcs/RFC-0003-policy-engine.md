# RFC-0003: Policy engine

Status: Draft  
Generated: 2026-06-13

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
- `validateLocalRepairWithCloud`
- `privacyFirst`

## Guardrails

The execution controller enforces privacy regardless of policy bugs.

## Open questions

1. Should policies be pure and non-suspending?
2. Should availability probing happen before policy or inside policy?
3. Should route journal be visible to policy?
4. Should fallback be route-level or policy-level?

## Recommendation

- Make policy suspending.
- Provide precomputed candidate reports to policy.
- Allow optional route journal context.
- Keep fallback rules in route plan.
