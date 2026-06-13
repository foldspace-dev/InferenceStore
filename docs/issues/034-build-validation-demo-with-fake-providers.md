# Build validation demo with fake providers

Labels: `area/demo`, `type/feature`, `priority/p0`  
Milestone: `M0 Validation`  
Dependencies: #4, #6, #8, #9

## Problem

Potential users need to see the value without waiting for real provider adapters.

## Proposal

Create runnable JVM/CLI demo using fake local/cloud providers and three route scenarios.

## Acceptance criteria

- [ ] Demo shows local success.
- [ ] Demo shows local unavailable -> cloud fallback.
- [ ] Demo shows local schema invalid -> cloud repair.
- [ ] Demo shows local-only privacy denial.
- [ ] Demo prints route trace.

## Notes

This issue is part of the initial InferenceStore planning backlog. Adjust scope after API validation.
