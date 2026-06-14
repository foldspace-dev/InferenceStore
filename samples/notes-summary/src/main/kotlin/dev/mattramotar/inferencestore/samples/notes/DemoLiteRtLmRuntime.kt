package dev.mattramotar.inferencestore.samples.notes

import dev.mattramotar.inferencestore.provider.litertlm.LiteRtLmBackend
import dev.mattramotar.inferencestore.provider.litertlm.LiteRtLmFailure
import dev.mattramotar.inferencestore.provider.litertlm.LiteRtLmRuntime
import dev.mattramotar.inferencestore.provider.litertlm.LiteRtLmStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * A stand-in [LiteRtLmRuntime] so the sample can exercise the LiteRT-LM provider
 * path offline, without Google's native AI Edge runtime. A real integrator replaces
 * this single class with the native binding — the adapter and routing code are
 * identical either way. It "succeeds" when a model path is supplied and streams a
 * canned on-device summary.
 */
public class DemoLiteRtLmRuntime : LiteRtLmRuntime {

    override suspend fun probe(modelPath: String, backend: LiteRtLmBackend): LiteRtLmStatus =
        if (modelPath.isNotBlank()) LiteRtLmStatus.Ready else LiteRtLmStatus.Unavailable(LiteRtLmFailure.MissingModel)

    override fun generate(modelPath: String, backend: LiteRtLmBackend, prompt: String): Flow<String> = flow {
        // A real runtime streams tokens from the on-device model; this simulates them.
        SIMULATED_SUMMARY.split(" ").forEach { word -> emit("$word ") }
    }

    private companion object {
        const val SIMULATED_SUMMARY =
            "On-device summary (demo runtime): roadmap items captured locally, nothing left the device."
    }
}
