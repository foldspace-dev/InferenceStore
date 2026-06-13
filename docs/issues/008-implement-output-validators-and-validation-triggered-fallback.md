# Implement output validators and validation-triggered fallback

Labels: `area/validation`, `type/feature`, `priority/p0`  
Milestone: `M1 Core prototype`  
Dependencies: #4, #5, #6

## Problem

Local model output may be malformed or inadequate; validation should be able to trigger fallback or repair.

## Proposal

Implement `OutputValidator`, `ValidationResult`, predicate validator helpers, and execution logic for fallback on validation failure.

## Acceptance criteria

- [ ] Predicate validators can pass/fail final output.
- [ ] Validation result is included in route trace.
- [ ] Policy can fall back on validation failure.
- [ ] Schema/JSON validation has either initial implementation or RFC stub.
- [ ] Tests cover local invalid -> cloud repair.

## Notes

This issue is part of the initial InferenceStore planning backlog. Adjust scope after API validation.
