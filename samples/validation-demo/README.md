# Validation demo

A scripted demo of InferenceStore's **four flagship flows**, each rendered as the
canonical [`RouteTrace`](../../docs/technical/event-model.md) the engine emits (OSS-6).

It is deliberately **engine-independent**: it constructs each `RouteTrace` by hand and
prints it, so it runs as a standalone specification artifact without invoking the
routing engine or any provider. That makes it a value demo you can show — and a
regression check on the trace contract — that does not depend on the core
implementation.

## Run

```bash
./gradlew :samples:validation-demo:run
```

## The four flows

| Flow | What it shows | Trace signal |
|---|---|---|
| **Local success** | On-device model answers; nothing leaves the device. | one `Local` attempt, `Succeeded`, no fallback |
| **Local unavailable → cloud fallback** | Local isn't ready, routing falls back to cloud. | `Local` attempt `Failed` (`ProviderUnavailable`) → `Cloud` attempt `Succeeded`; `fallbackReasons = [ProviderUnavailable]` |
| **Local schema invalid → cloud repair** | Local output fails schema validation; cloud produces a valid result. | `Local` attempt `Failed` (`ValidationFailed`) → `Cloud` attempt `Succeeded`; `fallbackReasons = [SchemaInvalid]` |
| **Local-only privacy denial before cloud invocation** | Privacy gate blocks cloud *before* any call. | cloud in `rejectedProviders` (`PolicyViolation`), **never** in `attempts` |

The fourth flow is the privacy guarantee in action: the cloud provider is recorded as
rejected and is never invoked, so no prompt reaches the network.

## Verification

`ValidationDemoTest` asserts each flow's shape and that the printed trace round-trips
through `RouteTrace` serialization — so `./gradlew :samples:validation-demo:test`
doubles as the "demo runs" check.
