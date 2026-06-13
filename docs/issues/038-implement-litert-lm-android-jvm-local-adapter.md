# Implement LiteRT-LM Android/JVM local adapter

Labels: `area/provider`, `platform/android`, `platform/jvm`, `type/feature`, `priority/p0`  
Milestone: `M1 Core prototype`  
Dependencies: #3, #4, #5, #7, #21, #22, #37, #39, #40

## Problem

The MVP must exercise real local inference behavior rather than only fake local providers.

## Proposal

Implement the LiteRT-LM Android/JVM adapter according to `docs/technical/litert-lm-adapter.md`.

## Acceptance criteria

- [ ] Adapter accepts explicit `.litertlm` model path and model ID.
- [ ] Missing/unreadable model maps to `ProviderUnavailable`.
- [ ] Text generation streams tokens through the provider contract.
- [ ] Engine initialization runs off main/test UI dispatcher.
- [ ] Timeout and cancellation clean up native/runtime resources.
- [ ] Unsupported capabilities map to `CapabilityUnsupported`.
- [ ] Route trace includes model/backend/runtime metadata and local privacy boundary.
- [ ] Optional real-model test is enabled by `INFERENCESTORE_LITERTLM_MODEL_PATH`.

## Notes

This issue is part of the InferenceStore planning backlog. Adjust scope after API validation.
