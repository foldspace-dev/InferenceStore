# Implement built-in local/cloud policy presets

Labels: `area/policy`, `type/feature`, `priority/p0`  
Milestone: `M1 Core prototype`  
Dependencies: #3, #4, #5

## Problem

The MVP needs simple but useful policies that prove the local/cloud routing model.

## Proposal

Implement `localOnly`, `cloudOnly`, `preferLocalThenCloud`, `preferCloudThenLocal`, and `validateLocalRepairWithCloud`.

## Acceptance criteria

- [ ] Policies choose providers based on kind, availability, and capabilities.
- [ ] Policy results are deterministic.
- [ ] Privacy guardrails cannot be bypassed by policy.
- [ ] Tests cover all built-ins.
- [ ] Docs show when to use each policy.

## Notes

This issue is part of the initial InferenceStore planning backlog. Adjust scope after API validation.
