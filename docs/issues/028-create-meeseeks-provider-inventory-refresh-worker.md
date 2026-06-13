# Create Meeseeks provider inventory refresh worker

Labels: `area/meeseeks`, `type/feature`, `priority/p2`  
Milestone: `M4 Meeseeks lifecycle`  
Dependencies: #20

## Problem

Foreground route planning benefits from cached provider/model availability, but probing every time can be expensive.

## Proposal

Add optional Meeseeks worker that refreshes provider inventory in the background.

## Acceptance criteria

- [ ] Worker payload supports provider IDs.
- [ ] Worker calls provider availability/capability probes.
- [ ] Worker writes provider inventory records.
- [ ] Scheduling helper is documented.
- [ ] Tests use fake providers and fake Meeseeks/runtime where possible.

## Notes

This issue is part of the initial InferenceStore planning backlog. Adjust scope after API validation.
