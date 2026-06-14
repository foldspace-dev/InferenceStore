package dev.mattramotar.inferencestore.core.validation

import dev.mattramotar.inferencestore.core.provider.ErrorCategory
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

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

    // Lenient JSON for post-hoc output checks: models often emit extra fields or
    // slightly loose JSON, and validation should judge schema conformance, not style.
    private val validationJson: Json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Passes when the provider's raw output is syntactically well-formed JSON
     * (structured-output post-hoc validation, RFC-0006). Inspects `rawText`, so it
     * applies to any output type; a null `rawText` fails. Failure is [ErrorCategory.ParsingFailed]
     * so it can repair on a schema-capable provider (`FallbackPolicy.repairEnabled`).
     */
    public fun wellFormedJson(): OutputValidator<Any> = OutputValidator { _, rawText ->
        val text = rawText
            ?: return@OutputValidator ValidationResult.Fail("no raw text to validate as JSON", ErrorCategory.ParsingFailed)
        try {
            validationJson.parseToJsonElement(text)
            ValidationResult.Pass
        } catch (_: SerializationException) {
            ValidationResult.Fail("output is not well-formed JSON", ErrorCategory.ParsingFailed)
        }
    }

    /**
     * Passes when the provider's raw output decodes to [Schema] via [serializer]
     * (serializer-based schema validation, RFC-0006) — well-formed AND
     * schema-conformant. Inspects `rawText`; a null `rawText` fails. Failure is
     * [ErrorCategory.ParsingFailed] so malformed/non-conforming local output can
     * repair on a schema-capable provider (`FallbackPolicy.repairEnabled`).
     */
    public fun <Schema : Any> validJson(serializer: KSerializer<Schema>): OutputValidator<Any> =
        OutputValidator { _, rawText ->
            val text = rawText
                ?: return@OutputValidator ValidationResult.Fail("no raw text to validate as JSON", ErrorCategory.ParsingFailed)
            try {
                validationJson.decodeFromString(serializer, text)
                ValidationResult.Pass
            } catch (_: SerializationException) {
                ValidationResult.Fail(
                    "output is not valid JSON for ${serializer.descriptor.serialName}",
                    ErrorCategory.ParsingFailed,
                )
            }
        }
}
