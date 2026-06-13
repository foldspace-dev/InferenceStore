# Strategy

Updated: 2026-06-13

## Executive summary

Build **InferenceStore** as the Store-like orchestration layer for local/cloud inference in Kotlin Multiplatform apps.

The project should not compete with inference runtimes. It should make runtimes usable inside production application architecture.

The wedge:

> Mobile teams want local inference for privacy, latency, offline behavior, and cost, but they still need cloud inference for quality, long context, unsupported capabilities, and device coverage. They need one testable abstraction that makes those tradeoffs explicit and observable.

## Strategic hypothesis

The winning abstraction is not:

```text
Run model X on device Y.
```

It is:

```text
For this request, on this device, under this privacy/cost/latency/quality policy,
choose the right inference path, stream the result, validate it, fall back if needed,
record what happened, and keep the app code stable.
```

## Why now

The platform and runtime landscape has crossed the threshold where hybrid inference is real but not yet architecturally boring.

- Android and Firebase now expose explicit hybrid inference modes.
- Apple Foundation Models provides native Swift access to Apple Intelligence models and a path for cloud/provider integrations.
- LiteRT-LM, ExecuTorch, MLC LLM, Llamatik, Cactus, and similar runtimes are rapidly improving.
- Enterprises and consumer apps increasingly want privacy-sensitive, offline, and cost-controlled AI features.
- Teams shipping KMP apps lack a neutral architecture layer for local/cloud inference routing.

## Strategic fit with Store

Store gave teams a stable way to coordinate:

```text
network + memory cache + local storage + validation + source of truth + request dedupe
```

InferenceStore should coordinate:

```text
local model + cloud model + output cache + validators/evals + route policy + fallback + observability
```

Store made data access predictable. InferenceStore should make model execution predictable.

## Strategic fit with Meeseeks

Meeseeks is useful because hybrid inference is not only an interactive foreground problem. It also needs background operations:

- model availability checks
- model downloads
- model warmup
- model pruning
- queued inference retries
- batch embeddings
- telemetry upload
- policy/config refresh
- redrive of failed local/cloud routes

Meeseeks should not be required for the synchronous core, but it should become the recommended runtime for lifecycle and deferred work.

## Target users

### Primary

KMP/mobile engineers building AI-powered features across iOS and Android.

Their problems:

- “I do not want every feature team to hard-code cloud/local decision logic.”
- “I need privacy-sensitive prompts to stay local.”
- “I need cloud fallback when the local model is missing or not good enough.”
- “I need to know which model actually served the response.”
- “I need to test routing and fallback deterministically.”
- “I need support for multiple providers without rewriting app code.”

### Secondary

Platform teams at companies adopting local inference.

Their problems:

- “We need a shared AI architecture layer.”
- “We need governance, policy, telemetry, and privacy boundaries.”
- “We need to roll providers/models out with feature flags.”
- “We need consistent observability across apps.”

### Tertiary

OSS/KMP ecosystem developers.

Their problems:

- “I want to plug in my runtime.”
- “I need a common adapter interface.”
- “I want example apps that show realistic offline-first AI.”

## Positioning

### Internal shorthand

> Store, but for inference.

### External positioning

> Policy-driven inference orchestration for Kotlin Multiplatform: local when possible, cloud when necessary, observable always.

### Category

```text
KMP inference orchestration
hybrid AI routing
offline-first AI architecture
```

### Differentiation

| Alternative | Strength | Gap InferenceStore should fill |
|---|---|---|
| Firebase AI Logic Hybrid API | Official local/cloud Gemini path | Vendor-specific, experimental parts, limited cross-provider policy/control |
| Apple Foundation Models | Native Apple integration | Apple-only, not KMP/cross-platform by itself |
| LiteRT-LM / ExecuTorch / MLC | Runtime performance and deployment | Not an app-level policy/source-of-truth framework |
| Llamatik / Cactus | KMP/local inference support | Closer to execution/runtime abstraction than Store-like orchestration |
| Custom app code | Maximum flexibility | Duplicated policy, fallback, observability, privacy, and testing logic |

## Product principles

1. **Provider-neutral, not provider-agnostic theater**  
   The abstraction must expose real capability differences rather than hiding them.

2. **Streaming-first**  
   Inference is interactive. Token streaming and route state changes are first-class.

3. **Privacy policy as API**  
   A request carries the canonical `PrivacyPolicy`: privacy class, cloud permission, persistence permission, telemetry permission, and provider-boundary requirements. The execution controller enforces this before provider invocation.

4. **Observable by default**  
   Every result should include route, provider, model, latency, fallback reason, and validator outcome.

5. **Deterministic tests**  
   Feature teams should assert “local attempted, cloud fallback occurred because schema failed” without real models.

6. **Adapters are optional modules**  
   Core stays small and stable; runtime/provider churn lives in adapters.

7. **Validation is not optional in serious usage**  
   Structured output, task validators, repair policies, and eval hooks are central to production reliability.

8. **Do not oversell cacheability**  
   Generated outputs are versioned artifacts, not universally reusable data.

## Open-source strategy

### License

Apache-2.0 is the natural default because it matches Store and Meeseeks.

### Repository strategy

Option A: independent repo under your namespace first.

Pros:
- fast iteration
- no MNF process overhead
- personal credibility
- easy experimentation

Cons:
- lower institutional signal
- may be perceived as less canonical

Option B: propose under Mobile Native Foundation later.

Pros:
- aligns with Store lineage
- stronger community trust
- more likely to attract KMP/mobile platform teams

Cons:
- higher governance overhead
- premature formalization could slow pivots

Recommendation: start independent, design the docs and package naming so it can graduate later.

## Adoption strategy

### Phase 0: Narrative and validation

Deliver:
- README
- architecture doc
- sample API
- user interview script
- static/scripted fake-provider demo traces
- comparison page
- first real local adapter decision

Goal:
- validate whether teams want policy + fallback + observability, not just local inference.

### Phase 1: Thin core

Deliver:
- common API
- fake/test provider
- OpenAI-compatible adapter
- LiteRT-LM Android/JVM local adapter
- local-first/cloud-fallback policy
- schema validation
- route telemetry
- privacy/error/timeout contracts

Goal:
- make the first demo feel inevitable.

### Phase 2: Alpha and mobile proof

Deliver:
- sample app with fake and LiteRT-LM modes
- adapter authoring guide
- iOS adapter decision/prototype
- Firebase/Apple adapter exploration

Goal:
- prove KMP value and device-aware routing without pretending all local adapters are equivalent.

### Phase 3: Production hardening

Deliver:
- caching/source-of-truth
- persistent route journal
- Meeseeks integration
- telemetry exporters
- remote-config policy hooks
- docs/cookbook

Goal:
- make it suitable for early adopters.

## Business / sustainability options

### OSS-first

Use open-source core and adapters. Build credibility and community adoption.

### Sponsorship

Offer GitHub Sponsors / company sponsorship around KMP AI infrastructure.

### Commercial support

Provide integration support for companies adopting hybrid inference.

### Hosted control plane, later

Only after adoption:
- remote policy config
- model rollout management
- telemetry dashboard
- eval regression tracking
- cost/latency routing analytics

Avoid starting with SaaS. The trust and adoption are in the library.

## Risks

### Risk: platform vendors absorb the simple case

Firebase and Apple already offer simple local/cloud paths. Mitigation: focus on vendor-neutral policy, testing, observability, validation, and KMP.

### Risk: runtime churn

Adapters will break as runtimes evolve. Mitigation: keep adapters optional and unstable until core semantics stabilize.

### Risk: inference caching is misunderstood

Naive response caching is dangerous. Mitigation: use explicit fingerprints, model versions, template versions, privacy class, privacy policy version, and validator rules.

### Risk: scope explosion

Hybrid inference touches everything: model downloads, telemetry, evals, prompt management, semantic cache, tools, multimodal, background tasks. Mitigation: strict MVP.

### Risk: Store analogy creates wrong expectations

Inference is nondeterministic and capability-dependent. Mitigation: document differences clearly.

## Strategic recommendation

Proceed, but with a narrow wedge:

> InferenceStore Core: KMP streaming inference router with provider capabilities, canonical privacy enforcement, local/cloud policy, fallback reasons, structured-output validation, route telemetry, deterministic tests, and one real local adapter.

Do not build a general model runtime. Do not begin with semantic cache. Do not begin with model download orchestration. Make the orchestration semantics compelling first.
