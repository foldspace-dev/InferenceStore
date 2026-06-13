# Add privacy policy model and enforcement

Labels: `area/security`, `type/feature`, `priority/p0`  
Milestone: `M1 Core prototype`  
Dependencies: #4, #6

## Problem

Cloud fallback must not happen accidentally for sensitive/local-only requests.

## Proposal

Implement `PrivacyPolicy`, privacy classifications, cloud permission, persistence permission, telemetry permission, and enforcement in execution controller.

## Acceptance criteria

- [ ] `LocalOnly` requests never invoke cloud providers.
- [ ] Sensitive/default profiles are documented.
- [ ] Execution controller enforces privacy independent of policy.
- [ ] Tests prove denied providers were not invoked.
- [ ] Monitor events are redacted by default.

## Notes

This issue is part of the initial InferenceStore planning backlog. Adjust scope after API validation.
