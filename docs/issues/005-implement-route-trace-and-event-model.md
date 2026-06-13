# Implement route trace and event model

Labels: `area/observability`, `type/feature`, `priority/p0`  
Milestone: `M1 Core prototype`  
Dependencies: #4, #37

## Problem

Users need a canonical route trace and event model to understand provider selection, privacy rejection, fallback, validation, timeout, and completion.

## Proposal

Implement route trace and canonical event model from `docs/technical/event-model.md`.

## Acceptance criteria

- [ ] Event model matches `event-model.md`.
- [ ] Trace records attempted providers and rejected providers.
- [ ] Trace records fallback reasons and error categories.
- [ ] Trace records privacy denials without provider invocation.
- [ ] Golden JSON traces exist for success, fallback, validation repair, timeout, cancellation, and privacy denial.

## Notes

This issue is part of the InferenceStore planning backlog. Adjust scope after API validation.
