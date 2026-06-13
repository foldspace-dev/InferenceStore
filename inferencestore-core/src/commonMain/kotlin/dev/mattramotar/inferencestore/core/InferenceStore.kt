package dev.mattramotar.inferencestore.core

import dev.mattramotar.inferencestore.core.event.AttemptOutcome
import dev.mattramotar.inferencestore.core.event.InferenceError
import dev.mattramotar.inferencestore.core.event.InferenceEvent
import dev.mattramotar.inferencestore.core.event.InferenceResult
import dev.mattramotar.inferencestore.core.event.ProviderAttemptSummary
import dev.mattramotar.inferencestore.core.event.RequestId
import dev.mattramotar.inferencestore.core.model.InferenceRequest
import dev.mattramotar.inferencestore.core.provider.InferenceContext
import dev.mattramotar.inferencestore.core.provider.InferenceProvider
import dev.mattramotar.inferencestore.core.provider.ProviderEvent
import dev.mattramotar.inferencestore.core.provider.toProviderRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * The streaming-first entry point.
 *
 * `stream()` returns a COLD flow — no provider work happens until collection
 * begins, which is the basis of the main-safety contract: a UI scope can
 * collect without blocking, and blocking provider work is moved off the
 * collector via [InferenceExecutionConfig.providerContext]. Full dispatcher and
 * dedupe fan-out semantics land in OSS-14. Caller cancellation is terminal and
 * never triggers fallback.
 *
 * This slice (OSS-10) routes to a single provider; policy-driven routing,
 * fallback, validation, privacy enforcement, cache, and the full event/trace
 * model are layered on in OSS-13 / OSS-8 / OSS-15 / OSS-25 / OSS-11.
 */
public interface InferenceStore {
    public fun <Output : Any> stream(request: InferenceRequest<Output>): Flow<InferenceEvent<Output>>

    public suspend fun <Output : Any> generate(request: InferenceRequest<Output>): InferenceResult<Output>

    public companion object {
        public const val VERSION: String = "0.1.0-dev"

        /** A single-provider store. Routing across providers arrives with OSS-13. */
        public fun single(
            provider: InferenceProvider,
            config: InferenceExecutionConfig = InferenceExecutionConfig(),
        ): InferenceStore = SingleProviderInferenceStore(provider, config)
    }
}

/**
 * Execution contexts for the engine. Minimal for now: [providerContext] moves
 * provider work off the collecting thread. Planning/blocking/monitor contexts
 * and dedupe fan-out are added in OSS-14 per `threading-dispatchers.md`.
 */
public class InferenceExecutionConfig(
    public val providerContext: CoroutineContext = EmptyCoroutineContext,
)

/** Thrown by [InferenceStore.generate] when a request terminates in failure. */
public class InferenceException(public val error: InferenceError) :
    RuntimeException(error.message ?: error.category.name, error.cause)

internal class SingleProviderInferenceStore(
    private val provider: InferenceProvider,
    private val config: InferenceExecutionConfig,
) : InferenceStore {

    override fun <Output : Any> stream(request: InferenceRequest<Output>): Flow<InferenceEvent<Output>> {
        val providerRequest = request.toProviderRequest()
        val context = InferenceContext(timeout = request.timeout)
        return flow {
            val requestId = RequestId(request.key.asString())
            emit(InferenceEvent.Started(requestId, request.key))
            emit(
                InferenceEvent.ProviderAttemptStarted(
                    requestId,
                    ProviderAttemptSummary(provider.id, attemptNumber = 1),
                ),
            )
            provider.stream(providerRequest, context).collect { event ->
                when (event) {
                    is ProviderEvent.Started -> Unit
                    is ProviderEvent.Token -> emit(InferenceEvent.Token(requestId, event.text))
                    is ProviderEvent.Partial -> emit(InferenceEvent.Partial(requestId, event.value))
                    is ProviderEvent.Completed -> {
                        emit(
                            InferenceEvent.ProviderAttemptCompleted(
                                requestId,
                                ProviderAttemptSummary(provider.id, 1, AttemptOutcome.Succeeded),
                            ),
                        )
                        emit(
                            InferenceEvent.Done(
                                requestId,
                                InferenceResult(request.key, event.output, event.rawText),
                            ),
                        )
                    }
                    is ProviderEvent.Failed -> {
                        emit(
                            InferenceEvent.ProviderAttemptCompleted(
                                requestId,
                                ProviderAttemptSummary(provider.id, 1, AttemptOutcome.Failed, event.error.category),
                            ),
                        )
                        emit(
                            InferenceEvent.Failed(
                                requestId,
                                InferenceError(event.error.category, event.error.message, event.error.cause),
                            ),
                        )
                    }
                }
            }
        }.flowOn(config.providerContext)
    }

    override suspend fun <Output : Any> generate(request: InferenceRequest<Output>): InferenceResult<Output> {
        val terminal = stream(request).first { it is InferenceEvent.Done<*> || it is InferenceEvent.Failed }
        return when (terminal) {
            is InferenceEvent.Done<*> -> {
                @Suppress("UNCHECKED_CAST")
                (terminal as InferenceEvent.Done<Output>).result
            }
            is InferenceEvent.Failed -> throw InferenceException(terminal.error)
            else -> error("unreachable terminal event: $terminal")
        }
    }
}
