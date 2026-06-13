# Fit with Store and Meeseeks

Generated: 2026-06-13

## Purpose

This document explains how InferenceStore should borrow from Store and Meeseeks without becoming a confused mashup.

## Store fit

Store's durable ideas:

- unified data access
- source-of-truth
- memory cache
- local storage
- fetcher
- validator
- request dedupe
- fallback
- offline-first behavior
- conflict bookkeeping

InferenceStore analogs:

| Store | InferenceStore |
|---|---|
| Data access | Inference execution |
| Network fetcher | Provider adapter |
| Memory cache | Output/route cache |
| Source of truth | Artifact store |
| Validator | Output validator/evaluator |
| Request dedupe | In-flight inference dedupe |
| Bookkeeper | Route/failure journal |
| Converter | Prompt codec/output parser |
| Offline-first | Local-first inference |
| MutableStore/updater | Deferred inference/model lifecycle, not MVP core |

## What not to copy from Store

Do not copy the API mechanically.

Store has stable `Key -> Output` data retrieval. Inference has:

- request input
- prompt version
- model version
- provider capability
- privacy policy
- route policy
- validation result
- nondeterminism

A request object should be primary.

## Meeseeks fit

Meeseeks handles background work. InferenceStore's foreground API should not require it, but the ecosystem story is strong.

Meeseeks can handle:

- provider inventory refresh
- model download
- model warmup
- pruning
- telemetry upload
- deferred inference
- batch embeddings
- retry/redrive

## Combined architecture story

```text
Store:      make data flow reliable
Meeseeks:   make background work reliable
InferenceStore: make inference routing reliable
```

Together, these form a coherent KMP app infrastructure stack.

## Integration boundaries

### Store integration

Potential later guide:

- use Store for domain data
- use InferenceStore to generate summaries/extractions
- write generated artifacts back through Store/SourceOfTruth if they become domain data

Example:

```text
Note Store -> note body
InferenceStore -> generated summary
Summary Store -> persist summary as domain artifact
```

### Meeseeks integration

Potential later guide:

- schedule warmup/download/prune/inventory
- schedule deferred inference
- route foreground calls using inventory

## Naming consideration

The “Store but for inference” framing is useful among Store users. Public docs should quickly explain where the analogy ends.

## Recommended docs cross-links

- “For Store users”
- “For Meeseeks users”
- “How InferenceStore differs from Store”
- “Using Meeseeks for model lifecycle”
