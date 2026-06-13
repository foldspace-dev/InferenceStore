# Competitive landscape

Updated: 2026-06-13

## Summary

The market is not empty. The opportunity is above the runtime layer.

There are three broad categories:

1. **Vendor hybrid APIs**: Firebase AI Logic, Apple Foundation Models.
2. **Inference runtimes**: LiteRT-LM, ExecuTorch, MLC LLM, llama.cpp-based stacks.
3. **KMP/local inference libraries**: Llamatik, Cactus, InferKt-style bindings.

InferenceStore should be the **application architecture layer** that can use these systems.

## Comparison matrix

| Project / category | What it is good at | Why InferenceStore still matters |
|---|---|---|
| Firebase AI Logic Hybrid API | Official Firebase/Gemini local-cloud path with explicit modes | Vendor-specific; app teams may need cross-provider policy, Store-like cache/validation, route testkit, and KMP architecture |
| Apple Foundation Models | Native Apple model access and provider protocol direction | Apple-only; KMP teams still need shared request/policy/test layer and Android/cloud parity |
| LiteRT-LM | Production-ready edge LLM framework with cross-platform runtime focus | Runtime layer, not app policy/source-of-truth/route observability layer |
| ExecuTorch | PyTorch edge runtime across mobile/embedded | Runtime layer, not KMP app architecture layer |
| MLC LLM | Unified engine with OpenAI-compatible APIs across platforms | Can be a provider; does not replace policy/fallback/testkit |
| Llamatik | Kotlin-first local/remote inference around llama.cpp and related runtimes | Could be an adapter; InferenceStore adds route policy, validation, cache, observability, Meeseeks lifecycle |
| Cactus Kotlin | KMP local/remote/local-first/remote-first inference | Overlaps in simple routing; InferenceStore can provide broader Store-like architecture and provider neutrality |
| Custom app code | Maximum control | Duplicated logic, poor testing, inconsistent privacy/fallback/telemetry |

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
