package dev.mattramotar.inferencestore.core.validation

import dev.mattramotar.inferencestore.core.provider.ErrorCategory
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException

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

    // Schema check tolerates extra fields models often add, but stays strict about
    // required fields (no isLenient). Well-formedness uses the default parser.
    private val schemaJson: Json = Json { ignoreUnknownKeys = true }

    // Any parse failure — including a StackOverflowError from pathologically nested
    // input — must map to ParsingFailed so it stays repair-eligible, never escaping to
    // the engine's terminal Unknown. Cancellation still propagates.
    private inline fun jsonCheck(reason: String, parse: () -> Unit): ValidationResult =
        try {
            parse()
            ValidationResult.Pass
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            ValidationResult.Fail(reason, ErrorCategory.ParsingFailed)
        }

    /**
     * Passes when the provider's raw output parses as JSON (structured-output post-hoc
     * validation, RFC-0006). Catches malformed input — truncated/garbled objects are the
     * common LLM failure; note `parseToJsonElement` is permissive about bare top-level
     * primitives, so use [validJson] when a specific shape is required. Inspects `rawText`,
     * so it applies to any output type; a null `rawText` fails. Failure is
     * [ErrorCategory.ParsingFailed] so it can repair on a schema-capable provider
     * (`FallbackPolicy.repairEnabled`).
     */
    public fun wellFormedJson(): OutputValidator<Any> = OutputValidator { _, rawText ->
        val text = rawText
            ?: return@OutputValidator ValidationResult.Fail("no raw text to validate as JSON", ErrorCategory.ParsingFailed)
        jsonCheck("output is not well-formed JSON") { Json.parseToJsonElement(text) }
    }

    /**
     * Passes when the provider's raw output decodes to [Schema] via [serializer]
     * (serializer-based schema validation, RFC-0006) — well-formed AND
     * schema-conformant (extra fields tolerated, required fields enforced). Inspects
     * `rawText`; a null `rawText` fails. Failure is [ErrorCategory.ParsingFailed] so
     * malformed/non-conforming local output can repair on a schema-capable provider
     * (`FallbackPolicy.repairEnabled`).
     */
    public fun <Schema : Any> validJson(serializer: KSerializer<Schema>): OutputValidator<Any> =
        OutputValidator { _, rawText ->
            val text = rawText
                ?: return@OutputValidator ValidationResult.Fail("no raw text to validate as JSON", ErrorCategory.ParsingFailed)
            jsonCheck("output is not valid JSON for ${serializer.descriptor.serialName}") {
                schemaJson.decodeFromString(serializer, text)
            }
        }
}
