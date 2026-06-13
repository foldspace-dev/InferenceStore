# Implement streaming-first InferenceStore API

Labels: `area/core`, `type/feature`, `priority/p0`  
Milestone: `M1 Core prototype`  
Dependencies: #2, #3

## Problem

Feature code needs a single API for streaming and non-streaming inference.

## Proposal

Implement `InferenceStore.stream(request)` and `InferenceStore.generate(request)` in core, backed by a simple execution controller.

## Acceptance criteria

- [ ] `stream` returns a cold Flow.
- [ ] `generate` collects stream and returns final result.
- [ ] Cancellation of collection cancels provider execution where possible.
- [ ] Event sequence includes Started, ProviderSelected, Token, Done/Failed.
- [ ] Unit tests cover success, failure, and cancellation.

## Notes

This issue is part of the initial InferenceStore planning backlog. Adjust scope after API validation.
