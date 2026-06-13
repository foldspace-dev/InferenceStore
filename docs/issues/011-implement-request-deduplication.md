# Implement request deduplication

Labels: `area/core`, `area/cache`, `type/feature`, `priority/p1`  
Milestone: `M2 Alpha`  
Dependencies: #10

## Problem

Equivalent concurrent requests should not cause duplicate provider invocations when policy allows sharing.

## Proposal

Add in-flight request coalescing keyed by fingerprint and compatible policy/cache settings.

## Acceptance criteria

- [ ] Concurrent equivalent requests share one provider call.
- [ ] Requests with dedupe disabled do not share.
- [ ] Privacy settings can prevent dedupe.
- [ ] Cancellation of one collector does not cancel shared work while another collector remains.
- [ ] Tests cover success, failure, and cancellation.

## Notes

This issue is part of the initial InferenceStore planning backlog. Adjust scope after API validation.
