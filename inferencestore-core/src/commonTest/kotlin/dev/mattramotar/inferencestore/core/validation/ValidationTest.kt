package dev.mattramotar.inferencestore.core.validation

import dev.mattramotar.inferencestore.core.provider.ErrorCategory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Built-in output validators (OSS-17). */
class ValidationTest {

    @Test
    fun predicate_passesAndFails() {
        val validator = OutputValidators.predicate<String>("must be ok") { it == "ok" }
        assertEquals(ValidationResult.Pass, validator.validate("ok", null))
        val fail = validator.validate("no", null)
        assertTrue(fail is ValidationResult.Fail)
        assertEquals("must be ok", fail.reason)
        assertEquals(ErrorCategory.ValidationFailed, fail.category)
    }

    @Test
    fun predicate_canMapToParsingFailed() {
        val validator = OutputValidators.predicate<String>("parse", ErrorCategory.ParsingFailed) { false }
        val fail = validator.validate("anything", null)
        assertTrue(fail is ValidationResult.Fail)
        assertEquals(ErrorCategory.ParsingFailed, fail.category)
    }

    @Test
    fun nonBlankText_rejectsBlank() {
        assertEquals(ValidationResult.Pass, OutputValidators.nonBlankText.validate("hi", null))
        assertTrue(OutputValidators.nonBlankText.validate("   ", null) is ValidationResult.Fail)
    }

    @Test
    fun all_returnsFirstFailureElsePass() {
        val validator = OutputValidators.all(
            OutputValidators.nonBlankText,
            OutputValidators.predicate("too short") { it.length > 2 },
        )
        assertTrue(validator.validate("", null) is ValidationResult.Fail) // blank fails first
        assertEquals(ValidationResult.Pass, validator.validate("abc", null))
        val fail = validator.validate("ab", null)
        assertTrue(fail is ValidationResult.Fail)
        assertEquals("too short", fail.reason)
    }
}
