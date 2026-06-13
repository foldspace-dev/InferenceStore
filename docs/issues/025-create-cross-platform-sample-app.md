# Create cross-platform sample app

Labels: `area/samples`, `platform/android`, `platform/ios`, `type/feature`, `priority/p2`  
Milestone: `M3 Mobile proof`  
Dependencies: #16, #23, #24

## Problem

A KMP library needs a mobile sample that demonstrates shared request/policy/test code and platform-specific providers.

## Proposal

Build a minimal Android/iOS sample using common inference code and platform-specific provider modules.

## Acceptance criteria

- [ ] Common code defines request and policy.
- [ ] Android provider configured in Android app.
- [ ] iOS provider configured in iOS app.
- [ ] Route trace shows actual provider used.
- [ ] Docs explain platform differences.

## Notes

This issue is part of the initial InferenceStore planning backlog. Adjust scope after API validation.
