# Validation plan

Generated: 2026-06-13

## Goal

Validate whether KMP/mobile teams want a Store-like inference orchestration layer enough to adopt a new OSS library.

## Core questions

1. Are developers already building local/cloud fallback logic?
2. Do they need provider neutrality, or is Firebase/Gemini enough?
3. Do they value deterministic tests for route decisions?
4. Is observability a must-have or nice-to-have?
5. What is the smallest adapter set that proves value?
6. What local inference tasks are realistic on their target devices?
7. Is the Store analogy helpful or misleading?
8. Would teams adopt this as app infrastructure?

## Hypotheses

### H1: Routing policy is the wedge

Developers do not primarily want “another local model wrapper”; they want a clean way to express policies like local-first, cloud-fallback, privacy-only-local, and quality-repair.

Validation signal:
- Interviewees describe hand-rolled routing/fallback logic.
- Interviewees ask for policy/test utilities before runtime support.

### H2: Observability is a core feature

Production teams need to know which provider served a result, why fallback happened, how long it took, and whether validation passed.

Validation signal:
- Teams ask for route traces, cost estimates, and error/fallback metrics.
- Teams say “we cannot ship this without monitoring.”

### H3: KMP matters

A shared Kotlin API across iOS and Android is a real advantage, even if some adapters are platform-specific.

Validation signal:
- KMP teams want the request/policy/test layer in common code.
- They accept platform-specific adapter modules.

### H4: Meeseeks integration is a later differentiator

Background model lifecycle is valuable, but not required for MVP adoption.

Validation signal:
- Teams mention download/warmup/pruning after they understand routing.
- Few teams require it before trying core.

## Interview targets

Minimum 15 conversations:

- 5 KMP app engineers
- 3 Android AI engineers
- 3 iOS AI engineers
- 2 platform/infrastructure leads
- 2 local inference/runtime maintainers

## Interview script

### Context

“I maintain Store and Meeseeks. I’m exploring a Store-like KMP layer for inference: one API for local/cloud inference with explicit routing policy, fallback, validation, cache hooks, and observability.”

### Questions

1. Are you currently using or exploring local inference?
2. What AI features are you building?
3. Do those features need to work offline?
4. Do you have privacy constraints that affect cloud inference?
5. How do you decide between local and cloud today?
6. What happens when the local model is unavailable?
7. What happens when local output is low quality or invalid?
8. Do you cache prompts, outputs, embeddings, or model availability?
9. What telemetry do you need?
10. How do you test inference code today?
11. Would you use a shared KMP API for routing and policy?
12. What provider/runtime support would make you try it?
13. What would make this a non-starter?
14. Would you contribute an adapter or sample?
15. What would you need before using this in production?

## Prototype validation

Build a tiny prototype that can demo:

- local fake provider succeeds
- local fake provider unavailable -> cloud fake provider
- local fake provider invalid schema -> cloud repair provider
- policy result includes route explanation
- test asserts route and fallback reason

No real runtime needed for the first validation demo.

## Landing page claims to test

- “Local when possible, cloud when necessary.”
- “Make hybrid inference a policy, not feature code.”
- “Deterministic tests for local/cloud routing.”
- “Store-like architecture for AI features.”
- “Provider-neutral KMP inference orchestration.”

Track which claim resonates.

## Success criteria

Proceed to MVP if at least 8 of 15 interviewees say they would try it and at least 5 have a concrete near-term feature.

Proceed to adapters if at least 3 teams identify the same local runtime or platform path.

Proceed to Meeseeks integration if at least 3 teams identify model download/warmup/pruning as a blocker.

## Red flags

- Teams only want wrappers around one runtime.
- Teams are fully satisfied with Firebase AI Logic or Apple-only APIs.
- Teams do not care about privacy, fallback, tests, or observability.
- Teams are not using KMP or do not want shared inference code.
- The API requires too much conceptual overhead for simple use cases.

## Validation artifacts

- README
- 5-minute demo video/GIF
- architecture diagram
- sample API
- fake-provider test suite
- comparison matrix
- GitHub discussion thread
