# Build OpenAI-compatible provider adapter

Labels: `area/provider`, `type/feature`, `priority/p1`  
Milestone: `M2 Alpha`  
Dependencies: #3, #4, #14

## Problem

The MVP needs a real cloud/remote provider adapter that works with many OpenAI-compatible endpoints.

## Proposal

Implement `inferencestore-provider-openai-compatible` using configurable base URL, API key provider, model name, and streaming support.

## Acceptance criteria

- [ ] Adapter supports text/chat request mapping.
- [ ] Adapter supports streaming where endpoint supports it.
- [ ] Adapter maps rate limit, timeout, network, and server errors.
- [ ] Adapter redacts secrets and prompts by default.
- [ ] Mock-server tests cover success, streaming, and error mapping.

## Notes

This issue is part of the initial InferenceStore planning backlog. Adjust scope after API validation.
