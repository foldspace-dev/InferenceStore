package dev.mattramotar.inferencestore.testkit

import dev.mattramotar.inferencestore.core.event.AttemptOutcome
import dev.mattramotar.inferencestore.core.event.FallbackReason
import dev.mattramotar.inferencestore.core.event.FinalStatus
import dev.mattramotar.inferencestore.core.event.RouteTrace
import dev.mattramotar.inferencestore.core.provider.ErrorCategory

/** Assertions over a [RouteTrace] — readable enough to document a route decision. */
public class RouteAssertions(private val trace: RouteTrace) {

    /** Asserts [providerId] was invoked (appears in the attempts). */
    public fun attempted(providerId: String) {
        if (trace.attempts.none { it.providerId == providerId }) {
            fail("expected '$providerId' to be attempted; attempts=${attemptIds()}")
        }
    }

    /** Asserts [providerId] was never invoked. */
    public fun didNotAttempt(providerId: String) {
        if (trace.attempts.any { it.providerId == providerId }) {
            fail("expected '$providerId' NOT to be attempted; attempts=${attemptIds()}")
        }
    }

    /** Asserts the request completed successfully on [providerId]. */
    public fun completedWith(providerId: String) {
        if (trace.finalStatus != FinalStatus.Succeeded || trace.finalProvider != providerId) {
            fail("expected success on '$providerId'; finalStatus=${trace.finalStatus} finalProvider=${trace.finalProvider}")
        }
    }

    /** Asserts [providerId] failed with [category]. */
    public fun failed(providerId: String, category: ErrorCategory) {
        if (trace.attempts.none { it.providerId == providerId && it.outcome == AttemptOutcome.Failed && it.errorCategory == category }) {
            fail("expected '$providerId' to fail with $category; attempts=${trace.attempts}")
        }
    }

    /** Asserts routing fell back to [providerId] for [reason]. */
    public fun fellBackTo(providerId: String, reason: FallbackReason) {
        if (reason !in trace.fallbackReasons) {
            fail("expected fallback reason $reason; reasons=${trace.fallbackReasons}")
        }
        attempted(providerId)
    }

    /** Asserts [providerId] was rejected (never invoked) for [reason]. */
    public fun rejected(providerId: String, reason: FallbackReason) {
        if (trace.rejectedProviders.none { it.providerId == providerId && it.reason == reason }) {
            fail("expected '$providerId' rejected for $reason; rejected=${trace.rejectedProviders}")
        }
        didNotAttempt(providerId)
    }

    private fun attemptIds(): List<String> = trace.attempts.map { it.providerId }
    private fun fail(message: String): Nothing = throw AssertionError("Route assertion failed: $message")
}

/** Runs [block] of [RouteAssertions] against [trace]; fails if the trace is null. */
public fun assertRoute(trace: RouteTrace?, block: RouteAssertions.() -> Unit) {
    if (trace == null) throw AssertionError("Route assertion failed: expected a RouteTrace but it was null")
    RouteAssertions(trace).apply(block)
}
