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
import dev.mattramotar.inferencestore.core.dedupe.DedupeCoordinator
import dev.mattramotar.inferencestore.core.model.InferenceInput
import dev.mattramotar.inferencestore.core.model.InferenceRequest
import dev.mattramotar.inferencestore.core.policy.FallbackMapping
import dev.mattramotar.inferencestore.core.policy.delayFor
import dev.mattramotar.inferencestore.core.policy.InferencePolicy
import dev.mattramotar.inferencestore.core.policy.InferenceRoute
import dev.mattramotar.inferencestore.core.policy.Policies
import dev.mattramotar.inferencestore.core.policy.PolicyViolation
import dev.mattramotar.inferencestore.core.policy.PrivacyDecision
import dev.mattramotar.inferencestore.core.policy.ProviderCandidate
import dev.mattramotar.inferencestore.core.policy.allowsProvider
import dev.mattramotar.inferencestore.core.provider.ErrorCategory
import dev.mattramotar.inferencestore.core.provider.ErrorSource
import dev.mattramotar.inferencestore.core.provider.InferenceContext
import dev.mattramotar.inferencestore.core.provider.InferenceProvider
import dev.mattramotar.inferencestore.core.provider.ProviderAvailability
import dev.mattramotar.inferencestore.core.provider.ProviderId
import dev.mattramotar.inferencestore.core.provider.ProviderEvent
import dev.mattramotar.inferencestore.core.provider.toProviderRequest
import dev.mattramotar.inferencestore.core.validation.ValidationResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.TimeSource

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
 * Execution contexts for the engine (`threading-dispatchers.md`). Dispatcher-
 * neutral: core does not assume platform UI dispatchers exist. [providerContext]
 * moves provider work off the collecting thread; [planningContext] is for route
 * planning; [blockingProviderContext] is for adapters' blocking init/native work;
 * [monitorContext] is for monitor emission (OSS-19). The dedupe coordinator and
 * its in-flight groups run on [providerContext].
 */
public class InferenceExecutionConfig(
    public val providerContext: CoroutineContext = EmptyCoroutineContext,
    public val planningContext: CoroutineContext = EmptyCoroutineContext,
    public val blockingProviderContext: CoroutineContext = EmptyCoroutineContext,
    public val monitorContext: CoroutineContext = EmptyCoroutineContext,
    /**
     * Clock for request-deadline budget accounting. Defaults to the monotonic
     * system clock; tests pass `TestScope.testTimeSource` so deadlines respect the
     * virtual clock.
     */
    public val timeSource: TimeSource = TimeSource.Monotonic,
)

/** Thrown by [InferenceStore.generate] when a request terminates in failure. */
public class InferenceException(public val error: InferenceError) :
    RuntimeException(error.message ?: error.category.name, error.cause)

private sealed interface AttemptResult<out Output : Any> {
    class NoTerminal<Output : Any> : AttemptResult<Output>
    class Success<Output : Any>(val output: Output, val rawText: String?) : AttemptResult<Output>
    class Failure(
        val category: ErrorCategory,
        val source: ErrorSource? = null,
        val message: String? = null,
        val cause: Throwable? = null,
        val retryAfter: Duration? = null,
    ) : AttemptResult<Nothing>
}

internal class RoutedInferenceStore(
    private val providers: List<InferenceProvider>,
    private val policy: InferencePolicy,
    private val config: InferenceExecutionConfig,
) : InferenceStore {

    init {
        // ProviderId is the key the routing and trace logic dedupes on; duplicates
        // would corrupt candidate/route/rejected bookkeeping, so reject them early.
        val duplicates = providers.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
        require(duplicates.isEmpty()) {
            "duplicate provider id(s): ${duplicates.map { it.value }}"
        }
    }

    // Long-lived scope for in-flight dedupe groups: it must outlive any single
    // collector so ref-counted sharing/cleanup works. Groups self-cancel when
    // their last subscriber leaves (SharingStarted.WhileSubscribed).
    private val dedupeScope = CoroutineScope(SupervisorJob() + config.providerContext)
    private val dedupe = DedupeCoordinator(dedupeScope)

    override fun <Output : Any> stream(request: InferenceRequest<Output>): Flow<InferenceEvent<Output>> {
        val route = routeStream(request)
        return if (request.cache.allowDedupe) dedupe.deduped(dedupeKey(request), route) else route
    }

    private fun <Output : Any> routeStream(request: InferenceRequest<Output>): Flow<InferenceEvent<Output>> =
        flow {
            val requestId = RequestId(request.key.asString())
            val key = request.key.asString()
            val context = InferenceContext(timeout = request.timeout)
            val providerRequest = request.toProviderRequest()
            emit(InferenceEvent.Started(requestId, request.key))

            // Start the request-deadline clock here so it covers the FULL request
            // (probes + attempts + retries + fallback), per timeout-retry-policy.md.
            val deadlineMark = config.timeSource.markNow()
            val requestTimeout = request.timeout.requestTimeout
            val attemptTimeout = request.timeout.attemptTimeout
            fun remainingBudget(): Duration? = requestTimeout?.let { it - deadlineMark.elapsedNow() }
            fun deadlineExceeded(): Boolean = remainingBudget()?.let { it <= Duration.ZERO } == true

            // Privacy gate (privacy-model.md): evaluated BEFORE any provider work.
            // A denial is terminal for that provider and routing policy cannot
            // override it, so privacy-denied providers are never probed nor routed.
            val privacyDenials = mutableMapOf<ProviderId, PolicyViolation>()
            val candidates = providers.map { provider ->
                when (val decision = request.privacy.allowsProvider(provider.id, provider.boundary)) {
                    is PrivacyDecision.Deny -> {
                        privacyDenials[provider.id] = decision.violation
                        ProviderCandidate(provider, available = false, supported = false)
                    }
                    // A provider that throws while being probed is treated as unavailable
                    // so the policy can route around it instead of crashing the request.
                    PrivacyDecision.Allow -> {
                        // Each probe is bounded by the smaller of the availability timeout
                        // and the remaining request budget.
                        val probeBudget = minDuration(request.timeout.availabilityTimeout, remainingBudget())
                        val available = probe(probeBudget) {
                            provider.availability(context) == ProviderAvailability.Available
                        }
                        val supported = available && probe(probeBudget) {
                            provider.capabilities(request, context).supported
                        }
                        ProviderCandidate(provider, available, supported)
                    }
                }
            }
            // The policy may only choose among routable candidates: probed available +
            // supported and privacy-allowed.
            val routableCandidates = candidates.filter {
                it.available && it.supported && it.provider.id !in privacyDenials
            }
            val routableIds = routableCandidates.mapTo(mutableSetOf()) { it.provider.id }
            val selected = (request.policy ?: policy).selectRoute(routableCandidates)
            val policyId = selected.policyId
            // Backstop: the route is authoritative only over routable providers, so
            // neither a custom policy nor a probe gap can route to an unvetted,
            // unavailable, unsupported, or privacy-denied provider.
            val route = selected.orderedProviders.filter { it.id in routableIds }

            val attempts = mutableListOf<ProviderAttemptTrace>()
            val fallbackReasons = mutableListOf<FallbackReason>()
            // Providers the policy left out of the route, with why they were dropped.
            val routedIds = route.mapTo(mutableSetOf()) { it.id }
            val rejected = candidates.filterNot { it.provider.id in routedIds }.map { candidate ->
                val reason = when {
                    // Privacy denial is checked first: a denied provider is also
                    // marked unavailable, but the security-relevant reason is privacy.
                    candidate.provider.id in privacyDenials -> FallbackReason.PolicyViolation
                    !candidate.available -> FallbackReason.ProviderUnavailable
                    !candidate.supported -> FallbackReason.CapabilityUnsupported
                    // Available and capable, but the active policy forbids this provider
                    // (e.g. a cloud provider under localOnly). With the MVP presets this
                    // only fires for a categorical kind exclusion, so "using it would
                    // violate the policy" is accurate.
                    else -> FallbackReason.PolicyViolation
                }
                RejectedProviderTrace(candidate.provider.id.value, reason)
            }

            if (route.isEmpty()) {
                // PrivacyDenied only when privacy blocked every provider; a mix where
                // a privacy-allowed provider was merely unavailable is a plain failure.
                val privacyBlocked = providers.isNotEmpty() && privacyDenials.size == providers.size
                val error = if (privacyBlocked) {
                    InferenceError(ErrorCategory.PolicyViolation, "privacy policy denied all candidate providers")
                } else {
                    InferenceError(ErrorCategory.ProviderUnavailable, "no provider satisfied policy '$policyId'")
                }
                emit(
                    InferenceEvent.Failed(
                        requestId,
                        error,
                        trace = RouteTrace(
                            requestId = requestId.value,
                            key = key,
                            finalStatus = if (privacyBlocked) FinalStatus.PrivacyDenied else FinalStatus.Failed,
                            policyId = policyId,
                            rejectedProviders = rejected,
                        ),
                    ),
                )
                return@flow
            }

            // Request-deadline budget helpers (remainingBudget / deadlineExceeded) and
            // the deadline clock are defined above so they also cover the probe phase.
            fun failedTrace() = RouteTrace(
                requestId = requestId.value,
                key = key,
                finalStatus = FinalStatus.Failed,
                policyId = policyId,
                attempts = attempts.toList(),
                rejectedProviders = rejected,
                fallbackReasons = fallbackReasons.toList(),
            )
            val deadlineError = InferenceError(
                ErrorCategory.Timeout,
                message = "request deadline exceeded",
                source = ErrorSource.RequestDeadlineExceeded,
            )

            val lastIndex = route.lastIndex
            var attemptNumber = 0
            for ((index, provider) in route.withIndex()) {
                var retriesUsed = 0
                while (true) {
                    if (deadlineExceeded()) {
                        emit(InferenceEvent.Failed(requestId, deadlineError, trace = failedTrace()))
                        return@flow
                    }
                    attemptNumber++
                    emit(InferenceEvent.ProviderAttemptStarted(requestId, ProviderAttemptSummary(provider.id, attemptNumber)))

                    // Bound the attempt by the smaller of the attempt timeout and the
                    // remaining request budget; remember which one is binding so a
                    // timeout maps to the right source.
                    val budgetRemaining = remainingBudget()
                    val attemptBudget = minDuration(attemptTimeout, budgetRemaining)
                    val deadlineBinding = budgetRemaining != null &&
                        (attemptTimeout == null || budgetRemaining <= attemptTimeout)

                    var modelId: String? = null
                    var result: AttemptResult<Output> = AttemptResult.NoTerminal()
                    emitAll(
                        provider.stream(providerRequest, context)
                            .let { if (attemptBudget != null) it.attemptTimeout(attemptBudget) else it }
                            .transform<ProviderEvent<Output>, InferenceEvent<Output>> { event ->
                                when (event) {
                                    is ProviderEvent.Started -> modelId = event.metadata.modelId
                                    is ProviderEvent.Token -> emit(InferenceEvent.Token(requestId, event.text))
                                    is ProviderEvent.Partial -> emit(InferenceEvent.Partial(requestId, event.value))
                                    is ProviderEvent.Completed -> {
                                        modelId = event.metadata.modelId ?: modelId
                                        result = AttemptResult.Success(event.output, event.rawText)
                                    }
                                    is ProviderEvent.Failed ->
                                        result = AttemptResult.Failure(
                                            event.error.category,
                                            event.error.source,
                                            event.error.message,
                                            event.error.cause,
                                            event.error.retryAfter,
                                        )
                                }
                            }
                            .catch { throwable ->
                                when {
                                    // Attempt/deadline timeout — map to the binding source.
                                    throwable is TimeoutCancellationException ->
                                        result = AttemptResult.Failure(
                                            ErrorCategory.Timeout,
                                            if (deadlineBinding) ErrorSource.RequestDeadlineExceeded else ErrorSource.AttemptTimeout,
                                        )
                                    // Caller cancellation is terminal and propagates untouched.
                                    throwable is CancellationException -> throw throwable
                                    // A provider that throws is mapped defensively to Unknown.
                                    else -> result = AttemptResult.Failure(ErrorCategory.Unknown, cause = throwable)
                                }
                            },
                    )

                    // Final-output validation (OSS-17): a provider output that fails the
                    // request validator becomes a validation failure for this attempt, so
                    // the canonical fallback path decides repair (FallbackPolicy.repairEnabled).
                    val produced = result
                    val validator = request.validator
                    if (produced is AttemptResult.Success && validator != null) {
                        var validatorError: Throwable? = null
                        val verdict = try {
                            validator.validate(produced.output, produced.rawText)
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (throwable: Throwable) {
                            // A throwing validator fails the attempt defensively, like a
                            // throwing provider — it must not escape and crash the request.
                            validatorError = throwable
                            ValidationResult.Fail("validator threw an exception", ErrorCategory.Unknown)
                        }
                        emit(InferenceEvent.ValidationCompleted(requestId, verdict))
                        if (verdict is ValidationResult.Fail) {
                            result = AttemptResult.Failure(verdict.category, message = verdict.reason, cause = validatorError)
                        }
                    }

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
                                        policyId = policyId,
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

                    val failure = result as? AttemptResult.Failure
                    val category = failure?.category ?: ErrorCategory.TransientProviderError
                    val source = failure?.source
                    attempts += ProviderAttemptTrace(provider.id.value, provider.kind, AttemptOutcome.Failed, modelId = modelId, errorCategory = category)
                    emit(
                        InferenceEvent.ProviderAttemptCompleted(
                            requestId,
                            ProviderAttemptSummary(provider.id, attemptNumber, AttemptOutcome.Failed, category),
                        ),
                    )

                    // A request-deadline timeout is always terminal — no retry, no fallback.
                    if (source == ErrorSource.RequestDeadlineExceeded) {
                        emit(InferenceEvent.Failed(requestId, deadlineError, trace = failedTrace()))
                        return@flow
                    }

                    // Opt-in same-provider retry (disabled by default). The Cancelled
                    // exclusion only guards a provider that *reports* Cancelled as a
                    // failure event; real caller cancellation is rethrown in .catch above.
                    val retry = request.retry
                    val canRetry = retriesUsed < retry.maxRetriesPerAttempt &&
                        category in retry.retryableCategories &&
                        category != ErrorCategory.Cancelled
                    if (canRetry) {
                        val backoff = retry.backoff.delayFor(retriesUsed)
                        val delay = if (retry.respectRetryAfter && failure?.retryAfter != null) {
                            maxOf(backoff, failure.retryAfter)
                        } else {
                            backoff
                        }
                        val budget = remainingBudget()
                        if (budget == null || delay < budget) {
                            retriesUsed++
                            emit(InferenceEvent.RetryScheduled(requestId, provider.id, attemptNumber + 1, delay))
                            if (delay > Duration.ZERO) delay(delay)
                            continue // retry the same provider
                        }
                        // No budget for the retry delay — fall through to fallback/terminal.
                    }

                    // Fallback per the canonical mapping (OSS-16), subject to budget.
                    val mayFallBack = index < lastIndex &&
                        FallbackMapping.isFallbackAllowed(category, source, request.fallback)
                    when {
                        mayFallBack && deadlineExceeded() -> {
                            emit(InferenceEvent.Failed(requestId, deadlineError, trace = failedTrace()))
                            return@flow
                        }
                        mayFallBack -> {
                            val reason = FallbackMapping.reasonFor(category)
                            fallbackReasons += reason
                            emit(InferenceEvent.FallbackStarted(requestId, reason, route[index + 1].id))
                            break // advance to the next provider
                        }
                        else -> {
                            emit(
                                InferenceEvent.Failed(
                                    requestId,
                                    InferenceError(category, message = failure?.message, cause = failure?.cause, source = source),
                                    trace = failedTrace(),
                                ),
                            )
                            return@flow
                        }
                    }
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

    // Dedupe compatibility key. Uses input hash codes (not raw content) so the
    // in-flight registry never retains prompts. Full fingerprinting (prompt/output
    // versions, policy version) arrives with OSS-20.
    private fun dedupeKey(request: InferenceRequest<*>): String {
        val inputSignature = when (val input = request.input) {
            is InferenceInput.Text -> input.value.hashCode()
            is InferenceInput.Messages -> input.messages.hashCode()
        }
        return "${request.key.asString()}|$inputSignature|${request.privacy.classification}|${request.output::class.simpleName}"
    }
}

/**
 * Runs a provider availability/capability probe defensively: a probe that throws
 * is treated as "no" (provider unavailable/incapable) rather than failing the
 * whole request, so routing can fall back. Cancellation still propagates.
 */
private suspend fun probe(timeout: Duration?, block: suspend () -> Boolean): Boolean =
    try {
        if (timeout != null) withTimeoutOrNull(timeout) { block() } == true else block()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (throwable: Throwable) {
        false
    }

/**
 * Bounds total time for a single provider attempt while keeping live emission.
 * A `channelFlow` is used so `send` inside `withTimeout` does not trip the flow
 * "emission from another coroutine" invariant; on timeout this fails with a
 * [TimeoutCancellationException] that the engine maps to a `Timeout` failure.
 */
private fun <T> Flow<T>.attemptTimeout(budget: Duration): Flow<T> = channelFlow {
    withTimeout(budget) {
        collect { send(it) }
    }
}

/** The smaller of two optional durations (null = unbounded). */
private fun minDuration(a: Duration?, b: Duration?): Duration? = when {
    a == null -> b
    b == null -> a
    else -> minOf(a, b)
}
