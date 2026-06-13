# Implement streaming-first InferenceStore API

Labels: `area/core`, `type/feature`, `priority/p0`  
Milestone: `M1 Core prototype`  
Dependencies: #2, #3, #37

## Problem

Feature code needs a single API for streaming and non-streaming inference.

## Proposal

Implement `InferenceStore.stream(request)` and `InferenceStore.generate(request)` in core, backed by a simple execution controller.

## Acceptance criteria

- [ ] `stream(request)` returns a cold Flow.
- [ ] `generate(request)` is implemented as a convenience over terminal success.
- [ ] Canonical events from `event-model.md` are emitted in documented order.
- [ ] Cancellation is terminal and does not fallback.
- [ ] Main-safety contract is documented and tested with fake blocking provider.

## Notes

This issue is part of the InferenceStore planning backlog. Adjust scope after API validation.
