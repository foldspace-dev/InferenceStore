# Write Quickstart documentation

Labels: `area/docs`, `type/docs`, `priority/p1`  
Milestone: `M2 Alpha`  
Dependencies: #15, #16

## Problem

Developers need a runnable path from install to first routed inference request.

## Proposal

Create Quickstart with dependency setup, fake provider, OpenAI-compatible provider, policy, streaming, and tests.

## Acceptance criteria

- [ ] Quickstart includes Gradle version catalog and Kotlin DSL examples.
- [ ] Quickstart shows local-first/cloud-fallback.
- [ ] Quickstart shows route trace.
- [ ] Quickstart shows privacy local-only test.
- [ ] All code snippets compile in sample/test.

## Notes

This issue is part of the initial InferenceStore planning backlog. Adjust scope after API validation.
