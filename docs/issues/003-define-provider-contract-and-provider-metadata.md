# Define provider contract and provider metadata

Labels: `area/provider`, `type/feature`, `priority/p0`  
Milestone: `M1 Core prototype`  
Dependencies: #1, #2, #37

## Problem

Provider adapters need a common contract for availability, capabilities, streaming execution, and metadata.

## Proposal

Implement provider contract with provider ID, kind, privacy boundary, availability, capability reports, streaming, provider metadata, and stable error mapping.

## Acceptance criteria

- [ ] Provider contract includes `ProviderPrivacyBoundary`.
- [ ] Availability and capability checks are suspending and bounded by timeout policy.
- [ ] Provider errors map to stable categories.
- [ ] Provider metadata includes model/runtime/boundary fields.
- [ ] Docs explain adapter responsibilities and non-responsibilities.

## Notes

This issue is part of the InferenceStore planning backlog. Adjust scope after API validation.
