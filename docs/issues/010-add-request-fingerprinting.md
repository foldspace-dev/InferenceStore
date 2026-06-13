# Add request fingerprinting

Labels: `area/cache`, `type/feature`, `priority/p1`  
Milestone: `M2 Alpha`  
Dependencies: #2, #7

## Problem

Caching and dedupe require a stable fingerprint that accounts for input, prompt, output, privacy, and policy-relevant fields.

## Proposal

Implement `InferenceFingerprint` and default fingerprinting strategy with extension hooks.

## Acceptance criteria

- [ ] Fingerprint includes key, input hash, prompt version, output version, privacy class, privacy policy version, and policy version if present.
- [ ] Raw input is not stored in the fingerprint.
- [ ] Tests cover prompt/input/privacy/policy changes.
- [ ] Docs warn about user/account scoping.

## Notes

This issue is part of the InferenceStore planning backlog. Adjust scope after API validation.
