package dev.mattramotar.inferencestore.core.policy

import dev.mattramotar.inferencestore.core.provider.InferenceProvider
import dev.mattramotar.inferencestore.core.provider.ProviderKind

/**
 * A provider the engine evaluated for a request, with its availability and
 * capability verdict already resolved. Policies order/filter candidates; they do
 * not perform the (suspending) probes themselves.
 */
public data class ProviderCandidate(
    public val provider: InferenceProvider,
    public val available: Boolean,
    public val supported: Boolean,
)

/** The ordered providers a policy chose to attempt, plus the policy id for the trace. */
public data class InferenceRoute(
    public val policyId: String,
    public val orderedProviders: List<InferenceProvider>,
)

/**
 * Decides which providers to attempt, and in what order, for a request.
 *
 * The engine precomputes availability/capability into [ProviderCandidate]s; the
 * policy filters and orders them. Privacy enforcement is a separate, mandatory
 * gate (OSS-15) that a policy cannot bypass.
 */
public fun interface InferencePolicy {
    public fun selectRoute(candidates: List<ProviderCandidate>): InferenceRoute
}

/**
 * The five built-in MVP policy presets (`routing-policy.md`).
 *
 * Presets route by deployment kind: local tier = [ProviderKind.Local] /
 * [ProviderKind.Platform], cloud tier = [ProviderKind.Cloud] /
 * [ProviderKind.Remote]. [ProviderKind.Test] is deliberately not a routable
 * deployment kind, so a Test-kind provider is never selected by a preset — a
 * routing test should declare the kind it simulates (Local/Cloud), and
 * [dev.mattramotar.inferencestore.core.InferenceStore.single] routes a provider
 * of any kind.
 */
public object Policies {
    private val localKinds: Set<ProviderKind> = setOf(ProviderKind.Local, ProviderKind.Platform)
    private val cloudKinds: Set<ProviderKind> = setOf(ProviderKind.Cloud, ProviderKind.Remote)

    /** Local / on-device providers only; never falls back to cloud. */
    public fun localOnly(): InferencePolicy = tiered("localOnly", localKinds)

    /** Cloud / remote providers only. */
    public fun cloudOnly(): InferencePolicy = tiered("cloudOnly", cloudKinds)

    /** Try local/platform first, then cloud/remote. */
    public fun preferLocalThenCloud(): InferencePolicy = tiered("preferLocalThenCloud", localKinds, cloudKinds)

    /** Try cloud/remote first, then local/platform. */
    public fun preferCloudThenLocal(): InferencePolicy = tiered("preferCloudThenLocal", cloudKinds, localKinds)

    /**
     * Try local first, then cloud for repair. Routing order matches
     * [preferLocalThenCloud]; pair it with a request `validator` and
     * `FallbackPolicy(repairEnabled = true)` so a local output that fails
     * validation repairs on cloud (OSS-17).
     */
    public fun validateLocalThenCloudRepair(): InferencePolicy =
        tiered("validateLocalThenCloudRepair", localKinds, cloudKinds)

    // Keeps only usable candidates and orders them tier by tier (preserving the
    // candidate order within each tier).
    private fun tiered(id: String, vararg tiers: Set<ProviderKind>): InferencePolicy = InferencePolicy { candidates ->
        val usable = candidates.filter { it.available && it.supported }
        val ordered = tiers.flatMap { tier -> usable.filter { it.provider.kind in tier }.map { it.provider } }
        InferenceRoute(id, ordered)
    }
}
