package dev.mattramotar.inferencestore.samples.notes

import dev.mattramotar.inferencestore.core.InferenceStore
import dev.mattramotar.inferencestore.core.event.InferenceResult
import dev.mattramotar.inferencestore.core.model.InferenceKey
import dev.mattramotar.inferencestore.core.model.InferenceRequest
import dev.mattramotar.inferencestore.core.policy.PrivacyPolicy
import dev.mattramotar.inferencestore.core.validation.OutputValidators

/**
 * The app-level use case: turn a note into a one-line summary via an
 * [InferenceStore]. The caller chooses the providers/policy when building the store;
 * the summarizer just expresses intent — a key, the input, a privacy policy, and a
 * non-blank validator.
 */
public class NoteSummarizer(private val store: InferenceStore) {

    public suspend fun summarize(
        note: String,
        privacy: PrivacyPolicy = PrivacyPolicy.Default,
    ): InferenceResult<String> = store.generate(
        InferenceRequest.text(
            key = InferenceKey("notes.summary", "demo"),
            input = "Summarize the following note in one line:\n$note",
            privacy = privacy,
            validator = OutputValidators.nonBlankText,
        ),
    )
}
