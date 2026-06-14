package dev.mattramotar.inferencestore.core.policy

import dev.mattramotar.inferencestore.core.provider.ProviderId
import dev.mattramotar.inferencestore.core.provider.ProviderPrivacyBoundary
import dev.mattramotar.inferencestore.core.provider.ProviderPrivacyBoundaryId

/**
 * Canonical privacy model and enforcement gate (`privacy-model.md`).
 *
 * Privacy is enforced by the engine **before any provider invocation**; routing
 * policy can choose candidates but cannot override a denial (see
 * [allowsProvider]). Provider adapters are not trusted to self-enforce privacy.
 */

/** Canonical privacy classes. `Custom` carries an app-defined label. */
public sealed interface PrivacyClass {
    public data object Public : PrivacyClass
    public data object Internal : PrivacyClass
    public data object Personal : PrivacyClass
    public data object Sensitive : PrivacyClass
    public data object LocalOnly : PrivacyClass
    public data class Custom(public val value: String) : PrivacyClass
}

/** Whether request content may reach cloud-capable providers. */
public sealed interface CloudPermission {
    public data object Denied : CloudPermission
    public data object Allowed : CloudPermission
    public data class ApprovedProviders(public val providers: Set<ProviderId>) : CloudPermission
    public data class ApprovedBoundaries(public val boundaries: Set<ProviderPrivacyBoundaryId>) : CloudPermission
}

/**
 * What may be persisted. Persistence is globally opt-in: a write happens only
 * when both the cache policy and this permission allow it. Enforced where writes
 * occur (cache/artifact store, OSS-25/OSS-30).
 */
public data class PersistencePermission(
    public val persistPrompt: Boolean = false,
    public val persistOutput: Boolean = false,
    public val persistTrace: Boolean = true,
    public val persistTraceContent: Boolean = false,
)

/**
 * What may be emitted to monitors. Redacting by default (no raw prompt/output).
 * Enforced by the monitor projection (OSS-19).
 */
public data class TelemetryPermission(
    public val emitMetrics: Boolean = true,
    public val emitHashes: Boolean = true,
    public val emitProviderMetadata: Boolean = true,
    public val emitPrompt: Boolean = false,
    public val emitOutput: Boolean = false,
)

/** Redaction defaults applied to monitor events; wired into the monitor in OSS-19. */
public data class RedactionPolicy(
    public val redactPrompts: Boolean = true,
    public val redactOutputs: Boolean = true,
) {
    public companion object {
        public val Default: RedactionPolicy = RedactionPolicy()
    }
}

/**
 * Additional provider-boundary requirements (e.g. allowed regions / retention).
 * A placeholder for future constraints; [Default] imposes none and the cloud
 * gate is driven by [CloudPermission] today.
 */
public class ProviderBoundaryRequirement private constructor() {
    public companion object {
        public val Default: ProviderBoundaryRequirement = ProviderBoundaryRequirement()
    }
}

/** The canonical per-request privacy policy carried by every `InferenceRequest`. */
public data class PrivacyPolicy(
    public val classification: PrivacyClass = PrivacyClass.Personal,
    public val cloud: CloudPermission = CloudPermission.Denied,
    public val persistence: PersistencePermission = PersistencePermission(),
    public val telemetry: TelemetryPermission = TelemetryPermission(),
    public val redaction: RedactionPolicy = RedactionPolicy.Default,
    public val providerBoundary: ProviderBoundaryRequirement = ProviderBoundaryRequirement.Default,
) {
    public companion object {
        /** Strict default: `Personal`, cloud denied, no prompt/output persistence, metadata-only telemetry. */
        public val Default: PrivacyPolicy = PrivacyPolicy()

        /** Harmless public/demo content: `Public` class, cloud allowed. Production should be explicit. */
        public fun publicData(): PrivacyPolicy =
            PrivacyPolicy(classification = PrivacyClass.Public, cloud = CloudPermission.Allowed)

        /** Hard local-only: cloud can never be reached, regardless of routing policy. */
        public fun localOnly(): PrivacyPolicy =
            PrivacyPolicy(classification = PrivacyClass.LocalOnly, cloud = CloudPermission.Denied)
    }
}

/** Outcome of the privacy gate for one provider. */
public sealed interface PrivacyDecision {
    public data object Allow : PrivacyDecision
    public data class Deny(public val violation: PolicyViolation) : PrivacyDecision
}

/** Why the privacy gate denied a provider. */
public enum class PolicyViolation { CloudNotAllowed, ProviderNotApproved, BoundaryNotApproved }

/**
 * The privacy gate (`privacy-model.md` "Enforcement algorithm"). The engine
 * calls this before invoking a provider; a denial is terminal for that provider
 * and routing policy cannot override it. Local (non-cloud-like) providers are
 * always allowed.
 *
 * Takes [providerId] + [boundary] rather than full `ProviderMetadata` because the
 * gate runs before invocation, when only the provider's static identity and
 * declared boundary are known (richer metadata arrives on the first event).
 */
public fun PrivacyPolicy.allowsProvider(
    providerId: ProviderId,
    boundary: ProviderPrivacyBoundary,
): PrivacyDecision {
    // LocalOnly hard-denies cloud-like providers even if `cloud` was set otherwise.
    if (classification == PrivacyClass.LocalOnly && boundary.isCloudLike) {
        return PrivacyDecision.Deny(PolicyViolation.CloudNotAllowed)
    }
    if (!boundary.isCloudLike) {
        return PrivacyDecision.Allow
    }
    return when (val permission = cloud) {
        CloudPermission.Denied -> PrivacyDecision.Deny(PolicyViolation.CloudNotAllowed)
        CloudPermission.Allowed -> PrivacyDecision.Allow
        is CloudPermission.ApprovedProviders ->
            if (providerId in permission.providers) PrivacyDecision.Allow
            else PrivacyDecision.Deny(PolicyViolation.ProviderNotApproved)
        is CloudPermission.ApprovedBoundaries ->
            if (boundary.id in permission.boundaries) PrivacyDecision.Allow
            else PrivacyDecision.Deny(PolicyViolation.BoundaryNotApproved)
    }
}
