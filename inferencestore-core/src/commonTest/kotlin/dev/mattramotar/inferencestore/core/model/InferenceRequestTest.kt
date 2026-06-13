package dev.mattramotar.inferencestore.core.model

import dev.mattramotar.inferencestore.core.policy.PrivacyClass
import dev.mattramotar.inferencestore.core.policy.PrivacyPolicy
import dev.mattramotar.inferencestore.core.policy.RetryPolicy
import dev.mattramotar.inferencestore.core.policy.TimeoutPolicy
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class InferenceRequestTest {

    @Serializable
    private data class Summary(val text: String)

    private val key = InferenceKey("notes.summary", "note-1", version = "v1")

    @Test
    fun textFactory_buildsTextInTextOut() {
        val req = InferenceRequest.text(key, "hello")
        val input = req.input
        assertTrue(input is InferenceInput.Text)
        assertEquals("hello", input.value)
        assertEquals(OutputSpec.Text, req.output)
        assertEquals(PrivacyPolicy.Default, req.privacy)
    }

    @Test
    fun messagesInput_isSupported() {
        val req = InferenceRequest(
            key = key,
            input = InferenceInput.Messages(listOf(ChatMessage(ChatRole.User, "hi"))),
            output = OutputSpec.Text,
        )
        assertTrue(req.input is InferenceInput.Messages)
    }

    @Test
    fun jsonOutputSpec_isSupported() {
        val req = InferenceRequest.json(key, "extract a summary", Summary.serializer())
        assertTrue(req.output is OutputSpec.Json)
    }

    @Test
    fun key_isRequiredAndRendersStableString() {
        assertEquals("notes.summary/note-1@v1", key.asString())
        assertEquals("notes.summary/note-1", InferenceKey("notes.summary", "note-1").asString())
    }

    @Test
    fun equality_dependsOnFingerprintRelevantFields() {
        val a = InferenceRequest.text(key, "hello")
        assertEquals(a, InferenceRequest.text(key, "hello"))

        // Different input -> different request.
        assertNotEquals(a, InferenceRequest.text(key, "world"))
        // Different key -> different request.
        assertNotEquals(a, InferenceRequest.text(InferenceKey("notes.summary", "note-2"), "hello"))
        // Different privacy class -> different request.
        assertNotEquals(
            a,
            InferenceRequest.text(key, "hello", privacy = PrivacyPolicy(PrivacyClass.LocalOnly)),
        )
    }

    @Test
    fun defaults_areCanonical() {
        val req = InferenceRequest.text(key, "hi")
        assertEquals(PrivacyClass.Personal, req.privacy.privacyClass) // strict default per privacy-model.md
        assertEquals(0, req.retry.maxRetriesPerAttempt)
        assertEquals(false, req.cache.allowDedupe)
        assertEquals(null, req.policy)
    }

    @Test
    fun key_escapesDelimitersToAvoidCollisions() {
        assertNotEquals(
            InferenceKey("a/b", "c").asString(),
            InferenceKey("a", "b/c").asString(),
        )
        assertNotEquals(
            InferenceKey("ns", "id@v").asString(),
            InferenceKey("ns", "id", version = "v").asString(),
        )
    }

    @Test
    fun policies_rejectInvalidValues() {
        assertFailsWith<IllegalArgumentException> { RetryPolicy(maxRetriesPerAttempt = -1) }
        assertFailsWith<IllegalArgumentException> { TimeoutPolicy(requestTimeout = (-1).seconds) }
        assertFailsWith<IllegalArgumentException> { TimeoutPolicy(attemptTimeout = 0.seconds) }
    }
}
