# Define core request and key model

Labels: `area/core`, `type/feature`, `priority/p0`  
Milestone: `M1 Core prototype`  
Dependencies: #1

## Problem

Inference requests need a stable shape that captures key, input, output type, privacy, cache, policy, timeout, prompt version, and metadata.

## Proposal

Implement `InferenceRequest`, `InferenceKey`, `InferenceInput`, `OutputSpec`, `PromptSpec`, and basic convenience builders.

## Acceptance criteria

- [ ] Text and message inputs are supported.
- [ ] Text output spec is supported.
- [ ] JSON/typed output spec API is sketched or implemented behind serialization.
- [ ] Request has privacy and cache defaults.
- [ ] Unit tests cover request creation and equality/fingerprint-relevant fields.

## Notes

This issue is part of the initial InferenceStore planning backlog. Adjust scope after API validation.
