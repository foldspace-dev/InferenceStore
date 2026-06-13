package dev.mattramotar.inferencestore.testkit

import dev.mattramotar.inferencestore.core.event.InferenceEvent

/**
 * Asserts a stream's [InferenceEvent]s occur in the documented canonical order
 * (`event-model.md`). Each call consumes the next event in sequence.
 */
public class EventAssertions(private val events: List<InferenceEvent<*>>) {
    private var index: Int = 0

    private fun take(label: String): InferenceEvent<*> {
        if (index >= events.size) fail("ran out of events expecting $label (consumed $index of ${events.size})")
        return events[index++]
    }

    public fun started() {
        val e = take("Started")
        if (e !is InferenceEvent.Started) fail("expected Started but was $e")
    }

    public fun providerAttemptStarted(providerId: String? = null) {
        val e = take("ProviderAttemptStarted")
        if (e !is InferenceEvent.ProviderAttemptStarted || (providerId != null && e.attempt.provider.value != providerId)) {
            fail("expected ProviderAttemptStarted${providerId?.let { "($it)" } ?: ""} but was $e")
        }
    }

    public fun token(text: String? = null) {
        val e = take("Token")
        if (e !is InferenceEvent.Token || (text != null && e.text != text)) {
            fail("expected Token${text?.let { "(\"$it\")" } ?: ""} but was $e")
        }
    }

    public fun providerAttemptCompleted() {
        val e = take("ProviderAttemptCompleted")
        if (e !is InferenceEvent.ProviderAttemptCompleted) fail("expected ProviderAttemptCompleted but was $e")
    }

    public fun done() {
        val e = take("Done")
        if (e !is InferenceEvent.Done<*>) fail("expected Done but was $e")
    }

    public fun failed() {
        val e = take("Failed")
        if (e !is InferenceEvent.Failed) fail("expected Failed but was $e")
    }

    private fun fail(message: String): Nothing = throw AssertionError("Event assertion failed: $message")
}

/** Runs [block] of [EventAssertions] against [events]. */
public fun assertEvents(events: List<InferenceEvent<*>>, block: EventAssertions.() -> Unit) {
    EventAssertions(events).apply(block)
}
