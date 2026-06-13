# Add privacy policy model and enforcement

Labels: `area/security`, `type/feature`, `priority/p0`  
Milestone: `M1 Core prototype`  
Dependencies: #4, #6, #37

## Problem

Cloud fallback must not happen accidentally for sensitive/local-only requests.

## Proposal

Implement `PrivacyPolicy`, `PrivacyClass`, `CloudPermission`, `PersistencePermission`, `TelemetryPermission`, provider boundary checks, and execution-controller enforcement exactly as specified in `privacy-model.md`.

## Acceptance criteria

- [ ] `LocalOnly` requests never invoke cloud/remote/hybrid providers.
- [ ] `Personal` default denies cloud.
- [ ] Approved cloud providers can be explicitly allowed.
- [ ] Execution controller enforces privacy independent of policy.
- [ ] Tests prove denied providers were not invoked.
- [ ] Monitor events are redacted by default.
- [ ] No `AllowsCloud` class or ad hoc privacy flags remain in public API.

## Notes

This issue is part of the InferenceStore planning backlog. Adjust scope after API validation.
