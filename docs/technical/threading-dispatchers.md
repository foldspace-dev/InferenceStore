# Threading, dispatchers, cancellation, and dedupe fan-out

Updated: 2026-06-13

This contract exists to prevent the first local adapter from hard-coding unsafe coroutine behavior into the core API.

## Goals

- `stream()` is safe to call from the main thread.
- Collection may happen from UI scopes without blocking UI threads.
- Blocking native/runtime work is isolated in adapter-controlled execution contexts.
- Cancellation is predictable.
- Request dedupe has explicit fan-out semantics.

## Core rule: dispatcher-neutral, not dispatcher-ignorant

`inferencestore-core` does not depend on platform UI dispatchers and does not assume `Dispatchers.IO` exists on every target. Core accepts execution configuration and passes it to providers.

```kotlin
data class InferenceExecutionConfig(
    val planningContext: CoroutineContext = EmptyCoroutineContext,
    val providerContext: CoroutineContext = EmptyCoroutineContext,
    val blockingProviderContext: CoroutineContext = Dispatchers.Default,
    val monitorContext: CoroutineContext = EmptyCoroutineContext
)
```

Defaults are conservative. Apps and adapters may override them.

## `stream()` contract

```kotlin
fun <Output : Any> stream(request: InferenceRequest<Output>): Flow<InferenceEvent<Output>>
```

Requirements:

1. `stream()` returns a cold `Flow` and performs no provider work before collection.
2. The first collector starts planning and execution.
3. Route planning may suspend, but must not perform blocking I/O without switching to an appropriate context.
4. Provider initialization, model load, and synchronous native calls must not run on the caller's UI dispatcher.
5. A provider may use `flowOn` internally, but it must document emitted-thread expectations if they matter.
6. Core event emission must respect Flow backpressure.

## Main-safety requirement

A developer should be able to collect from a UI coroutine scope without causing ANRs or UI hangs, provided the provider adapter follows this contract.

Bad adapter behavior:

```kotlin
flow {
    engine.initialize() // blocking model load on collector context
    emitAll(conversation.sendMessageAsync(prompt))
}
```

Required adapter behavior:

```kotlin
flow {
    val engine = withContext(config.blockingProviderContext) {
        enginePool.acquireOrCreate(modelPath)
    }
    emitAll(engine.stream(prompt))
}
```

## Local/native provider rules

Adapters for local runtimes must:

- perform model load and warmup off the main thread;
- document whether native calls are thread-confined;
- document whether one engine supports concurrent conversations;
- serialize access if the runtime is not thread-safe;
- close native resources on completion, failure, or cancellation;
- convert cancellation into cleanup, not fallback.

## Cancellation semantics

Cancellation is terminal and is not a fallback trigger.

Rules:

1. If the collector cancels, core cancels the active provider attempt.
2. Provider adapters must propagate cancellation where supported.
3. `CancellationException` maps to `InferenceError.Cancelled`, not `Timeout` or `TransientProviderError`.
4. Core must not fallback after caller cancellation.
5. Cleanup errors after cancellation may be logged as debug metadata but must not replace the cancellation result.

## Timeout is not cancellation

Timeouts are created by InferenceStore and mapped through the timeout contract. Caller cancellation is created by the parent coroutine and remains cancellation.

## Dedupe compatibility

Two requests are dedupe-compatible only when all are true:

- identical `InferenceFingerprint`;
- compatible policy ID/version;
- same output spec/schema version;
- same privacy policy version;
- same cache/dedupe setting;
- provider calls are declared side-effect-free;
- both requests opt into dedupe.

## MVP dedupe fan-out semantics

MVP dedupe intentionally avoids late-join token replay complexity.

1. A dedupe group is created when the first compatible collector starts planning.
2. Compatible collectors may join the group until the first content event (`Token` or typed `Partial`) is emitted.
3. After first content, new streaming collectors do not join the in-flight stream; they either read a completed cache artifact or start their own provider call.
4. Joined collectors receive the same upstream provider attempt and terminal result.
5. Cancellation is reference counted:
   - cancelling one joined collector does not cancel upstream while another joined collector remains;
   - when the last joined collector cancels, upstream is cancelled;
   - provider cleanup runs once.
6. Terminal events are broadcast to all joined collectors.
7. Monitor events should record that dedupe occurred without exposing raw content.

Post-MVP can add buffered token replay or `ContentSnapshot` events, but that requires a separate RFC.

## Non-streaming `generate()` semantics

`generate()` may join an in-flight dedupe group until terminal completion because it only needs the final result. It does not need token replay.

```kotlin
suspend fun <Output : Any> generate(request: InferenceRequest<Output>): InferenceResult<Output>
```

If `generate()` joins after first content, it waits for the existing terminal result when the request is compatible and dedupe is allowed.

## Testkit requirements

The testkit must include:

- a virtual clock for delays and timeouts;
- a fake blocking provider that records execution context;
- cancellation probes;
- concurrent collector tests;
- dedupe join-before-token test;
- dedupe late-stream-starts-new-call test;
- `generate()` joins in-flight terminal result test;
- cancellation ref-count test.

## LiteRT-LM adapter implications

The LiteRT-LM adapter must initialize engines and create conversations off the main thread. Its provider documentation must state whether an engine is pooled, whether conversations are one-shot, and how cancellation closes a conversation.
