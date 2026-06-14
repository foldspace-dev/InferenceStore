package dev.mattramotar.inferencestore.monitor.opentelemetry

import dev.mattramotar.inferencestore.core.InferenceStore
import dev.mattramotar.inferencestore.core.event.InferenceEvent
import dev.mattramotar.inferencestore.core.model.InferenceKey
import dev.mattramotar.inferencestore.core.model.InferenceRequest
import dev.mattramotar.inferencestore.core.policy.Policies
import dev.mattramotar.inferencestore.core.provider.ErrorCategory
import dev.mattramotar.inferencestore.core.provider.ProviderKind
import dev.mattramotar.inferencestore.testkit.fakeProvider
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.`data`.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpenTelemetryMonitorTest {

    private val key = InferenceKey("notes.summary", "n1")
    private val exporter = InMemorySpanExporter.create()
    private val monitor = OpenTelemetryMonitor(
        SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build().get("inferencestore"),
    )

    private fun span(): SpanData = exporter.finishedSpanItems.single()

    @Test
    fun successfulRequest_exportsOneSpanWithAttributesAndEvents() = runTest {
        val local = fakeProvider("local", ProviderKind.Local) { tokens("Hel", "lo"); complete("Hello") }
        InferenceStore.build { provider(local); monitor(monitor) }.generate(InferenceRequest.text(key, "hi"))

        val span = span()
        assertEquals(OpenTelemetryMonitor.SPAN_NAME, span.name)
        assertEquals("local", span.attributes.get(OpenTelemetryMonitor.FINAL_PROVIDER))
        assertEquals("Succeeded", span.attributes.get(OpenTelemetryMonitor.FINAL_STATUS))
        assertEquals(2L, span.attributes.get(OpenTelemetryMonitor.TOKEN_COUNT))
        val events = span.events.map { it.name }
        assertTrue("provider.attempt.started" in events)
        assertTrue("provider.attempt.completed" in events)
    }

    @Test
    fun fallback_recordsFallbackEvent_andFinalProvider() = runTest {
        val a = fakeProvider("a", ProviderKind.Local) { fail(ErrorCategory.TransientProviderError) }
        val b = fakeProvider("b", ProviderKind.Local) { complete("ok") }
        InferenceStore.build {
            provider(a); provider(b)
            policy = Policies.preferLocalThenCloud()
            monitor(monitor)
        }.generate(InferenceRequest.text(key, "hi"))

        val span = span()
        assertTrue(span.events.any { it.name == "fallback.started" })
        assertEquals("b", span.attributes.get(OpenTelemetryMonitor.FINAL_PROVIDER))
    }

    @Test
    fun failedRequest_marksSpanError_withCategory() = runTest {
        val p = fakeProvider("p", ProviderKind.Local) { fail(ErrorCategory.RateLimited) }
        val last = InferenceStore.build { provider(p); monitor(monitor) }
            .stream(InferenceRequest.text(key, "hi")).toList().last()
        assertTrue(last is InferenceEvent.Failed)

        val span = span()
        assertEquals(StatusCode.ERROR, span.status.statusCode)
        assertEquals("RateLimited", span.attributes.get(OpenTelemetryMonitor.ERROR_CATEGORY))
    }

    @Test
    fun export_carriesNoPromptOrOutputContent() = runTest {
        val p = fakeProvider("p", ProviderKind.Local) { tokens("secret"); complete("secret-output") }
        InferenceStore.build { provider(p); monitor(monitor) }.generate(InferenceRequest.text(key, "secret-prompt"))

        val span = span()
        // Collect every exported string value (span + event attributes) and assert no content leaked.
        val values = buildList {
            span.attributes.forEach { _, v -> add(v.toString()) }
            span.events.forEach { e -> e.attributes.forEach { _, v -> add(v.toString()) } }
        }
        assertTrue(values.none { it.contains("secret-prompt") || it.contains("secret-output") || it == "secret" }, "exported: $values")
    }
}
