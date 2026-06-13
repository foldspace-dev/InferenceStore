# Create testkit fake provider and route assertions

Labels: `area/testkit`, `type/feature`, `priority/p0`  
Milestone: `M1 Core prototype`  
Dependencies: #3, #5

## Problem

Developers need deterministic tests without real models or cloud calls.

## Proposal

Implement `FakeInferenceProvider`, scripted responses, failure injection, streaming scripts, and route assertion helpers.

## Acceptance criteria

- [ ] Fake provider can stream tokens.
- [ ] Fake provider can be unavailable.
- [ ] Fake provider can fail with stable error categories.
- [ ] Assertions support attempted/fellBackTo/completedWith/didNotAttempt.
- [ ] Examples in docs compile.

## Notes

This issue is part of the initial InferenceStore planning backlog. Adjust scope after API validation.
