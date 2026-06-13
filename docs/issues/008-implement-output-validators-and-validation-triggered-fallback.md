# Implement output validators and validation-triggered fallback

Labels: `area/validation`, `type/feature`, `priority/p0`  
Milestone: `M1 Core prototype`  
Dependencies: #4, #5, #6, #37

## Problem

Local model output may be malformed or inadequate; validation should be able to trigger fallback or repair.

## Proposal

Implement `OutputValidator`, `ValidationResult`, predicate validator helpers, and execution logic for fallback on validation failure.

## Acceptance criteria

- [ ] Predicate validator works on final output.
- [ ] Parser/schema failure maps to stable error categories.
- [ ] Validation failure can trigger configured fallback/repair.
- [ ] Partial validation is explicitly out of MVP.
- [ ] Tests cover local-invalid -> cloud-repair route.

## Notes

This issue is part of the InferenceStore planning backlog. Adjust scope after API validation.
