package dev.mattramotar.inferencestore.core.cache

import dev.mattramotar.inferencestore.core.model.InferenceInput
import dev.mattramotar.inferencestore.core.model.InferenceKey
import dev.mattramotar.inferencestore.core.model.InferenceRequest
import dev.mattramotar.inferencestore.core.model.OutputSpec
import dev.mattramotar.inferencestore.core.model.PromptSpec
import dev.mattramotar.inferencestore.core.policy.CloudPermission
import dev.mattramotar.inferencestore.core.policy.Policies
import dev.mattramotar.inferencestore.core.policy.PrivacyClass
import dev.mattramotar.inferencestore.core.policy.PrivacyPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class FingerprintTest {

    private val key = InferenceKey("notes.summary", "n1")
    private fun fp(request: InferenceRequest<*>) = DefaultFingerprinter.fingerprint(request)

    @Test
    fun inputHash_changesWithInput_andIsStable() {
        assertNotEquals(fp(InferenceRequest.text(key, "hello")).inputHash, fp(InferenceRequest.text(key, "world")).inputHash)
        assertEquals(fp(InferenceRequest.text(key, "hello")).inputHash, fp(InferenceRequest.text(key, "hello")).inputHash)
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
    fun privacyConfigChange_changesPolicyVersion() {
        val denied = fp(InferenceRequest.text(key, "x", privacy = PrivacyPolicy(PrivacyClass.Personal, CloudPermission.Denied)))
        val allowed = fp(InferenceRequest.text(key, "x", privacy = PrivacyPolicy(PrivacyClass.Personal, CloudPermission.Allowed)))
        assertNotEquals(denied.privacyPolicyVersion, allowed.privacyPolicyVersion)
    }

    @Test
    fun outputVersion_distinguishesTypes() {
        val text = fp(InferenceRequest.text(key, "x"))
        val custom = fp(InferenceRequest(key, InferenceInput.Text("x"), OutputSpec.Custom { it }))
        assertEquals("text", text.outputVersion)
        assertNotEquals(text.outputVersion, custom.outputVersion)
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
