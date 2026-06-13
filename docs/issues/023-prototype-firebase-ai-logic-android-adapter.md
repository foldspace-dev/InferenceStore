# Prototype Firebase AI Logic Android adapter

Labels: `area/provider`, `platform/android`, `type/feature`, `priority/p2`  
Milestone: `M3 Mobile proof`  
Dependencies: #3, #6, #7, #40

## Problem

Android teams may want to use Firebase AI Logic while preserving InferenceStore route traces and policy ownership.

## Proposal

Prototype Firebase AI Logic as either separate logical on-device/cloud providers or a hybrid convenience provider that reports actual source metadata.

## Acceptance criteria

- [ ] Design chooses separate providers vs hybrid convenience wrapper.
- [ ] Privacy boundary behavior is documented.
- [ ] Capability limits are documented.
- [ ] Route trace records whether Firebase used on-device or cloud when available.
- [ ] Prototype does not change core API.

## Notes

This issue is part of the InferenceStore planning backlog. Adjust scope after API validation.
