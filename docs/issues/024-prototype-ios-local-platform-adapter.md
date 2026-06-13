# Prototype iOS local/platform adapter

Labels: `area/provider`, `platform/ios`, `type/feature`, `priority/p2`  
Milestone: `M3 Mobile proof`  
Dependencies: #22

## Problem

The project needs an iOS path to prove KMP value beyond Android/JVM.

## Proposal

Implement experimental iOS adapter based on selected platform/runtime path, likely behind expect/actual and Swift interop as needed.

## Acceptance criteria

- [ ] Adapter compiles for iOS target.
- [ ] Availability/capability mapping is implemented.
- [ ] Setup docs explain required OS/device constraints.
- [ ] Unsupported devices return stable reasons.
- [ ] Adapter is marked experimental.

## Notes

This issue is part of the initial InferenceStore planning backlog. Adjust scope after API validation.
