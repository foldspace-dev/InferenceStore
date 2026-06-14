package dev.mattramotar.inferencestore.core.lifecycle

import dev.mattramotar.inferencestore.core.provider.InferenceContext
import dev.mattramotar.inferencestore.core.provider.InferenceProvider

/**
 * Optional model-lifecycle hooks an [InferenceProvider] may implement (RFC-0005).
 *
 * Support is opt-in: a provider that does not implement this interface simply has
 * no lifecycle and background lifecycle workers skip it. Local runtimes with high
 * first-token latency are the primary motivation — warming the model before a
 * request arrives moves that cost out of the foreground path.
 */
public interface ProviderLifecycle {
    /**
     * Prepare [modelId] (or the provider's default model when `null`) so the next
     * foreground request pays less first-token latency. Suspends until warm; a
     * background worker bounds it via [context]'s timeout where the adapter honors it.
     *
     * Implementations should be idempotent — warming an already-warm model is a
     * no-op — and must throw on failure so the worker can record it. Never called
     * on the foreground inference path.
     */
    public suspend fun warmup(modelId: String?, context: InferenceContext)
}
