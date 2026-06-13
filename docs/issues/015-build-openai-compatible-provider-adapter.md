# Build OpenAI-compatible provider adapter

Labels: `area/provider`, `area/security`, `type/feature`, `priority/p1`  
Milestone: `M1 Core prototype`  
Dependencies: #3, #4, #21, #40, #37

## Problem

The MVP needs a real cloud/remote provider adapter that works with many OpenAI-compatible endpoints.

## Proposal

Implement `inferencestore-provider-openai-compatible` using configurable base URL, API key provider, model name, and streaming support.

## Acceptance criteria

- [ ] Streaming works against mock HTTP server.
- [ ] Timeout/cancellation are mapped according to timeout contract.
- [ ] HTTP 401/403/429/5xx map to stable categories.
- [ ] API keys are never logged or traced.
- [ ] Docs include mobile API-key guidance and backend-proxy recommendation.
- [ ] Provider privacy boundary defaults to cloud/remote unless configured otherwise.

## Notes

This issue is part of the InferenceStore planning backlog. Adjust scope after API validation.
