# ADR-0003: Streaming-first API

Status: Accepted  
Generated: 2026-06-13

## Context

Inference is often interactive and token-streamed. A non-streaming API would hide important state transitions like route planning, fallback, validation, and first token latency.

## Decision

`stream(request): Flow<InferenceEvent<Output>>` is the primary API. `generate(request)` is convenience.

## Consequences

Positive:

- natural UI integration
- event trace is first-class
- fallback can be visible
- observability fits execution model

Negative:

- more complex API than simple suspend function
- adapters must handle streaming/non-streaming differences
