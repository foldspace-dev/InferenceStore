# Implement route trace and event model

Labels: `area/observability`, `type/feature`, `priority/p0`  
Milestone: `M1 Core prototype`  
Dependencies: #4

## Problem

Users need to know which provider served an inference request and why fallback occurred.

## Proposal

Add `InferenceEvent`, `InferenceResult`, `RouteTrace`, `ProviderAttemptTrace`, and route metadata to all results.

## Acceptance criteria

- [ ] Every result includes a `RouteTrace`.
- [ ] Every provider attempt is captured with provider ID/kind/model metadata when available.
- [ ] Fallback reason can be represented.
- [ ] Failed requests include trace information.
- [ ] Tests assert trace content.

## Notes

This issue is part of the initial InferenceStore planning backlog. Adjust scope after API validation.
