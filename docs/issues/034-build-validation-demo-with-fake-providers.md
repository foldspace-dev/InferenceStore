# Build validation demo with fake providers

Labels: `area/demo`, `type/feature`, `priority/p0`  
Milestone: `M0 Validation`  
Dependencies: #22, #37

## Problem

Potential users need to see the value before the M1 implementation exists.

## Proposal

Create a static/scripted validation demo using fake route traces or a throwaway script. Do not depend on M1 core implementation.

## Acceptance criteria

- [ ] Demo shows local success.
- [ ] Demo shows local unavailable -> cloud fallback.
- [ ] Demo shows local schema invalid -> cloud repair.
- [ ] Demo shows local-only privacy denial before cloud invocation.
- [ ] Demo prints or displays canonical route trace.
- [ ] Demo can run before M1 core issues are complete.

## Notes

This issue is part of the InferenceStore planning backlog. Adjust scope after API validation.
