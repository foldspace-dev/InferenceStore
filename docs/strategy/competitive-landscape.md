# Competitive landscape

Updated: 2026-06-14

> Accuracy note: this reflects the landscape as of **2026-06**. On-device AI moves
> quickly and every project below is evolving — treat specific capabilities as
> point-in-time and verify against the linked [sources](#sources). The goal here is
> positioning (orchestration vs. runtime execution), not a benchmark.

## Summary

The market is not empty. The opportunity is above the runtime layer.

There are three broad categories:

1. **Vendor hybrid APIs**: Firebase AI Logic, Apple Foundation Models.
2. **Inference runtimes**: LiteRT-LM, ExecuTorch, MLC LLM, llama.cpp-based stacks.
3. **KMP/local inference libraries**: Llamatik, Cactus, InferKt-style bindings.

InferenceStore should be the **application architecture layer** that can use these systems.

## Comparison matrix

Each project links to its official source; see [Sources](#sources) for the full list.

| Project / category | Layer | What it is good at | Why InferenceStore still matters |
|---|---|---|---|
| [Firebase AI Logic](https://firebase.google.com/docs/ai-logic) | Vendor hybrid API | Official Firebase SDKs to call Gemini, with a [hybrid on-device/cloud](https://firebase.google.com/docs/ai-logic/hybrid) path | Vendor-specific (Gemini + Chrome/Android on-device); app teams may need cross-provider policy, Store-like cache/validation, a route testkit, and KMP architecture |
| [Apple Foundation Models](https://developer.apple.com/documentation/foundationmodels) | Vendor hybrid API | On-device Apple Intelligence model via a Swift framework (guided generation, tool calling) | Apple platforms only; KMP teams still need a shared request/policy/test layer and Android/cloud parity |
| [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM) | Inference runtime | Google AI Edge's cross-platform on-device LLM runtime (GPU/NPU, multimodal) | Runtime layer, not the app policy / source-of-truth / route-observability layer (it is InferenceStore's first real local adapter) |
| [ExecuTorch](https://github.com/pytorch/executorch) | Inference runtime | PyTorch Edge on-device runtime across mobile/embedded | Runtime layer, not a KMP app-architecture layer |
| [MLC LLM](https://github.com/mlc-ai/mlc-llm) | Inference runtime | TVM-based universal deployment with OpenAI-compatible APIs across platforms | Can be a provider behind an adapter; does not replace policy/fallback/testkit |
| [Llamatik](https://github.com/ferranpons/Llamatik) | KMP inference library | Kotlin-first local/remote inference around llama.cpp (unified `LlamaBridge` API, optional remote) | Closest KMP neighbor; could be an adapter. InferenceStore adds route policy, privacy enforcement, validation/repair, cache, observability, and Meeseeks lifecycle above it |
| [Cactus](https://github.com/cactus-compute/cactus) | KMP inference library | C++ engine with KMP/Flutter/RN SDKs; on-device with an optional cloud fallback | Overlaps in simple local-first/remote-first routing; InferenceStore offers broader Store-like architecture and provider neutrality (not tied to one engine) |
| Custom app code | — | Maximum control | Duplicated logic, poor testing, inconsistent privacy/fallback/telemetry |

## Key differentiation

### InferenceStore is not a runtime

It should not compete on tokens/sec, quantization, or GPU/NPU backend support.

### InferenceStore is not only a wrapper

It should not be a thin facade over one provider.

### InferenceStore is a policy and semantics layer

Its core value:

- explicit route policy
- provider capability model
- privacy enforcement
- validation and repair
- cache/artifact validity
- deterministic route tests
- observability
- background lifecycle hooks

## Strategic risk: Firebase/Apple absorb the simple case

Firebase and Apple can make “prefer local, fall back cloud” easy within their ecosystem.

InferenceStore must go beyond that:

- non-Gemini cloud providers
- non-platform local runtimes
- KMP common code
- deterministic testkit
- route telemetry
- validation/fallback semantics
- storage/cache semantics
- Meeseeks background lifecycle

## Strategic risk: KMP runtime libraries add routing

Llamatik or Cactus may add more routing features.

InferenceStore should welcome them as adapters while maintaining a broader application architecture scope.

## Strategic risk: too abstract

Developers may prefer concrete adapters over a framework.

Mitigation:

- ship a sample app early
- provide OpenAI-compatible adapter
- provide fake/test provider
- ship one real local adapter in MVP: LiteRT-LM Android/JVM
- keep API small

## Strategic opportunity

A team building AI features across iOS and Android wants to say:

```kotlin
inferenceStore.generate(request)
```

And separately configure:

```kotlin
local-first for private summaries
cloud-first for complex reasoning
local-only for sensitive notes
schema-validate and repair for extraction
```

That is the product.


## Alpha differentiation requirement

The alpha should not look like a Cactus/Firebase clone. It must demonstrate at least three things beyond simple local-first fallback:

1. `PrivacyPolicy` rejects cloud providers before invocation and tests prove zero cloud calls.
2. Final-output validation can trigger cloud repair with a canonical trace.
3. Request fingerprinting includes prompt/output/privacy/policy versions so cache and dedupe behavior is principled.

The LiteRT-LM MVP adapter makes the runtime boundary concrete while keeping InferenceStore's differentiation in orchestration.

## Sources

Official project documentation and repositories (verified 2026-06-14):

- **Firebase AI Logic** — <https://firebase.google.com/docs/ai-logic> ·
  hybrid on-device/cloud: <https://firebase.google.com/docs/ai-logic/hybrid>
- **Apple Foundation Models** — <https://developer.apple.com/documentation/foundationmodels>
- **LiteRT-LM** (Google AI Edge) — <https://github.com/google-ai-edge/LiteRT-LM> ·
  overview: <https://ai.google.dev/edge/litert-lm/overview>
- **ExecuTorch** (PyTorch Edge) — <https://github.com/pytorch/executorch> ·
  docs: <https://pytorch.org/executorch/>
- **MLC LLM** — <https://github.com/mlc-ai/mlc-llm> · docs: <https://llm.mlc.ai/>
- **Llamatik** — <https://github.com/ferranpons/Llamatik> · site: <https://www.llamatik.com/>
- **Cactus** — <https://github.com/cactus-compute/cactus> · site: <https://cactuscompute.com/>

Capabilities change frequently; confirm specifics against these sources before relying
on any point-in-time claim above.
