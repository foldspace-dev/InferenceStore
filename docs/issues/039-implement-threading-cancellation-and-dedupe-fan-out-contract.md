# Implement threading, cancellation, and dedupe fan-out contract

Labels: `area/core`, `area/reliability`, `type/feature`, `priority/p0`  
Milestone: `M1 Core prototype`  
Dependencies: #4, #9, #37

## Problem

Streaming-first KMP inference needs explicit dispatcher, cancellation, and fan-out semantics before local adapters are written around accidental behavior.

## Proposal

Implement the core execution configuration, main-safety rules, cancellation semantics, and MVP dedupe fan-out behavior from `docs/technical/threading-dispatchers.md`.

## Acceptance criteria

- [ ] `stream()` is cold and performs no provider work before collection.
- [ ] Blocking provider work can be routed to configured execution context.
- [ ] Caller cancellation is terminal and does not fallback.
- [ ] Dedupe joins stream collectors only before first content event in MVP.
- [ ] `generate()` can join an in-flight compatible request until terminal result.
- [ ] Cancellation of one joined collector does not cancel upstream while others remain.
- [ ] Tests cover late stream collector behavior and cancellation ref-counting.

## Notes

This issue is part of the InferenceStore planning backlog. Adjust scope after API validation.
