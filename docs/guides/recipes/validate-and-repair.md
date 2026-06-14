# Validate &amp; repair output

**Goal:** require structured output to match a schema. If the on-device model returns
invalid output, repair it by falling back to a stronger cloud model.

## 1. Define your output type

```kotlin
@Serializable
data class Extraction(
    val title: String,
    val tasks: List<String>,
)
```

## 2. Request JSON, validate the schema, enable repair

```kotlin
val request = InferenceRequest.json(
    key = InferenceKey("notes.extract", note.id),
    input = note.body,
    serializer = Extraction.serializer(),
    privacy = PrivacyPolicy.publicData(),
    policy = Policies.validateLocalThenCloudRepair(),
    fallback = FallbackPolicy(repairEnabled = true),
    validator = OutputValidators.validJson(Extraction.serializer()),
)

val result = store.generate(request)
val extraction: Extraction = result.output
```

What each piece does:

- `OutputValidators.validJson(serializer)` parses and validates the final output. Invalid
  output fails with `ParsingFailed`; a domain check you write fails with `ValidationFailed`.
- `FallbackPolicy(repairEnabled = true)` makes those validation failures **repair-eligible**
  instead of terminal. Without it, a validation failure ends the request.
- `Policies.validateLocalThenCloudRepair()` routes to the local model first, then to the
  cloud on a validation failure.

## 3. Inspect what happened

```kotlin
result.trace?.attempts?.forEach { a ->
    println("${a.providerId} (${a.providerKind}) -> ${a.outcome} ${a.errorCategory ?: ""}")
}
// on-device (Local) -> Failed ParsingFailed
// cloud     (Cloud) -> Succeeded
```

!!! note "Validators are final-output only"
    MVP validation runs on the completed output, not per token. Build composite checks with
    `OutputValidators.all(...)`, or a custom `OutputValidator { output, _ -> … }`.

## See also

- [Validator](../../concepts/validator.md)
- [Structured output (RFC-0006)](../../rfcs/RFC-0006-structured-output.md)
- [Error → fallback mapping](../../technical/error-fallback-mapping.md)
