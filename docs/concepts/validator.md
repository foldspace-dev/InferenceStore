# Validator

An `OutputValidator` checks a provider's final output. A failure can trigger
configured repair — fall back to a stronger provider and try again — via
`FallbackPolicy(repairEnabled = true)` and the `validateLocalThenCloudRepair` preset.

```kotlin
val request = InferenceRequest.text(
    key, prompt,
    fallback = FallbackPolicy(repairEnabled = true),
    validator = OutputValidators.validJson(MySchema.serializer()),
)
```

Built-ins (`OutputValidators`): `nonBlankText`, `predicate(...)`, `all(...)`,
`wellFormedJson()`, and `validJson(serializer)` for structured output. Validators fail
with a stable `ErrorCategory` — `ValidationFailed` (predicate/domain) or `ParsingFailed`
(schema/parse). Both are terminal by default and become repair-eligible only when
`FallbackPolicy.repairEnabled` is set.

MVP validates the **final** output; partial/streaming validation is post-MVP.

Learn more: [structured output RFC](../rfcs/RFC-0006-structured-output.md),
[error → fallback mapping](../technical/error-fallback-mapping.md).
