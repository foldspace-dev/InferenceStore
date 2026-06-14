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
import dev.mattramotar.inferencestore.core.cache.DefaultFingerprinter
import dev.mattramotar.inferencestore.core.cache.Fingerprinter
import dev.mattramotar.inferencestore.core.cache.InferenceArtifact
import dev.mattramotar.inferencestore.core.cache.InferenceCache
import dev.mattramotar.inferencestore.core.dedupe.DedupeCoordinator
import dev.mattramotar.inferencestore.core.model.InferenceRequest
import dev.mattramotar.inferencestore.core.model.OutputSpec
import dev.mattramotar.inferencestore.core.monitor.InferenceMonitor
import dev.mattramotar.inferencestore.core.monitor.MonitorEvent
import dev.mattramotar.inferencestore.core.monitor.RequestSummary
import dev.mattramotar.inferencestore.core.policy.FallbackMapping
import dev.mattramotar.inferencestore.core.policy.delayFor
import dev.mattramotar.inferencestore.core.policy.InferencePolicy
import dev.mattramotar.inferencestore.core.policy.InferenceRoute
import dev.mattramotar.inferencestore.core.policy.Policies
import dev.mattramotar.inferencestore.core.policy.PolicyViolation
import dev.mattramotar.inferencestore.core.policy.PrivacyDecision
import dev.mattramotar.inferencestore.core.policy.ProviderCandidate
import dev.mattramotar.inferencestore.core.policy.CacheAccess
import dev.mattramotar.inferencestore.core.policy.allowsProvider
import dev.mattramotar.inferencestore.core.policy.stableId
import dev.mattramotar.inferencestore.core.provider.ErrorCategory
import dev.mattramotar.inferencestore.core.provider.ProviderMetadata
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
import kotlinx.coroutines.cancel
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
import kotlinx.coroutines.withContext
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
public interface InferenceStore : AutoCloseable {
    public fun <Output : Any> stream(request: InferenceRequest<Output>): Flow<InferenceEvent<Output>>

    public suspend fun <Output : Any> generate(request: InferenceRequest<Output>): InferenceResult<Output>

    /** Releases the store's dedupe scope. Optional; default is a no-op for stores without one. */
    override fun close() {}

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
            monitors = emptyList(),
        )
    }
}

/** DSL for [InferenceStore.build]. */
public class InferenceStoreBuilder {
    private val providers: MutableList<InferenceProvider> = mutableListOf()
    private val monitors: MutableList<InferenceMonitor> = mutableListOf()

    /** Default routing policy; a request may override it via `InferenceRequest.policy`. */
    public var policy: InferencePolicy = Policies.preferLocalThenCloud()
    public var executionConfig: InferenceExecutionConfig = InferenceExecutionConfig()

    /** Optional artifact cache (`storage-model.md`); null disables read/write entirely. */
    public var cache: InferenceCache? = null

    /** Fingerprint strategy used to key the cache; defaults to [DefaultFingerprinter]. */
    public var fingerprinter: Fingerprinter = DefaultFingerprinter

    public fun provider(provider: InferenceProvider) {
        providers += provider
    }

    /** Register a redacted-telemetry observer; called for each request's lifecycle. */
    public fun monitor(monitor: InferenceMonitor) {
        monitors += monitor
    }

    internal fun build(): InferenceStore =
        RoutedInferenceStore(providers.toList(), policy, executionConfig, monitors.toList(), cache, fingerprinter)
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
    private val monitors: List<InferenceMonitor> = emptyList(),
    private val cache: InferenceCache? = null,
    private val fingerprinter: Fingerprinter = DefaultFingerprinter,
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
    // collector so ref-counted sharing/cleanup works. Groups ref-count their
    // subscribers and cancel upstream when the last one leaves; close() tears the
    // whole scope down.
    private val dedupeScope = CoroutineScope(SupervisorJob() + config.providerContext)
    private val dedupe = DedupeCoordinator(dedupeScope)

    override fun close() {
        dedupeScope.cancel()
    }

    override fun <Output : Any> stream(request: InferenceRequest<Output>): Flow<InferenceEvent<Output>> {
        val route = routeStream(request).withMonitors(request)
        val dedupeKey = dedupeKeyOrNull(request)
        return if (dedupeKey != null) dedupe.stream(dedupeKey, route) else route
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

            // Artifact cache (storage-model.md): a read may short-circuit provider work.
            // The fingerprint is content-free and reused for the post-success write.
            // All cache work is best-effort — a failing cache (or custom fingerprinter)
            // must never fail an otherwise-successful request, so it is tolerated to a
            // miss/skipped-write rather than propagated. Compute the fingerprint only
            // when this request could actually use the cache, and bound it by the
            // remaining budget so a slow custom fingerprinter can't blow the deadline.
            //
            // Only outputs with a stable identity are cacheable: a custom parser has no
            // type discriminator (its fingerprint can't distinguish two Custom<T>s), so
            // caching it could serve a wrongly-typed artifact (ClassCastException in the
            // caller). Excluded for the same reason dedupe excludes Custom outputs.
            val cacheableOutput = outputSignature(request.output) != null
            val wantsCacheAccess = cache != null && cacheableOutput && (
                request.cache.read == CacheAccess.Allow ||
                    (request.cache.write == CacheAccess.Allow && request.privacy.persistence.persistOutput)
                )
            val fingerprint = if (wantsCacheAccess) {
                tolerateCacheFailure(remainingBudget()) { fingerprinter.fingerprint(request) }
            } else {
                null
            }
            if (cache != null && fingerprint != null && request.cache.read == CacheAccess.Allow) {
                // Bound the read by the remaining request budget so a slow (non-throwing)
                // cache cannot hang the request past its deadline.
                val cached = tolerateCacheFailure(remainingBudget()) {
                    cache.read(fingerprint, request.output, request.cache)
                }
                // A redacted artifact (output omitted by privacy) cannot serve a hit.
                if (cached?.output != null) {
                    emit(
                        InferenceEvent.Done(
                            requestId,
                            InferenceResult(
                                request.key,
                                cached.output,
                                cached.rawText,
                                trace = RouteTrace(
                                    requestId = requestId.value,
                                    key = key,
                                    finalStatus = FinalStatus.Succeeded,
                                    finalProvider = cached.provider.providerId.value,
                                    servedFromCache = true,
                                ),
                            ),
                        ),
                    )
                    return@flow
                }
            }

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
                    var attemptMetadata: ProviderMetadata? = null
                    var result: AttemptResult<Output> = AttemptResult.NoTerminal()
                    emitAll(
                        provider.stream(providerRequest, context)
                            .let { if (attemptBudget != null) it.attemptTimeout(attemptBudget) else it }
                            .transform<ProviderEvent<Output>, InferenceEvent<Output>> { event ->
                                when (event) {
                                    is ProviderEvent.Started -> {
                                        attemptMetadata = event.metadata
                                        modelId = event.metadata.modelId
                                    }
                                    is ProviderEvent.Token -> emit(InferenceEvent.Token(requestId, event.text))
                                    is ProviderEvent.Partial -> emit(InferenceEvent.Partial(requestId, event.value))
                                    is ProviderEvent.Completed -> {
                                        attemptMetadata = event.metadata
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
                    var attemptValidation: ValidationResult? = null
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
                        attemptValidation = verdict
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
                        val successTrace = RouteTrace(
                            requestId = requestId.value,
                            key = key,
                            finalStatus = FinalStatus.Succeeded,
                            policyId = policyId,
                            attempts = attempts.toList(),
                            rejectedProviders = rejected,
                            fallbackReasons = fallbackReasons.toList(),
                            finalProvider = provider.id.value,
                        )
                        // Cache write (storage-model.md): only when BOTH the cache policy
                        // and the privacy policy allow persisting output. Runs before the
                        // Done emission so generate()'s terminal short-circuit can't skip it.
                        if (cache != null && fingerprint != null &&
                            request.cache.write == CacheAccess.Allow &&
                            request.privacy.persistence.persistOutput
                        ) {
                            tolerateCacheFailure(remainingBudget()) {
                                cache.write(
                                    InferenceArtifact(
                                        fingerprint = fingerprint,
                                        output = success.output,
                                        rawText = success.rawText,
                                        provider = attemptMetadata
                                            ?: ProviderMetadata(provider.id, provider.kind, provider.boundary, modelId = modelId),
                                        trace = if (request.privacy.persistence.persistTrace) successTrace else null,
                                        validation = attemptValidation,
                                    ),
                                    request.cache,
                                )
                            }
                        }
                        emit(InferenceEvent.Done(requestId, InferenceResult(request.key, success.output, success.rawText, trace = successTrace)))
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
        // generate() may join an in-flight dedupe group until the terminal result
        // (it needs no token replay); otherwise it runs the route directly.
        val route = routeStream(request).withMonitors(request)
        val dedupeKey = dedupeKeyOrNull(request)
        val terminal: InferenceEvent<Output> = if (dedupeKey != null) {
            dedupe.generate(dedupeKey, route)
        } else {
            route.first { it is InferenceEvent.Done<*> || it is InferenceEvent.Failed }
        }
        return when (terminal) {
            is InferenceEvent.Done<*> -> {
                @Suppress("UNCHECKED_CAST")
                (terminal as InferenceEvent.Done<Output>).result
            }
            is InferenceEvent.Failed -> throw InferenceException(terminal.error)
            else -> error("unreachable terminal event: $terminal")
        }
    }

    // Projects each stream event to a redacted MonitorEvent and dispatches it
    // exactly once per execution (applied to routeStream, before dedupe sharing).
    private fun <Output : Any> Flow<InferenceEvent<Output>>.withMonitors(
        request: InferenceRequest<Output>,
    ): Flow<InferenceEvent<Output>> {
        if (monitors.isEmpty()) return this
        val requestId = RequestId(request.key.asString())
        return flow {
            var tokens = 0
            var routeSelected = false
            collect { event ->
                when (event) {
                    is InferenceEvent.Started -> dispatch(MonitorEvent.RequestStarted(requestId, request.key))
                    is InferenceEvent.ProviderAttemptStarted -> {
                        if (!routeSelected) {
                            routeSelected = true
                            dispatch(MonitorEvent.RouteSelected(requestId, event.attempt.provider))
                        }
                        dispatch(MonitorEvent.ProviderAttemptStarted(requestId, event.attempt))
                    }
                    is InferenceEvent.Token -> dispatch(MonitorEvent.TokenEmitted(requestId, ++tokens))
                    is InferenceEvent.Partial<*> -> Unit
                    is InferenceEvent.ValidationCompleted -> dispatch(MonitorEvent.ValidationCompleted(requestId, event.result))
                    is InferenceEvent.ProviderAttemptCompleted -> dispatch(MonitorEvent.ProviderAttemptCompleted(requestId, event.attempt))
                    is InferenceEvent.FallbackStarted -> dispatch(MonitorEvent.FallbackStarted(requestId, event.reason))
                    is InferenceEvent.RetryScheduled -> dispatch(MonitorEvent.RetryScheduled(requestId, event.provider, event.attemptNumber, event.delay))
                    is InferenceEvent.Done<*> -> dispatch(
                        MonitorEvent.RequestCompleted(
                            requestId,
                            RequestSummary(event.result.trace?.finalProvider, FinalStatus.Succeeded, tokens),
                        ),
                    )
                    is InferenceEvent.Failed -> dispatch(MonitorEvent.RequestFailed(requestId, event.error.category))
                }
                emit(event)
            }
        }
    }

    // Monitors run on config.monitorContext when configured (apps can move telemetry
    // off the collector), inline for the default EmptyCoroutineContext (no switch).
    private suspend fun dispatch(event: MonitorEvent) {
        val context = config.monitorContext
        if (context == EmptyCoroutineContext) dispatchInline(event) else withContext(context) { dispatchInline(event) }
    }

    // A misbehaving monitor must never break inference; cancellation still propagates.
    private fun dispatchInline(event: MonitorEvent) {
        for (monitor in monitors) {
            try {
                monitor.onEvent(event)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                // best-effort observability
            }
        }
    }

    private fun outputSignature(output: OutputSpec<*>): String? = when (output) {
        is OutputSpec.Text -> "text"
        is OutputSpec.Json -> "json:${output.serializer.descriptor.serialName}"
        is OutputSpec.Custom -> null // no stable identity for a custom parser → do not dedupe
    }

    // Dedupe compatibility key, or null when the request must run alone. Dedupe is
    // opt-in (allowDedupe) and only for outputs with a stable identity (not Custom).
    //
    // The key is the canonical OSS-20 [InferenceFingerprint] — content-free (hashes,
    // not raw prompts) and the SAME identity the cache keys on, so dedupe and cache
    // agree on what "equivalent" means — plus the request's cache access policy. The
    // cache part matters because two in-flight requests differing only in cache
    // read/write must NOT share one execution, or one caller's explicit cache.write
    // would silently decide the other's persistence. A misbehaving custom
    // fingerprinter disables sharing (null) rather than failing the request.
    private fun dedupeKeyOrNull(request: InferenceRequest<*>): String? {
        if (!request.cache.allowDedupe || outputSignature(request.output) == null) return null
        val fingerprint = try {
            fingerprinter.fingerprint(request)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            return null
        }
        return "$fingerprint|${request.cache}"
    }
}

/**
 * Runs a provider availability/capability probe defensively: a probe that throws
 * is treated as "no" (provider unavailable/incapable) rather than failing the
 * whole request, so routing can fall back. Cancellation still propagates.
 */
/**
 * Runs a best-effort cache operation, bounded by [budget] when set so a slow
 * (non-throwing) cache cannot hang the request past its deadline. A failure (a
 * misbehaving cache or custom fingerprinter) or a timeout yields null — treated as
 * a miss or a skipped write — and never fails the request; caller cancellation
 * still propagates.
 */
private suspend fun <T> tolerateCacheFailure(budget: Duration?, block: suspend () -> T): T? =
    try {
        if (budget != null) withTimeoutOrNull(budget) { block() } else block()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (throwable: Throwable) {
        null
    }

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
