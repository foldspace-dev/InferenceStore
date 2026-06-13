# Define provider contract and provider metadata

Labels: `area/provider`, `type/feature`, `priority/p0`  
Milestone: `M1 Core prototype`  
Dependencies: #1, #2

## Problem

Provider adapters need a common contract for availability, capabilities, streaming execution, and metadata.

## Proposal

Implement `InferenceProvider`, `ProviderId`, `ProviderKind`, `ProviderAvailability`, `Capability`, `CapabilityReport`, `ProviderMetadata`, `ProviderEvent`, and `ProviderError`.

## Acceptance criteria

- [ ] Providers can report availability without executing inference.
- [ ] Providers can report capability support for a request.
- [ ] Provider events support started/token/completed/failed.
- [ ] Provider errors map to stable categories.
- [ ] No provider-specific dependencies enter core.

## Notes

This issue is part of the initial InferenceStore planning backlog. Adjust scope after API validation.
