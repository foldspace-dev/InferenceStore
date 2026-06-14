package dev.mattramotar.inferencestore.samples.crossplatform

import dev.mattramotar.inferencestore.core.InferenceStore
import dev.mattramotar.inferencestore.core.provider.ProviderKind
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Shared cross-platform request/policy + platform-provider wiring (OSS-37). */
class CrossPlatformSummarizerTest {

    @Test
    fun sharedRequest_isTextWithValidator() {
        val request = CrossPlatformSummarizer.request("hello")
        assertEquals(CrossPlatformSummarizer.key, request.key)
        assertNotNull(request.validator)
    }

    @Test
    fun demoProvider_streamsThroughStore_andTraces() = runTest {
        val store = InferenceStore.build {
            provider(DemoTextProvider("test-local", ProviderKind.Local, "a one-line summary"))
            policy = CrossPlatformSummarizer.policy()
        }
        val result = store.generate(CrossPlatformSummarizer.request("a long note"))
        assertEquals("a one-line summary", result.output)
        assertEquals("test-local", result.trace?.finalProvider)
    }

    @Test
    fun summarize_usesPlatformProvider_andRecordsItInTrace() = runTest {
        // Runs the current platform's actual provider; the trace records which one.
        val result = CrossPlatformSummarizer.summarize("a long note")
        assertTrue(result.output.isNotBlank())
        assertNotNull(result.trace?.finalProvider)
    }
}
