package dev.mattramotar.inferencestore.samples.crossplatform

import dev.mattramotar.inferencestore.core.InferenceStore
import dev.mattramotar.inferencestore.core.event.InferenceResult
import dev.mattramotar.inferencestore.core.model.InferenceKey
import dev.mattramotar.inferencestore.core.model.InferenceRequest
import dev.mattramotar.inferencestore.core.policy.InferencePolicy
import dev.mattramotar.inferencestore.core.policy.Policies
import dev.mattramotar.inferencestore.core.policy.PrivacyPolicy
import dev.mattramotar.inferencestore.core.provider.InferenceProvider
import dev.mattramotar.inferencestore.core.validation.OutputValidators

/**
 * The provider for the current platform. Common code stays provider-neutral; each
 * platform supplies its own (`androidMain` → LiteRT-LM, `iosMain` → Apple Foundation
 * Models in production; demo stand-ins here). This is the one platform-specific seam.
 */
public expect fun platformProvider(): InferenceProvider

/**
 * Shared request + policy + summarize logic — identical on every platform. The Android
 * and iOS apps call [summarize]; only [platformProvider] differs between them, so the
 * route trace shows which platform provider actually ran.
 */
public object CrossPlatformSummarizer {

    public val key: InferenceKey = InferenceKey("notes.summary", "cross-platform")

    /** The shared request: text in/out, strict default privacy, non-blank validator. */
    public fun request(note: String): InferenceRequest<String> = InferenceRequest.text(
        key = key,
        input = "Summarize the following note in one line:\n$note",
        privacy = PrivacyPolicy.Default,
        validator = OutputValidators.nonBlankText,
    )

    /** The shared routing policy: prefer on-device, fall back to cloud. */
    public fun policy(): InferencePolicy = Policies.preferLocalThenCloud()

    /** Summarize using the platform's provider; the trace records which one ran. */
    public suspend fun summarize(note: String): InferenceResult<String> {
        val store = InferenceStore.build {
            provider(platformProvider())
            policy = policy()
        }
        return store.generate(request(note))
    }
}
