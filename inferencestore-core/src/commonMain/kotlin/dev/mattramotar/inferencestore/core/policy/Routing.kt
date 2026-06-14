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
    /**
     * Orders [candidates] into a route. Must be side-effect-free: [stableId] calls it
     * speculatively with an empty list purely to derive the policy's stable identity
     * ([InferenceRoute.policyId]).
     */
    public fun selectRoute(candidates: List<ProviderCandidate>): InferenceRoute
}

/**
 * A policy that routes only among providers NOT in [providerIds] — e.g. a cooled-down
 * snapshot from a `RouteJournal` — delegating ordering to the receiver. The journal
 * read is suspending, so take the snapshot before the request and pass it here:
 *
 * ```kotlin
 * val cooled = journal.cooledDownProviders()
 * store.generate(request.copy(policy = Policies.preferLocalThenCloud().excluding(cooled)))
 * ```
 */
public fun InferencePolicy.excluding(
    providerIds: Set<dev.mattramotar.inferencestore.core.provider.ProviderId>,
): InferencePolicy = InferencePolicy { candidates ->
    selectRoute(candidates.filterNot { it.provider.id in providerIds })
}

/**
 * A policy's stable identity — its route's [InferenceRoute.policyId] — used for cache
 * fingerprinting (OSS-20) and dedupe compatibility. Readable and consistent across
 * platforms, unlike `::class` names, which are null on Kotlin/Native for the
 * lambda-based presets and collapse distinct lambdas to one class. Falls back to a
 * class name (or a non-null marker) if a custom policy throws on empty candidates.
 */
internal fun InferencePolicy.stableId(): String =
    runCatching { selectRoute(emptyList()).policyId }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }
        ?: this::class.qualifiedName ?: this::class.simpleName ?: "policy"

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
