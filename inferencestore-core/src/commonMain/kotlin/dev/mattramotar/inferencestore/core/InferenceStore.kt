package dev.mattramotar.inferencestore.core

import dev.mattramotar.inferencestore.core.event.AttemptOutcome
import dev.mattramotar.inferencestore.core.event.FallbackReason
import dev.mattramotar.inferencestore.core.event.FinalStatus
import dev.mattramotar.inferencestore.core.event.InferenceError
import dev.mattramotar.inferencestore.core.event.InferenceEvent
import dev.mattramotar.inferencestore.core.event.InferenceResult
import dev.mattramotar.inferencestore.core.event.ProviderAttemptSummary
import dev.mattramotar.inferencestore.core.event.ProviderAttemptTrace
import dev.mattramotar.inferencestore.core.event.RejectedProviderTrace
import dev.mattramotar.inferencestore.core.event.RequestId
import dev.mattramotar.inferencestore.core.event.RouteTrace
import dev.mattramotar.inferencestore.core.model.InferenceRequest
import dev.mattramotar.inferencestore.core.policy.InferencePolicy
import dev.mattramotar.inferencestore.core.policy.InferenceRoute
import dev.mattramotar.inferencestore.core.policy.Policies
import dev.mattramotar.inferencestore.core.policy.ProviderCandidate
import dev.mattramotar.inferencestore.core.provider.ErrorCategory
import dev.mattramotar.inferencestore.core.provider.InferenceContext
import dev.mattramotar.inferencestore.core.provider.InferenceProvider
import dev.mattramotar.inferencestore.core.provider.ProviderAvailability
import dev.mattramotar.inferencestore.core.provider.ProviderEvent
import dev.mattramotar.inferencestore.core.provider.toProviderRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.transform
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * The streaming-first entry point.
 *
 * `stream()` returns a COLD flow — no provider work happens until collection
 * begins, which is the basis of the main-safety contract: a UI scope can collect
 * without blocking, and blocking provider work is moved off the collector via
 * [InferenceExecutionConfig.providerContext]. Caller cancellation is terminal and
 * never triggers fallback.
 *
 * Routing across multiple providers is policy-driven (OSS-13): the engine probes
 * candidate availability/capability, the [InferencePolicy] orders them, and the
 * engine executes the route with fallback. Privacy enforcement, validation, cache,
 * and dedupe are layered on in OSS-15 / OSS-8 / OSS-25 / OSS-11.
 */
public interface InferenceStore {
    public fun <Output : Any> stream(request: InferenceRequest<Output>): Flow<InferenceEvent<Output>>

    public suspend fun <Output : Any> generate(request: InferenceRequest<Output>): InferenceResult<Output>

    public companion object {
        public const val VERSION: String = "0.1.0-dev"

        /** Build a routed store from a set of providers and a policy. */
        public fun build(block: InferenceStoreBuilder.() -> Unit): InferenceStore =
            InferenceStoreBuilder().apply(block).build()

        /** A single-provider store: the one provider is always the route (no fallback). */
        public fun single(
            provider: InferenceProvider,
            config: InferenceExecutionConfig = InferenceExecutionConfig(),
        ): InferenceStore = RoutedInferenceStore(
            providers = listOf(provider),
            policy = InferencePolicy { candidates -> InferenceRoute("single", candidates.map { it.provider }) },
            config = config,
        )
    }
}

/** DSL for [InferenceStore.build]. */
public class InferenceStoreBuilder {
    private val providers: MutableList<InferenceProvider> = mutableListOf()

    /** Default routing policy; a request may override it via `InferenceRequest.policy`. */
    public var policy: InferencePolicy = Policies.preferLocalThenCloud()
    public var executionConfig: InferenceExecutionConfig = InferenceExecutionConfig()

    public fun provider(provider: InferenceProvider) {
        providers += provider
    }

    internal fun build(): InferenceStore = RoutedInferenceStore(providers.toList(), policy, executionConfig)
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

private sealed interface AttemptResult<out Output : Any> {
    class NoTerminal<Output : Any> : AttemptResult<Output>
    class Success<Output : Any>(val output: Output, val rawText: String?) : AttemptResult<Output>
    class Failure(val category: ErrorCategory) : AttemptResult<Nothing>
}

internal class RoutedInferenceStore(
    private val providers: List<InferenceProvider>,
    private val policy: InferencePolicy,
    private val config: InferenceExecutionConfig,
) : InferenceStore {

    override fun <Output : Any> stream(request: InferenceRequest<Output>): Flow<InferenceEvent<Output>> =
        flow {
            val requestId = RequestId(request.key.asString())
            val key = request.key.asString()
            val context = InferenceContext(timeout = request.timeout)
            val providerRequest = request.toProviderRequest()
            emit(InferenceEvent.Started(requestId, request.key))

            val candidates = providers.map { provider ->
                ProviderCandidate(
                    provider = provider,
                    available = provider.availability(context) == ProviderAvailability.Available,
                    supported = provider.capabilities(request, context).supported,
                )
            }
            val route = (request.policy ?: policy).selectRoute(candidates)

            val attempts = mutableListOf<ProviderAttemptTrace>()
            val fallbackReasons = mutableListOf<FallbackReason>()
            // Providers the policy left out of the route, with why they were dropped.
            val routedIds = route.orderedProviders.mapTo(mutableSetOf()) { it.id }
            val rejected = candidates.filterNot { it.provider.id in routedIds }.map { candidate ->
                val reason = when {
                    !candidate.available -> FallbackReason.ProviderUnavailable
                    !candidate.supported -> FallbackReason.CapabilityUnsupported
                    else -> FallbackReason.PolicyViolation
                }
                RejectedProviderTrace(candidate.provider.id.value, reason)
            }

            if (route.orderedProviders.isEmpty()) {
                emit(
                    InferenceEvent.Failed(
                        requestId,
                        InferenceError(ErrorCategory.ProviderUnavailable, "no provider satisfied policy '${route.policyId}'"),
                        trace = RouteTrace(
                            requestId = requestId.value,
                            key = key,
                            finalStatus = FinalStatus.Failed,
                            policyId = route.policyId,
                            rejectedProviders = rejected,
                        ),
                    ),
                )
                return@flow
            }

            val lastIndex = route.orderedProviders.lastIndex
            for ((index, provider) in route.orderedProviders.withIndex()) {
                val attemptNumber = index + 1
                emit(InferenceEvent.ProviderAttemptStarted(requestId, ProviderAttemptSummary(provider.id, attemptNumber)))

                var modelId: String? = null
                var result: AttemptResult<Output> = AttemptResult.NoTerminal()
                emitAll(
                    provider.stream(providerRequest, context)
                        .transform<ProviderEvent<Output>, InferenceEvent<Output>> { event ->
                            when (event) {
                                is ProviderEvent.Started -> modelId = event.metadata.modelId
                                is ProviderEvent.Token -> emit(InferenceEvent.Token(requestId, event.text))
                                is ProviderEvent.Partial -> emit(InferenceEvent.Partial(requestId, event.value))
                                is ProviderEvent.Completed -> {
                                    modelId = event.metadata.modelId ?: modelId
                                    result = AttemptResult.Success(event.output, event.rawText)
                                }
                                is ProviderEvent.Failed -> result = AttemptResult.Failure(event.error.category)
                            }
                        }
                        .catch { throwable ->
                            // Cancellation is terminal and must propagate untouched.
                            if (throwable is CancellationException) throw throwable
                            // A provider that throws is mapped defensively to Unknown.
                            result = AttemptResult.Failure(ErrorCategory.Unknown)
                        },
                )

                val success = result as? AttemptResult.Success
                if (success != null) {
                    attempts += ProviderAttemptTrace(provider.id.value, provider.kind, AttemptOutcome.Succeeded, modelId = modelId)
                    emit(
                        InferenceEvent.ProviderAttemptCompleted(
                            requestId,
                            ProviderAttemptSummary(provider.id, attemptNumber, AttemptOutcome.Succeeded),
                        ),
                    )
                    emit(
                        InferenceEvent.Done(
                            requestId,
                            InferenceResult(
                                request.key,
                                success.output,
                                success.rawText,
                                trace = RouteTrace(
                                    requestId = requestId.value,
                                    key = key,
                                    finalStatus = FinalStatus.Succeeded,
                                    policyId = route.policyId,
                                    attempts = attempts.toList(),
                                    rejectedProviders = rejected,
                                    fallbackReasons = fallbackReasons.toList(),
                                    finalProvider = provider.id.value,
                                ),
                            ),
                        ),
                    )
                    return@flow
                }

                val category = (result as? AttemptResult.Failure)?.category ?: ErrorCategory.TransientProviderError
                attempts += ProviderAttemptTrace(provider.id.value, provider.kind, AttemptOutcome.Failed, modelId = modelId, errorCategory = category)
                emit(
                    InferenceEvent.ProviderAttemptCompleted(
                        requestId,
                        ProviderAttemptSummary(provider.id, attemptNumber, AttemptOutcome.Failed, category),
                    ),
                )

                if (index < lastIndex && isFallbackEligible(category)) {
                    val reason = toFallbackReason(category)
                    fallbackReasons += reason
                    emit(InferenceEvent.FallbackStarted(requestId, reason, route.orderedProviders[index + 1].id))
                } else {
                    emit(
                        InferenceEvent.Failed(
                            requestId,
                            InferenceError(category),
                            trace = RouteTrace(
                                requestId = requestId.value,
                                key = key,
                                finalStatus = FinalStatus.Failed,
                                policyId = route.policyId,
                                attempts = attempts.toList(),
                                rejectedProviders = rejected,
                                fallbackReasons = fallbackReasons.toList(),
                            ),
                        ),
                    )
                    return@flow
                }
            }
        }.flowOn(config.providerContext)

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

/** Whether a provider failure of [category] should trigger fallback (OSS-16 owns the canonical table). */
private fun isFallbackEligible(category: ErrorCategory): Boolean = when (category) {
    ErrorCategory.ProviderUnavailable,
    ErrorCategory.CapabilityUnsupported,
    ErrorCategory.Timeout,
    ErrorCategory.RateLimited,
    ErrorCategory.TransientProviderError,
    ErrorCategory.ValidationFailed,
    ErrorCategory.ParsingFailed,
    -> true
    ErrorCategory.PermanentProviderError,
    ErrorCategory.PolicyViolation,
    ErrorCategory.Cancelled,
    ErrorCategory.Unknown,
    -> false
}

private fun toFallbackReason(category: ErrorCategory): FallbackReason = when (category) {
    ErrorCategory.ProviderUnavailable -> FallbackReason.ProviderUnavailable
    ErrorCategory.CapabilityUnsupported -> FallbackReason.CapabilityUnsupported
    ErrorCategory.Timeout -> FallbackReason.Timeout
    ErrorCategory.RateLimited -> FallbackReason.RateLimited
    ErrorCategory.TransientProviderError -> FallbackReason.TransientError
    ErrorCategory.PermanentProviderError -> FallbackReason.PermanentError
    ErrorCategory.ValidationFailed -> FallbackReason.ValidatorRejected
    ErrorCategory.ParsingFailed -> FallbackReason.OutputParserFailed
    ErrorCategory.PolicyViolation -> FallbackReason.PolicyViolation
    ErrorCategory.Cancelled, ErrorCategory.Unknown -> FallbackReason.Unknown
}
