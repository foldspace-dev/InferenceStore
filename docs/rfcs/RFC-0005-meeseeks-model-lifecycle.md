# RFC-0005: Meeseeks model lifecycle integration

Status: Draft  
Generated: 2026-06-13

## Summary

Use Meeseeks for background model lifecycle and deferred inference tasks.

## Motivation

Hybrid inference requires background work: model availability checks, downloads, warmup, pruning, telemetry upload, and deferred retries. Meeseeks already provides KMP background scheduling with persistence and retry policies.

## Proposal

Add optional module:

```text
inferencestore-meeseeks
```

Provide task payloads and workers for:

- provider inventory refresh
- model download
- model warmup
- model pruning
- telemetry upload
- deferred inference

## Non-goals

- foreground inference depends on Meeseeks
- automatic background scheduling without app consent
- provider-specific model download in core

## First implementation

Provider inventory refresh worker.

## Acceptance criteria

- Apps can schedule inventory refresh.
- Routing can consume inventory records.
- Tasks respect privacy and network preconditions.
- Module is optional.
