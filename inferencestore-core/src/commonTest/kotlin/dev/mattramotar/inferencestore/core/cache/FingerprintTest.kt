package dev.mattramotar.inferencestore.core.cache

import dev.mattramotar.inferencestore.core.model.ChatMessage
import dev.mattramotar.inferencestore.core.model.ChatRole
import dev.mattramotar.inferencestore.core.model.InferenceInput
import dev.mattramotar.inferencestore.core.model.InferenceKey
import dev.mattramotar.inferencestore.core.model.InferenceRequest
import dev.mattramotar.inferencestore.core.model.OutputSpec
import dev.mattramotar.inferencestore.core.model.PromptSpec
import dev.mattramotar.inferencestore.core.policy.CloudPermission
import dev.mattramotar.inferencestore.core.policy.Policies
import dev.mattramotar.inferencestore.core.policy.PrivacyClass
import dev.mattramotar.inferencestore.core.policy.PrivacyPolicy
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FingerprintTest {

    private val key = InferenceKey("notes.summary", "n1")
    private fun fp(request: InferenceRequest<*>) = DefaultFingerprinter.fingerprint(request)

    @Test
    fun inputHash_changesWithInput_andIsStable() {
        assertNotEquals(fp(InferenceRequest.text(key, "hello")).inputHash, fp(InferenceRequest.text(key, "world")).inputHash)
        assertEquals(fp(InferenceRequest.text(key, "hello")).inputHash, fp(InferenceRequest.text(key, "hello")).inputHash)
    }

    @Test
    fun inputHash_coversMessagesInput() {
        fun messages(content: String) = InferenceRequest<String>(
            key = key,
            input = InferenceInput.Messages(listOf(ChatMessage(ChatRole.User, content))),
            output = OutputSpec.Text,
        )
        assertNotEquals(fp(messages("a")).inputHash, fp(messages("b")).inputHash)
        assertEquals(fp(messages("a")).inputHash, fp(messages("a")).inputHash)
    }

    @Test
    fun rawInput_isNotStored() {
        val fingerprint = fp(InferenceRequest.text(key, "SECRET-PROMPT-TEXT"))
        assertFalse(fingerprint.inputHash.contains("SECRET"))
        assertFalse(fingerprint.toString().contains("SECRET-PROMPT-TEXT"))
    }

    @Test
    fun fingerprint_isDeterministic() {
        val request = InferenceRequest.text(key, "x")
        assertEquals(fp(request), fp(request))
    }

    @Test
    fun promptVersion_isCaptured() {
        val v1 = fp(InferenceRequest.text(key, "x").copy(prompt = PromptSpec(version = "v1")))
        val v2 = fp(InferenceRequest.text(key, "x").copy(prompt = PromptSpec(version = "v2")))
        assertEquals("v1", v1.promptVersion)
        assertNotEquals(v1.promptVersion, v2.promptVersion)
    }

    @Test
    fun privacyClassChange_changesFingerprint() {
        val personal = fp(InferenceRequest.text(key, "x", privacy = PrivacyPolicy.Default))
        val public = fp(InferenceRequest.text(key, "x", privacy = PrivacyPolicy.publicData()))
        assertNotEquals(personal.privacyClass, public.privacyClass)
    }

    @Test
    fun privacyConfigChange_changesPrivacyPolicyVersion() {
        val denied = fp(InferenceRequest.text(key, "x", privacy = PrivacyPolicy(PrivacyClass.Personal, CloudPermission.Denied)))
        val allowed = fp(InferenceRequest.text(key, "x", privacy = PrivacyPolicy(PrivacyClass.Personal, CloudPermission.Allowed)))
        assertNotEquals(denied.privacyPolicyVersion, allowed.privacyPolicyVersion)
    }

    @Test
    fun outputVersion_distinguishesTextJsonCustom() {
        val text = fp(InferenceRequest.text(key, "x"))
        val json = fp(InferenceRequest.json(key, "x", serializer<String>()))
        val custom = fp(InferenceRequest(key, InferenceInput.Text("x"), OutputSpec.Custom { it }))
        assertEquals("text", text.outputVersion)
        assertEquals("custom", custom.outputVersion)
        // JSON is namespaced so a serializer named "text" can't collide with Text output.
        val jsonVersion = json.outputVersion
        assertNotNull(jsonVersion)
        assertTrue(jsonVersion.startsWith("json:"))
        assertNotEquals(text.outputVersion, custom.outputVersion)
        assertNotEquals(text.outputVersion, json.outputVersion)
        assertNotEquals(json.outputVersion, custom.outputVersion)
    }

    @Test
    fun policyVersion_capturesPolicyPresence() {
        val none = fp(InferenceRequest.text(key, "x"))
        val withPolicy = fp(InferenceRequest.text(key, "x", policy = Policies.cloudOnly()))
        assertNull(none.policyVersion)
        assertNotEquals(none.policyVersion, withPolicy.policyVersion)
    }

    @Test
    fun fnv1aHex_isStableAndDistinct() {
        assertEquals(fnv1aHex("hello"), fnv1aHex("hello"))
        assertNotEquals(fnv1aHex("hello"), fnv1aHex("hellp"))
    }
}
