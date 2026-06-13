# Add timeout and retry semantics

Labels: `area/reliability`, `type/feature`, `priority/p0`  
Milestone: `M1 Core prototype`  
Dependencies: #4, #5, #40, #37

## Problem

Provider calls need predictable timeout and retry behavior before fallback.

## Proposal

Implement `TimeoutPolicy`, `RetryPolicy`, timeout source metadata, retry events if enabled, and fallback behavior according to `timeout-retry-policy.md`.

## Acceptance criteria

- [ ] Request-level deadline works and is terminal.
- [ ] Attempt-level timeout works and may fallback.
- [ ] Time-to-first-token and idle stream timeout are represented or explicitly deferred.
- [ ] Timeout maps to stable error/fallback reason with source metadata.
- [ ] Retries are disabled by default and observable when enabled.
- [ ] Tests cover timeout -> fallback and request deadline -> terminal failure.

## Notes

This issue is part of the InferenceStore planning backlog. Adjust scope after API validation.
