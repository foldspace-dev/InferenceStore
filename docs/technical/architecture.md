# Architecture overview

Generated: 2026-06-13

## System purpose

InferenceStore coordinates inference across providers. It does not execute models itself unless an adapter delegates to a runtime. Its core responsibilities are route planning, execution orchestration, validation, fallback, caching hooks, telemetry, and testing.

## Store analogy

| Store concept | InferenceStore concept | Notes |
|---|---|---|
| `Store<Key, Output>` | `InferenceStore` | Orchestrates a request and emits a stream of responses/events. |
| `StoreReadRequest` | `InferenceRequest` | Includes key, input, output type, privacy, cache, policy, timeout, metadata. |
| `Fetcher` | `InferenceProvider` | Fetcher retrieves network data; provider generates output locally or remotely. |
| `SourceOfTruth` | `InferenceArtifactStore` | Stores versioned inference artifacts, not necessarily authoritative truth. |
| `Validator` | `OutputValidator` / `Evaluator` | Checks schema, safety, quality, freshness, policy compliance. |
| `Bookkeeper` | `RouteJournal` / `FailureJournal` | Tracks failed attempts, fallbacks, cooldowns, retry state. |
| `Converter` | `PromptCodec` / `OutputParser` | Converts domain inputs to provider requests and outputs back to typed domain data. |
| Memory cache | Output/request cache | Avoids duplicate or stale-but-valid inference work where policy allows. |
| Request dedupe | Request dedupe | Coalesces equivalent concurrent inference requests. |

## Key difference from Store

Store coordinates data retrieval. InferenceStore coordinates model execution.

That creates differences:

- Inference results can be nondeterministic.
- Output validity may depend on schema, evaluator, task, model version, prompt template, and user context.
- “Freshness” is not just time; it may include input hash, model version, prompt version, provider version, privacy class, and tool availability.
- Some results should never be persisted.
- Cloud fallback may violate privacy unless explicitly allowed.
- Provider capabilities vary dramatically.
- Streaming is central, not an add-on.

## High-level components

```text
Feature code
   |
   v
InferenceStore
   |
   +--> Request fingerprint / cache policy
   |
   +--> Policy engine
   |       |
   |       +--> Provider registry
   |       +--> Availability probes
   |       +--> Capability checks
   |       +--> Privacy/cost/latency rules
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

## Core module boundaries

### `core`

Contains stable abstractions and orchestration:

- request/response/event types
- provider contracts
- policy contracts
- route planner
- execution controller
- validators
- monitors
- cache interfaces
- test-friendly error categories

### `testkit`

Contains deterministic fakes and assertions.

### `provider-*`

Optional provider adapters:

- `provider-openai-compatible`
- `provider-firebase-ai-logic-android`
- `provider-apple-foundation-models-ios`
- `provider-llamatik`
- `provider-cactus`
- `provider-litert-lm`
- `provider-mlc`

### `store-*`

Optional persistent implementations:

- `store-memory`
- `store-sqldelight`

### `meeseeks`

Optional integration for model lifecycle and deferred jobs.

## Request lifecycle

### 1. Normalize

The request is normalized:

- key
- input hash
- prompt/template version
- output type
- privacy class
- cache policy
- policy ID

### 2. Check cache

If allowed, memory cache and artifact store are checked.

The validator can reject cached artifacts if:

- prompt version changed
- model version no longer accepted
- output schema changed
- TTL expired
- privacy policy forbids reuse
- custom validator fails

### 3. Plan route

The policy engine evaluates:

- provider availability
- provider capability
- network state
- device state if provided
- privacy rules
- cost/latency budget
- fallback rules
- route journal/cooldowns

### 4. Execute attempt

The execution controller invokes the selected provider.

Events are streamed:

- `Planning`
- `ProviderSelected`
- `Token`
- `Partial`
- `Done`
- `Error`

### 5. Validate

Final output is validated.

Possible outcomes:

- pass -> emit result
- fail and fallback allowed -> route to next provider
- fail and repair allowed -> call repair provider
- fail and no fallback -> emit validation error

### 6. Persist and observe

If policy allows, the result artifact is stored. The monitor receives a complete route trace.

## Event ordering

For a successful single-provider stream:

```text
Started
CacheChecked
RoutePlanned
ProviderSelected
Token*
ValidationStarted
ValidationCompleted(pass)
Done
```

For fallback:

```text
Started
RoutePlanned
ProviderSelected(local)
Token*
ValidationCompleted(fail)
FallbackStarted(reason = SchemaInvalid)
ProviderSelected(cloud)
Token*
ValidationCompleted(pass)
Done
```

For privacy failure:

```text
Started
RoutePlanned
PolicyRejected(reason = CloudNotAllowed)
Error
```

## Concurrency model

- Each `stream` call returns a cold `Flow`.
- Execution starts on collection.
- Equivalent concurrent requests may be deduped if `cachePolicy.allowDedupe == true`.
- Cancellation propagates to provider calls.
- Provider adapters must not leak work after cancellation.
- Route planning should be side-effect free except monitor events.

## Error model

Errors should be split into:

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

Raw exceptions should be preserved internally and optionally exposed through debug hooks, but public policy should operate on stable categories.

## Capability model

Capabilities should be explicit and extensible.

Initial capabilities:

- text generation
- chat messages
- streaming
- structured output
- JSON mode
- schema-constrained output
- embeddings
- image input
- audio input
- tool calling
- function calling
- local-only
- offline
- cost estimation
- token counting

## Privacy model

Privacy should be part of the request, not out-of-band.

Initial privacy classes:

- `Public`
- `Internal`
- `Personal`
- `Sensitive`
- `LocalOnly`
- user-defined tags

A policy can map classes to allowed providers.

## Model metadata

Every provider attempt should report:

- provider ID
- provider kind
- model ID
- model version if available
- runtime version if available
- local/cloud
- capability set
- availability status
- input/output token counts if available
- cost estimate if available

## Open design questions

1. Should route planning be synchronous or suspending? Recommendation: suspending, because availability/capability checks may require I/O.
2. Should validators see streamed partials? Recommendation: MVP final-only; later partial validators.
3. Should the request key be required? Recommendation: yes for cache/dedupe; convenience APIs can generate one.
4. Should provider priority be numeric or policy-defined? Recommendation: policy-defined; priority is a primitive but not sufficient.
5. Should prompts be first-class? Recommendation: keep prompt registry out of MVP, but include `promptVersion`.
