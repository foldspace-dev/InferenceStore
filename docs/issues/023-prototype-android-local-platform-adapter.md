# Prototype Android local/platform adapter

Labels: `area/provider`, `platform/android`, `type/feature`, `priority/p2`  
Milestone: `M3 Mobile proof`  
Dependencies: #22

## Problem

The project needs one real Android local or platform inference path to prove value on-device.

## Proposal

Implement experimental Android adapter based on selected runtime/platform path.

## Acceptance criteria

- [ ] Adapter compiles in Android target.
- [ ] Availability and capability mapping are implemented.
- [ ] Basic text generation works on supported environment or sample stub documents setup.
- [ ] Unsupported devices/models return stable availability/capability reasons.
- [ ] Adapter is marked experimental.

## Notes

This issue is part of the initial InferenceStore planning backlog. Adjust scope after API validation.
