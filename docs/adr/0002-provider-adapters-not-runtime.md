# ADR-0002: Provider adapters, not a new inference runtime

Status: Accepted  
Updated: 2026-06-13

## Context

The runtime ecosystem is already active: platform APIs, LiteRT-LM, ExecuTorch, MLC, Llamatik, Cactus, Firebase, and cloud providers.

## Decision

InferenceStore will not implement its own inference runtime. It will define provider contracts and optional adapters.

## Consequences

Positive:

- smaller scope
- less hardware/runtime churn
- easier adoption by runtime maintainers
- clearer Store-like value

Negative:

- depends on external runtime quality
- adapter maintenance burden
- limited ability to fix runtime performance bugs
