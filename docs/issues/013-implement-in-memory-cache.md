# Implement in-memory cache

Labels: `area/cache`, `type/feature`, `priority/p1`  
Milestone: `M2 Alpha`  
Dependencies: #12

## Problem

A small in-memory cache is needed for demos, tests, and repeated UI calls.

## Proposal

Implement `MemoryInferenceCache` with TTL and clear APIs.

## Acceptance criteria

- [ ] Cache hit returns artifact without provider call.
- [ ] Expired artifact triggers provider call.
- [ ] Clear by key works.
- [ ] Clear all works.
- [ ] Tests cover privacy no-write behavior.

## Notes

This issue is part of the initial InferenceStore planning backlog. Adjust scope after API validation.
