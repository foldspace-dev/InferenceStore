# User stories

Generated: 2026-06-13

## App engineer stories

### US-001: Local-first summary

As an app engineer, I want to summarize text locally when possible so that private notes can be processed quickly and cheaply.

Acceptance:

- Given a local provider is available, when I request a summary, then the local provider is selected.
- The result trace says local provider was used.
- No cloud provider is invoked.

### US-002: Cloud fallback

As an app engineer, I want cloud fallback when the local provider is unavailable so that the feature still works on unsupported devices.

Acceptance:

- Given local provider is unavailable and cloud is allowed, when I request inference, then cloud provider is selected.
- The result trace includes fallback reason.

### US-003: Privacy block

As an app engineer, I want cloud fallback blocked for local-only requests so that sensitive content never leaves the device.

Acceptance:

- Given request privacy is local-only and local provider is unavailable, when I request inference, then the request fails with a policy violation.
- The cloud provider is not invoked.

### US-004: Structured output repair

As an app engineer, I want malformed local structured output to be repaired by cloud when allowed so that I can rely on typed results.

Acceptance:

- Given local output fails JSON/schema validation, when cloud repair is allowed, then cloud provider is attempted.
- Final result validates.
- Trace includes validation failure and fallback.

### US-005: Deterministic route test

As a feature engineer, I want to unit test routing without real providers so that my tests are fast and deterministic.

Acceptance:

- Fake providers can simulate availability, capabilities, tokens, and errors.
- Test can assert local attempted and cloud not attempted.

## Platform team stories

### US-006: Central provider registry

As a platform lead, I want to register providers once so that feature teams do not manage provider setup.

Acceptance:

- Provider registry is configured at app startup.
- Feature code only passes request and optional policy.

### US-007: Observability

As a platform lead, I want route telemetry so that I can monitor local/cloud usage, fallbacks, and failures.

Acceptance:

- Monitor events include provider ID, model ID, fallback reason, latency, validation status.
- Raw prompts/outputs are redacted by default.

### US-008: Policy rollout

As a platform lead, I want policy to be swappable so that I can change local/cloud routing without rewriting feature code.

Acceptance:

- Policy can be injected.
- Request-level override is possible.
- Tests can cover policy behavior.

## Runtime maintainer stories

### US-009: Adapter implementation

As a runtime maintainer, I want to implement one provider interface so that apps can use my runtime through InferenceStore.

Acceptance:

- Adapter guide exists.
- Provider contract supports availability, capabilities, streaming, metadata, and errors.
- Testkit can verify adapter behavior.

## Meeseeks stories

### US-010: Background model inventory

As an app engineer, I want model availability refreshed in the background so that foreground inference does not always probe the runtime.

Acceptance:

- Meeseeks worker refreshes provider inventory.
- Foreground route planning can consume inventory.

### US-011: Model warmup

As an app engineer, I want to warm a local model before use so that first-token latency improves.

Acceptance:

- Warmup task can be scheduled with battery/network constraints.
- Provider reports warmup support.
