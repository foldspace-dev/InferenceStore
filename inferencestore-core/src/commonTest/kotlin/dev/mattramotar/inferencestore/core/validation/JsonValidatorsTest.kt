package dev.mattramotar.inferencestore.core.validation

import dev.mattramotar.inferencestore.core.provider.ErrorCategory
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** JSON / schema structured-output validators (OSS-23, RFC-0006). */
class JsonValidatorsTest {

    @Serializable
    private data class Summary(val value: String)

    private fun fail(result: ValidationResult): ValidationResult.Fail {
        assertTrue(result is ValidationResult.Fail, "expected Fail, was $result")
        return result
    }

    @Test
    fun validJson_passesForConformingJson() {
        val result = OutputValidators.validJson(Summary.serializer()).validate("ignored", """{"value":"ok"}""")
        assertEquals(ValidationResult.Pass, result)
    }

    @Test
    fun validJson_ignoresUnknownKeys() {
        // Lenient: extra fields the schema doesn't declare are tolerated.
        val result = OutputValidators.validJson(Summary.serializer())
            .validate("ignored", """{"value":"ok","extra":42}""")
        assertEquals(ValidationResult.Pass, result)
    }

    @Test
    fun validJson_failsForMalformedJson_asParsingFailed() {
        val result = fail(OutputValidators.validJson(Summary.serializer()).validate("x", "{ not json"))
        assertEquals(ErrorCategory.ParsingFailed, result.category)
    }

    @Test
    fun validJson_failsForWrongSchema_asParsingFailed() {
        // Well-formed JSON, but missing the required `value` field.
        val result = fail(OutputValidators.validJson(Summary.serializer()).validate("x", """{"other":1}"""))
        assertEquals(ErrorCategory.ParsingFailed, result.category)
    }

    @Test
    fun validJson_failsForNullRawText() {
        assertTrue(OutputValidators.validJson(Summary.serializer()).validate("x", null) is ValidationResult.Fail)
    }

    @Test
    fun wellFormedJson_passesForValid_failsForMalformed() {
        assertEquals(ValidationResult.Pass, OutputValidators.wellFormedJson().validate("x", """{"a":1}"""))
        val malformed = fail(OutputValidators.wellFormedJson().validate("x", "{ nope"))
        assertEquals(ErrorCategory.ParsingFailed, malformed.category)
    }

    @Test
    fun wellFormedJson_doesNotValidateSchema() {
        // Any well-formed JSON passes, regardless of shape.
        assertEquals(ValidationResult.Pass, OutputValidators.wellFormedJson().validate("x", """{"anything":[1,2,3]}"""))
    }
}
