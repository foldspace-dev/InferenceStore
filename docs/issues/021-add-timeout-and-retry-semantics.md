# Add timeout and retry semantics

Labels: `area/reliability`, `type/feature`, `priority/p1`  
Milestone: `M2 Alpha`  
Dependencies: #4, #6

## Problem

Provider calls need predictable timeout and retry behavior before fallback.

## Proposal

Add attempt timeout support and stable retry/fallback categories. Keep retry minimal in core; prefer fallback over hidden retries.

## Acceptance criteria

- [ ] Request-level timeout works.
- [ ] Attempt-level timeout works.
- [ ] Timeout maps to stable error/fallback reason.
- [ ] Retries are explicit and observable if implemented.
- [ ] Tests cover timeout -> fallback.

## Notes

This issue is part of the initial InferenceStore planning backlog. Adjust scope after API validation.
