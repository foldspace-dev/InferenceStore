# Create sample: private note summarization

Labels: `area/samples`, `type/feature`, `priority/p1`  
Milestone: `M2 Alpha`  
Dependencies: #6, #7, #9, #15, #38

## Problem

The project needs a concrete demo that explains the value better than architecture docs.

## Proposal

Build a sample note summarizer using fake local provider mode, optional LiteRT-LM local provider mode, OpenAI-compatible cloud provider, privacy toggle, validation demo, and route trace display.

## Acceptance criteria

- [ ] Sample runs with fake providers without external model/API.
- [ ] Sample can run LiteRT-LM path when model path env/config is supplied.
- [ ] Local unavailable -> cloud fallback scenario works.
- [ ] Private/local-only scenario refuses cloud and proves zero invocation.
- [ ] Route trace is visible in logs or UI.

## Notes

This issue is part of the InferenceStore planning backlog. Adjust scope after API validation.
