# Architecture overview

Updated: 2026-06-13

## System purpose

InferenceStore coordinates inference across providers. It does not execute models itself unless an adapter delegates to a runtime. Its core responsibilities are route planning, privacy gating, execution orchestration, validation, fallback, caching hooks, telemetry, deterministic testing, and later model lifecycle integration.

## Store analogy

| Store concept | InferenceStore concept | Notes |
|---|---|---|
| `Store<Key, Output>` | `InferenceStore` | Orchestrates a request and emits a stream of responses/events. The root store is not generic; each request carries its output spec. |
| `StoreReadRequest` | `InferenceRequest<Output>` | Includes key, input, output type, privacy policy, cache policy, route policy, timeout, retry, metadata. |
| `Fetcher` | `InferenceProvider` | Fetcher retrieves network data; provider generates output locally or remotely. |
| `SourceOfTruth` | `InferenceArtifactStore` | Stores versioned inference artifacts, not necessarily authoritative truth. |
| `Validator` | `OutputValidator` / `Evaluator` | Checks schema, safety, quality, freshness, policy compliance. MVP validation is final-output only. |
| `Bookkeeper` | `RouteJournal` / `FailureJournal` | Tracks failed attempts, fallbacks, cooldowns, retry state. |
| `Converter` | `PromptCodec` / `OutputParser` | Converts domain inputs to provider requests and outputs back to typed domain data. |
| Memory cache | Output/request cache | Avoids duplicate or stale-but-valid inference work where policy and privacy allow. |
| Request dedupe | Request dedupe | Coalesces equivalent concurrent inference requests with explicit fan-out semantics. |

## Key difference from Store

Store coordinates data retrieval. InferenceStore coordinates model execution.

That creates differences:

- Inference results can be nondeterministic.
- Output validity may depend on schema, evaluator, task, model version, prompt template, and user context.
- “Freshness” may include input hash, model version, prompt version, provider version, privacy class, policy version, and tool availability.
- Some results should never be persisted.
- Cloud fallback may violate privacy unless explicitly allowed.
- Provider capabilities vary dramatically.
- Streaming is central, not an add-on.
- Local runtimes can fail through OOM, unsupported backend, model missing, warmup timeout, or native resource errors.

## Source-of-truth technical contracts

The following docs are normative:

- Privacy: `docs/technical/privacy-model.md`
- Events: `docs/technical/event-model.md`
- Threading/cancellation/dedupe: `docs/technical/threading-dispatchers.md`
- Error to fallback mapping: `docs/technical/error-fallback-mapping.md`
- Timeout/retry: `docs/technical/timeout-retry-policy.md`
- First real local adapter: `docs/technical/litert-lm-adapter.md`

## High-level components

```text
Feature code
   |
   v
InferenceStore
   |
   +--> Request fingerprint / cache policy
   |
   +--> Privacy gate
   |       |
   |       +--> provider boundary checks
   |       +--> persistence/telemetry redaction checks
   |
   +--> Policy engine
   |       |
   |       +--> Provider registry
   |       +--> Availability probes
   |       +--> Capability checks
   |       +--> Cost/latency/timeout rules
   |
   +--> Execution controller
   |       |
   |       +--> Provider attempt 1
   |       +--> Validator/evaluator
   |       +--> Fallback attempt 2
   |
   +--> Artifact store / memory cache
   |
   +--> Monitor hooks / route journal
```

Privacy gate is intentionally shown outside provider adapters and independent of route policy.

## Core module boundaries

### `core`

Contains stable abstractions and orchestration:

- request/response/event types;
- provider contracts;
- policy contracts;
- route planner;
- execution controller;
- privacy enforcement;
- validators;
- monitors;
- cache interfaces;
- timeout/retry contracts;
- test-friendly error categories.

### `testkit`

Contains deterministic fakes, virtual clock integration, failure injection, and assertions.

### `provider-*`

Optional provider adapters:

- `provider-openai-compatible`
- `provider-litertlm-android` / `provider-litertlm-jvm` for MVP real local runtime proof
- `provider-firebase-ai-logic-android`
- `provider-apple-foundation-models-ios`
- `provider-llamatik`
- `provider-cactus`
- `provider-mlc`

### `store-*`

Optional persistent implementations:

- `store-memory`
- `store-sqldelight`

### `meeseeks`

Optional M5 integration for model lifecycle and deferred jobs.

## Request lifecycle

### 1. Normalize

The request is normalized:

- key;
- input hash;
- prompt/template version;
- output type/schema version;
- privacy class and privacy policy version;
- cache policy;
- policy ID/version;
- timeout/retry policy.

### 2. Check cache

If allowed by both cache and privacy policy, memory cache and artifact store are checked.

The validator can reject cached artifacts if:

- prompt version changed;
- model version no longer accepted;
- output schema changed;
- TTL expired;
- privacy policy forbids reuse;
- custom validator fails.

### 3. Evaluate providers

The provider registry produces candidates with:

- availability;
- capability report;
- privacy boundary;
- model/runtime metadata;
- latency/cost estimates if available.

### 4. Enforce privacy gate

Before any provider invocation, the execution controller rejects candidates that violate `PrivacyPolicy`.

Denied candidates are visible in trace metadata but are not invoked.

### 5. Plan route

The policy engine evaluates:

- provider availability;
- provider capability;
- network state;
- device state if provided;
- cost/latency budget;
- timeout/retry policy;
- fallback rules;
- route journal/cooldowns.

### 6. Execute attempt

The execution controller invokes the selected provider under the threading and timeout contracts.

Events are streamed according to `event-model.md`.

### 7. Validate

Final output is validated.

Possible outcomes:

- pass -> emit result;
- fail and fallback allowed -> route to next provider;
- fail and repair allowed -> call repair provider;
- fail and no fallback -> emit validation error.

### 8. Persist and observe

If cache and privacy policies both allow, the result artifact is stored. The monitor receives a redacted route trace.

## Event ordering

Event ordering is canonical in `docs/technical/event-model.md`. Architecture examples should not diverge from that document.

## Concurrency model

The concurrency contract is canonical in `docs/technical/threading-dispatchers.md`.

Summary:

- Each `stream` call returns a cold `Flow`.
- Execution starts on collection.
- Provider initialization and blocking native calls must not run on UI dispatchers.
- Equivalent concurrent requests may be deduped if policy, cache, privacy, and output settings allow.
- MVP streaming dedupe joins only before first content event; `generate()` can join until terminal result.
- Cancellation is terminal and does not trigger fallback.

## Error model

Errors should be split into stable categories:

- `ProviderUnavailable`
- `CapabilityUnsupported`
- `PolicyViolation`
- `Timeout`
- `RateLimited`
- `TransientProviderError`
- `PermanentProviderError`
- `ValidationFailed`
- `ParsingFailed`
- `Cancelled`
- `Unknown`

The fallback mapping is canonical in `docs/technical/error-fallback-mapping.md`.

## Capability model

Capabilities should be explicit and extensible.

Initial capabilities:

- text generation;
- chat messages;
- streaming;
- structured output;
- JSON mode;
- schema-constrained output;
- embeddings;
- image input;
- audio input;
- tool calling;
- local execution;
- offline;
- cost estimation;
- token counting.

## Privacy model

Privacy is part of the request and is enforced before provider invocation. The canonical model is `docs/technical/privacy-model.md`.

Built-in classes:

- `Public`
- `Internal`
- `Personal`
- `Sensitive`
- `LocalOnly`
- `Custom(value)`

`AllowsCloud` is not a class. Cloud permission is a field in `PrivacyPolicy`.

## Model metadata

Every provider attempt should report:

- provider ID;
- provider kind;
- provider privacy boundary;
- model ID;
- model version if available;
- runtime version if available;
- local/cloud/hybrid boundary;
- capability set;
- availability status;
- input/output token counts if available;
- cost estimate if available.

## Resolved design questions

1. Route planning is suspending because availability/capability checks may require I/O.
2. Validators are final-output only in MVP.
3. `InferenceKey` is required for cache/dedupe; convenience APIs can generate ephemeral keys only when these features are off.
4. Provider priority is policy-defined; numeric priority may exist as metadata but is not sufficient.
5. Prompt registry is out of MVP, but `promptVersion` is in the fingerprint.
6. LiteRT-LM Android/JVM is the first real local adapter.
