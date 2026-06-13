# Define cache and artifact store interfaces

Labels: `area/cache`, `type/feature`, `priority/p1`  
Milestone: `M2 Alpha`  
Dependencies: #10

## Problem

The core needs cache/source-of-truth-like hooks without overcommitting to a persistent implementation.

## Proposal

Add `InferenceCache`, `InferenceArtifactStore`, `InferenceArtifact`, `CachePolicy`, and `CacheOutcome` interfaces/types.

## Acceptance criteria

- [ ] Core can read before provider execution when cache policy allows.
- [ ] Core can write successful results only when cache policy and privacy policy both allow.
- [ ] Artifact includes provider/model/trace/validation metadata.
- [ ] Artifact can omit/redact prompt/output content.
- [ ] Docs explain why this is not exactly Store SourceOfTruth.

## Notes

This issue is part of the InferenceStore planning backlog. Adjust scope after API validation.
