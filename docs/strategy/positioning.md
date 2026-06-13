# Positioning and messaging

Updated: 2026-06-13

## One-line positioning

**InferenceStore is a policy-driven inference layer for Kotlin Multiplatform apps: local when possible, cloud when necessary, observable always.**

## Short description

InferenceStore lets mobile teams call one inference API while routing requests across local and cloud providers using explicit privacy, cost, latency, availability, and quality policies. It provides streaming, fallback, validation, caching hooks, route telemetry, and test utilities so AI features do not need to reimplement inference orchestration.

## Long description

Modern AI apps increasingly need both local and cloud inference. Local models are useful for privacy, offline support, low latency, and lower marginal cost. Cloud models are useful for coverage, quality, long context, multimodal capabilities, and tool use. Most apps end up scattering provider checks, network checks, fallback rules, prompt caching, retry logic, and telemetry across feature code.

InferenceStore brings a Store-like architecture to inference. It gives developers a stable KMP API for requests and streaming results, plus a policy engine that chooses the best provider for the current request and device. Providers can be local runtimes, platform models, Firebase/Gemini, OpenAI-compatible backends, or app-specific services.

## The slogan

> Store made data access boring. InferenceStore makes hybrid AI routing boring.

## Audience-specific messaging

### For app engineers

Stop writing local/cloud fallback logic in every feature. Define providers once, write a routing policy once, and call one API from shared KMP code.

### For platform teams

Centralize AI governance: privacy boundaries, provider choice, cost limits, model rollout, fallback rules, observability, and deterministic tests.

### For OSS/runtime maintainers

Expose your runtime through a common KMP provider interface so app developers can adopt it without locking their architecture to one backend.

### For product teams

Ship AI features that work offline where possible, use cloud when needed, and can be measured in terms of latency, cost, quality, and privacy behavior.

## Taglines

- Local when possible. Cloud when necessary. Observable always.
- Offline-first AI architecture for Kotlin Multiplatform.
- A Store-like layer for hybrid inference.
- One inference API. Explicit routing. Auditable results.
- Make local/cloud AI routing a policy, not feature code.

## What to avoid saying

Avoid:

```text
Universal inference runtime
```

This suggests competing with LiteRT-LM, ExecuTorch, MLC, llama.cpp, or platform SDKs.

Avoid:

```text
AI cache
```

This undersells routing/validation and invites incorrect assumptions.

Avoid:

```text
Automatic best model selection
```

This overpromises. The library should enable policy-driven selection, not pretend model quality is universally knowable.

Avoid:

```text
Drop-in replacement for Firebase AI Logic
```

Firebase can be an adapter, not the enemy.

## Naming options

### InferenceStore

Pros:
- direct Store lineage
- immediately understandable
- SEO-friendly enough
- clear technical meaning

Cons:
- may imply official Store/MNF project
- less playful

### StoreAI

Pros:
- concise
- strong Store connection

Cons:
- could sound like an AI product store/marketplace
- may create trademark/brand ambiguity

### ModelStore

Pros:
- natural fit for model artifacts
- good if model lifecycle becomes central

Cons:
- less about inference routing
- could be confused with model registry/download manager

### Route

Pros:
- captures the core decision
- short

Cons:
- too generic

### Sage / Oracle / Router

Pros:
- memorable

Cons:
- less aligned with Store/Meeseeks credibility

Recommendation: use **InferenceStore** as the working name through validation. Revisit before public 1.0.

## Launch narrative

### Blog post title options

- “Store, but for inference”
- “Offline-first AI for Kotlin Multiplatform”
- “Building a policy layer for local and cloud AI”
- “Making hybrid inference testable”
- “Why mobile AI needs a source-of-truth layer”

### Launch post outline

1. Local inference is now real, but app architecture is messy.
2. Cloud is still necessary.
3. Hybrid inference needs policy, validation, fallback, and observability.
4. Store provides a useful mental model but inference is different.
5. Introduce InferenceStore.
6. Show a local-first note summarization example.
7. Show a schema-failed local result falling back to cloud repair.
8. Invite adapter authors and early app teams.

## README hero

~~~markdown
# InferenceStore

Policy-driven inference orchestration for Kotlin Multiplatform.

```kotlin
val answer = inferenceStore.generate(
    request = InferenceRequest.text(
        key = InferenceKey("notes.summary", note.id),
        input = note.body,
        privacy = PrivacyPolicy.personal(
            cloud = CloudPermission.ApprovedProviders(setOf(ProviderId("cloud")))
        )
    ),
    policy = Policies.preferLocalThenCloud()
)
```

Local when possible. Cloud when necessary. Observable always.
~~~

## Differentiation narrative

InferenceStore is not trying to pick the winning model runtime. It assumes the runtime ecosystem will keep changing. Its job is to keep app architecture stable while providers, models, devices, and cloud APIs evolve.

## Community ask

Ask contributors for adapters, sample apps, validator patterns, and routing policies rather than broad feature requests.

Good first contribution categories:

- Provider adapter
- Policy preset
- Validator
- Sample app
- Documentation recipe
- Testkit scenario
- Runtime capability matrix


## MVP credibility note

The alpha must not be fake-only. Fake providers are for deterministic tests, but the MVP includes LiteRT-LM Android/JVM as a real local adapter so the launch narrative demonstrates real local-runtime behavior.
