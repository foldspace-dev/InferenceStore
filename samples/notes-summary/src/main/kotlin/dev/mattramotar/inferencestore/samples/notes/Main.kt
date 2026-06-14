package dev.mattramotar.inferencestore.samples.notes

import dev.mattramotar.inferencestore.core.InferenceStore
import dev.mattramotar.inferencestore.core.event.RouteTrace
import dev.mattramotar.inferencestore.core.policy.Policies
import dev.mattramotar.inferencestore.core.policy.PrivacyPolicy
import dev.mattramotar.inferencestore.core.provider.ProviderAvailability
import dev.mattramotar.inferencestore.core.provider.ProviderKind
import dev.mattramotar.inferencestore.core.provider.ProviderPrivacyBoundary
import dev.mattramotar.inferencestore.core.provider.UnavailableReason
import dev.mattramotar.inferencestore.testkit.fakeProvider
import kotlinx.coroutines.runBlocking

private const val NOTE: String =
    "Met with Priya about the Q3 roadmap. Action items: ship caching, draft the structured-output RFC, demo to leadership Friday."

private val cloudBoundary: ProviderPrivacyBoundary = ProviderPrivacyBoundary.thirdPartyCloud("demo-cloud")

public fun main(): Unit = runBlocking {
    println("=== InferenceStore — private note summarization ===")
    println("Note: $NOTE")

    localFirst()
    cloudFallback()
    privateLocalOnly()
    liteRtLmPath()
    openAiCompatible()
}

/** Local model is available → it serves the request; cloud is never needed. */
private suspend fun localFirst() {
    val onDevice = fakeProvider("on-device", ProviderKind.Local) {
        complete("Local summary: ship caching, draft the RFC, demo Friday.")
    }
    val cloud = fakeProvider("cloud", ProviderKind.Cloud, cloudBoundary) { complete("(cloud summary)") }
    val store = InferenceStore.build {
        provider(onDevice)
        provider(cloud)
        policy = Policies.preferLocalThenCloud()
    }
    val result = NoteSummarizer(store).summarize(NOTE, PrivacyPolicy.publicData())
    section("Local-first (local available)")
    println("Summary: ${result.output}")
    printTrace(result.trace)
}

/** Local model is unavailable → routing falls back to the cloud provider. */
private suspend fun cloudFallback() {
    val onDevice = fakeProvider("on-device", ProviderKind.Local) {
        availability = ProviderAvailability.Unavailable(UnavailableReason.ModelMissing) // e.g. model not downloaded
    }
    val cloud = fakeProvider("cloud", ProviderKind.Cloud, cloudBoundary) {
        complete("Cloud summary: roadmap on track, demo Friday.")
    }
    val store = InferenceStore.build {
        provider(onDevice)
        provider(cloud)
        policy = Policies.preferLocalThenCloud()
    }
    val result = NoteSummarizer(store).summarize(NOTE, PrivacyPolicy.publicData())
    section("Local unavailable -> cloud fallback")
    println("Summary: ${result.output}")
    printTrace(result.trace)
}

/** Default privacy (Personal, cloud denied): the gate refuses the cloud provider entirely. */
private suspend fun privateLocalOnly() {
    val onDevice = fakeProvider("on-device", ProviderKind.Local) {
        complete("Local summary: ship caching, draft the RFC, demo Friday.")
    }
    val cloud = fakeProvider("cloud", ProviderKind.Cloud, cloudBoundary) { complete("(should never run)") }
    val store = InferenceStore.build {
        provider(onDevice)
        provider(cloud)
        policy = Policies.preferLocalThenCloud()
    }
    val result = NoteSummarizer(store).summarize(NOTE, PrivacyPolicy.Default)
    section("Private (local-only): cloud refused by the privacy gate")
    println("Summary: ${result.output}")
    println("Cloud invocations: ${cloud.invocations}  (privacy guarantees zero)")
    printTrace(result.trace)
}

/** On-device LiteRT-LM path; runs against the bundled demo runtime when a model path is supplied. */
private suspend fun liteRtLmPath() {
    section("LiteRT-LM on-device path")
    val modelPath = System.getenv("INFERENCESTORE_LITERTLM_MODEL")
    if (modelPath.isNullOrBlank()) {
        println("Set INFERENCESTORE_LITERTLM_MODEL=/path/to/model to run this path (uses the bundled demo runtime).")
        return
    }
    val result = NoteSummarizer(InferenceStore.single(liteRtLmDemoProvider(modelPath))).summarize(NOTE)
    println("Model path: $modelPath")
    println("Summary: ${result.output}")
    printTrace(result.trace)
}

/** OpenAI-compatible cloud adapter, driven by a mock HTTP engine so it runs offline. */
private suspend fun openAiCompatible() {
    section("OpenAI-compatible cloud adapter (mock HTTP engine)")
    val result = NoteSummarizer(InferenceStore.single(openAiCompatibleDemoProvider()))
        .summarize(NOTE, PrivacyPolicy.publicData())
    println("Summary: ${result.output}")
    printTrace(result.trace)
}

private fun section(title: String) = println("\n--- $title ---")

private fun printTrace(trace: RouteTrace?) {
    if (trace == null) {
        println("route: (no trace)")
        return
    }
    println("route: final=${trace.finalProvider} status=${trace.finalStatus}")
    trace.attempts.forEach { attempt ->
        val error = attempt.errorCategory?.let { " [$it]" } ?: ""
        println("  attempt ${attempt.providerId} (${attempt.providerKind}) -> ${attempt.outcome}$error")
    }
    trace.rejectedProviders.forEach { rejected -> println("  rejected ${rejected.providerId} -> ${rejected.reason}") }
    if (trace.fallbackReasons.isNotEmpty()) println("  fallbacks: ${trace.fallbackReasons.joinToString()}")
}
