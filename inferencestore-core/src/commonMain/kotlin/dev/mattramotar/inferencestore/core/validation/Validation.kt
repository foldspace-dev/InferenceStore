package dev.mattramotar.inferencestore.core.validation

import dev.mattramotar.inferencestore.core.provider.ErrorCategory

/**
 * The outcome of validating a final model output (`caching-validation-dedupe.md`).
 * MVP is final-output validation only; partial/streaming validation is post-MVP.
 */
public sealed interface ValidationResult {
    public data object Pass : ValidationResult

    /**
     * Validation failed. [reason] is an app-authored message (not raw model output)
     * and [category] selects the stable error the failure maps to — `ValidationFailed`
     * for predicate/domain validators, `ParsingFailed` for parser/schema validators.
     */
    public data class Fail(
        public val reason: String,
        public val category: ErrorCategory = ErrorCategory.ValidationFailed,
    ) : ValidationResult
}

/**
 * Validates a final output. A failure can trigger configured fallback/repair
 * (see `FallbackPolicy.repairEnabled` and the `validateLocalThenCloudRepair`
 * preset). [rawText] is the provider's raw text when available, for validators
 * (e.g. JSON/schema) that need to inspect it.
 */
public fun interface OutputValidator<in Output : Any> {
    public fun validate(output: Output, rawText: String?): ValidationResult
}

/** Built-in [OutputValidator]s and combinators. */
public object OutputValidators {

    /** Passes when [predicate] holds for the output; otherwise fails with [reason]. */
    public fun <Output : Any> predicate(
        reason: String,
        category: ErrorCategory = ErrorCategory.ValidationFailed,
        predicate: (Output) -> Boolean,
    ): OutputValidator<Output> = OutputValidator { output, _ ->
        if (predicate(output)) ValidationResult.Pass else ValidationResult.Fail(reason, category)
    }

    /** Rejects blank text output. */
    public val nonBlankText: OutputValidator<String> = OutputValidator { text, _ ->
        if (text.isNotBlank()) ValidationResult.Pass else ValidationResult.Fail("blank output")
    }

    /** Runs [validators] in order, returning the first [ValidationResult.Fail] or [ValidationResult.Pass]. */
    public fun <Output : Any> all(vararg validators: OutputValidator<Output>): OutputValidator<Output> =
        OutputValidator { output, rawText ->
            for (validator in validators) {
                val result = validator.validate(output, rawText)
                if (result is ValidationResult.Fail) return@OutputValidator result
            }
            ValidationResult.Pass
        }
}
