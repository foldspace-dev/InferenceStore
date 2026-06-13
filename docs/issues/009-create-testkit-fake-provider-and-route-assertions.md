# Create testkit fake provider and route assertions

Labels: `area/testkit`, `type/feature`, `priority/p0`  
Milestone: `M1 Core prototype`  
Dependencies: #3, #5, #37

## Problem

Developers need deterministic tests without real models or cloud calls.

## Proposal

Implement `FakeInferenceProvider`, scripted responses, failure injection, streaming scripts, and route assertion helpers.

## Acceptance criteria

- [ ] Fake providers support scripted tokens, completion, delays, cancellation, and every stable error category.
- [ ] Route assertions cover attempted/fellBackTo/completedWith/rejected/didNotAttempt.
- [ ] Virtual clock supports timeouts/retries.
- [ ] Privacy assertions prove provider invocation count is zero when denied.
- [ ] Dedupe fan-out assertions are available.

## Notes

This issue is part of the InferenceStore planning backlog. Adjust scope after API validation.
