# Create sample: private note summarization

Labels: `area/samples`, `type/feature`, `priority/p1`  
Milestone: `M2 Alpha`  
Dependencies: #6, #7, #9, #15

## Problem

The project needs a concrete demo that explains the value better than architecture docs.

## Proposal

Build a sample note summarizer using fake local provider, OpenAI-compatible cloud provider, privacy toggle, and route trace display.

## Acceptance criteria

- [ ] Sample runs on JVM or Android initially.
- [ ] Local success scenario works.
- [ ] Local unavailable -> cloud fallback scenario works.
- [ ] Private/local-only scenario refuses cloud.
- [ ] Route trace is visible in logs or UI.

## Notes

This issue is part of the initial InferenceStore planning backlog. Adjust scope after API validation.
