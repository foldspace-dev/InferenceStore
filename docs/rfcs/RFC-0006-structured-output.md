# RFC-0006: Structured output (JSON / schema)

Status: Accepted for MVP
Updated: 2026-06-14

## Summary

Define how InferenceStore produces and validates structured (JSON / schema-typed)
output across providers whose native support varies. Covers:

- serializer-based parsing (`OutputSpec.Json`);
- schema-constrained providers vs. post-hoc validation;
- validation-triggered repair fallback;
- streamed partial JSON (explicitly post-MVP).

## Motivation

Structured output — "give me a typed object, not prose" — is one of the most common
inference use cases (extraction, classification, tool arguments, form filling). But
the capability is unevenly supported:

- some cloud providers enforce a JSON schema server-side (constrained decoding /
  `response_format`), guaranteeing well-formed, conformant output;
- many local / on-device models only emit free text and must be parsed and validated
  after the fact, and they fail more often (truncation, extra prose, invalid JSON).

The library must let an app request a typed result, get strong guarantees where the
provider offers them, and degrade safely (validate + repair on a stronger provider)
where it does not — without the app hand-rolling parse/validate/retry logic per call.

## Proposal

### 1. Serializer-based parsing (`OutputSpec.Json`)

The request carries the output contract. `OutputSpec.Json<T>(serializer)` (already in
the core model) declares "decode the model's text into `T` with this
`kotlinx.serialization` `KSerializer`". A provider adapter that produces `T` does so by
decoding its raw text with the serializer; a decode failure is surfaced as
`ErrorCategory.ParsingFailed` (canonical in `error-fallback-mapping.md`), which maps to
`FallbackReason.OutputParserFailed`.

`kotlinx.serialization` is the parsing substrate because it is multiplatform, reflection-
free, and already the project's serialization dependency. Apps that need a parser the
serializer can't express use `OutputSpec.Custom`.

### 2. Schema-constrained providers vs. post-hoc validation

Two strategies, both first-class:

- **Schema-constrained (provider-enforced).** A provider that can constrain decoding to a
  schema advertises `Capability.StructuredOutput`; the adapter passes the schema/serializer
  to the provider so output is well-formed by construction. Routing already filters on
  required capabilities (a `Json`/`Custom` output requires `StructuredOutput`), so a request
  for structured output is only routed to providers that can produce it.
- **Post-hoc validation (library-enforced).** When a provider returns free text — or when an
  app wants defense-in-depth even against a "constrained" provider — the output is validated
  after generation by an `OutputValidator`. This RFC adds two built-ins
  (`OutputValidators`):
  - `wellFormedJson()` — the raw output is syntactically valid JSON;
  - `validJson(serializer)` — the raw output decodes to the serializer's type
    (well-formed **and** schema-conformant).

  Both inspect the provider's `rawText`, fail with `ErrorCategory.ParsingFailed`, and use a
  lenient parser (`ignoreUnknownKeys`, `isLenient`) so validation judges conformance, not
  style.

Post-hoc validation is the MVP baseline (works with every provider); schema-constrained is a
capability-gated optimization layered on top.

### 3. Validation-triggered repair

`ParsingFailed` is terminal by default but **repair-eligible**: with
`FallbackPolicy(repairEnabled = true)` and the `validateLocalThenCloudRepair` preset, a
local model that emits malformed or non-conforming JSON falls back to a stronger
(typically schema-capable cloud) provider, which re-generates and is validated again. This
reuses the OSS-17 validation→fallback machinery — no new control flow:

```kotlin
@Serializable data class Summary(val value: String)

store.generate(
    InferenceRequest.text(
        key, prompt,
        policy = Policies.validateLocalThenCloudRepair(),
        fallback = FallbackPolicy(repairEnabled = true),
        validator = OutputValidators.validJson(Summary.serializer()),
    ),
)
// local emits "{ not json" -> ParsingFailed -> repair on cloud -> valid JSON
```

This example uses the **post-hoc validation** path (section 2), which is provider-agnostic:
the validator inspects raw text and is independent of the `StructuredOutput` capability gate,
so it works even with providers that only emit free text. A request that instead declared
`OutputSpec.Json` would additionally be routed only to `StructuredOutput`-capable providers.

### 4. Streamed partial JSON — post-MVP

MVP validates the **final** output only. Streaming partial-JSON parsing (incrementally
decoding a growing buffer, surfacing typed partials) is deferred: it interacts with the
streaming guardrails (time-to-first-token / idle timeouts) and partial-output validation,
which are themselves post-MVP. `OutputSpec.Json` results still stream token deltas; only the
typed value is materialized at completion. The `OutputValidator` contract already documents
final-output-only validation, leaving room to add a partial validator later without breaking
the API.

## Initial API (this RFC)

```kotlin
object OutputValidators {
    fun wellFormedJson(): OutputValidator<Any>
    fun <Schema : Any> validJson(serializer: KSerializer<Schema>): OutputValidator<Any>
}
```

Returned as `OutputValidator<Any>` so they apply to any request output type (the validator is
contravariant in its output and only reads `rawText`).

## Alternatives considered

- **A bespoke JSON-schema (draft-07) validator.** Rejected for MVP: `kotlinx.serialization`
  descriptors already encode the app's schema, and a second schema language is redundant and
  heavyweight. Revisit if interop with external JSON-Schema documents is required.
- **Parsing failures as a distinct top-level result instead of `ParsingFailed`.** Rejected:
  folding into the canonical error→fallback table keeps one repair path and one trace
  vocabulary.

## Open questions

1. Should `validJson` optionally surface the decoded value to avoid a double parse on the
   success path? Recommendation: not in MVP — the validator is a guard; typed decoding belongs
   to `OutputSpec.Json`.
2. Should repair prompts be augmented with the validation error to help the repair provider?
   Recommendation: post-MVP, and only as redacted metadata (no raw model output echoed).
