# Implement request deduplication

Labels: `area/core`, `area/cache`, `type/feature`, `priority/p1`  
Milestone: `M2 Alpha`  
Dependencies: #2, #4, #9, #39

## Problem

Equivalent concurrent requests should not cause duplicate provider invocations when policy allows sharing.

## Proposal

Implement request deduplication according to `threading-dispatchers.md`, including MVP stream join-before-token behavior and `generate()` in-flight terminal joining.

## Acceptance criteria

- [ ] Equivalent concurrent `generate()` requests share one provider invocation.
- [ ] Equivalent `stream()` collectors before first content share one provider invocation.
- [ ] Late stream collector after first content starts a new invocation or reads cache.
- [ ] Cancellation is reference counted.
- [ ] Tests cover privacy/policy incompatibility and no-sharing cases.

## Notes

This issue is part of the InferenceStore planning backlog. Adjust scope after API validation.
