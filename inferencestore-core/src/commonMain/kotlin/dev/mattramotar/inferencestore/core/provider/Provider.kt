package dev.mattramotar.inferencestore.core.provider

import dev.mattramotar.inferencestore.core.model.InferenceInput
import dev.mattramotar.inferencestore.core.model.InferenceKey
import dev.mattramotar.inferencestore.core.model.InferenceRequest
import dev.mattramotar.inferencestore.core.model.OutputSpec
import dev.mattramotar.inferencestore.core.model.PromptSpec
import dev.mattramotar.inferencestore.core.policy.PrivacyPolicy
import dev.mattramotar.inferencestore.core.policy.TimeoutPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/** Stable provider identifier. */
@JvmInline
public value class ProviderId(public val value: String)

/** Where a provider runs / who operates it. Drives privacy and routing decisions. */
@Serializable
public enum class ProviderKind { Local, Cloud, Platform, Remote, Test }

/**
 * The adapter contract. Implementations connect InferenceStore to a real
 * inference system (local runtime, platform API, cloud API, app backend, or a
 * test fake). Core never depends on adapter implementation details.
 *
 * Adapters report availability/capabilities and stream provider events; they do
 * not own routing policy or persistence. See `provider-adapters.md`.
 */
public interface InferenceProvider {
    public val id: ProviderId
    public val kind: ProviderKind
    public val boundary: ProviderPrivacyBoundary

    /** Suspending availability probe; the engine bounds it by the availability timeout. */
    public suspend fun availability(context: InferenceContext): ProviderAvailability

    /** Reports whether this provider can serve [request] and which capabilities it offers. */
    public suspend fun capabilities(
        request: InferenceRequest<*>,
        context: InferenceContext,
    ): CapabilityReport

    /** Cold stream of provider events for a single attempt. */
    public fun <Output : Any> stream(
        request: ProviderRequest<Output>,
        context: InferenceContext,
    ): Flow<ProviderEvent<Output>>
}

/**
 * Per-attempt execution context supplied by the engine. Carries the effective
 * [timeout] so availability/capability probes and streaming can be bounded
 * (binding is enforced by the engine — OSS-10 / OSS-18). [attributes] is an
 * adapter-readable bag for engine-provided hints.
 */
public class InferenceContext(
    public val timeout: TimeoutPolicy = TimeoutPolicy.Default,
    public val attributes: Map<String, String> = emptyMap(),
)

/** Result of [InferenceProvider.availability]. */
public sealed interface ProviderAvailability {
    public data object Available : ProviderAvailability
    public data class Unavailable(public val reason: UnavailableReason) : ProviderAvailability
}

public enum class UnavailableReason { NetworkUnavailable, ModelMissing, Unsupported, Disabled, Unknown }

/** Result of [InferenceProvider.capabilities]. */
public data class CapabilityReport(
    public val supported: Boolean,
    public val capabilities: Set<Capability>,
)

/**
 * Provider capabilities. Extensible — new capabilities can be added without
 * changing the contract. Not every provider supports every capability.
 */
public sealed interface Capability {
    public data object TextGeneration : Capability
    public data object Chat : Capability
    public data object Streaming : Capability
    public data object StructuredOutput : Capability
    public data object JsonSchema : Capability
    public data object Embeddings : Capability
    public data object ImageInput : Capability
    public data object AudioInput : Capability
    public data object ToolCalling : Capability
    public data object Offline : Capability
}

/** Stable identifier for a provider privacy boundary (used by `CloudPermission.ApprovedBoundaries`). */
@JvmInline
public value class ProviderPrivacyBoundaryId(public val value: String)

/** Where a provider executes (`privacy-model.md`). Drives the cloud privacy gate via [ProviderPrivacyBoundary.isCloudLike]. */
public enum class ProviderExecutionBoundary {
    LocalProcess,
    PlatformOnDevice,
    PlatformHybrid,
    AppBackend,
    ThirdPartyCloud,
}

/**
 * Declares a provider's data boundary. This is metadata to inform routing and
 * the privacy gate — not a legal guarantee (see `security-privacy.md`).
 */
public data class ProviderPrivacyBoundary(
    public val id: ProviderPrivacyBoundaryId,
    public val execution: ProviderExecutionBoundary,
    public val vendor: String? = null,
    public val dataRetention: String? = null,
    public val trainingUse: String? = null,
    public val region: String? = null,
    public val notes: String? = null,
) {
    /**
     * Whether this boundary is cloud-capable: `PlatformHybrid`, `AppBackend`, or
     * `ThirdPartyCloud` must pass the privacy gate before use (`privacy-model.md`).
     */
    public val isCloudLike: Boolean
        get() = execution == ProviderExecutionBoundary.PlatformHybrid ||
            execution == ProviderExecutionBoundary.AppBackend ||
            execution == ProviderExecutionBoundary.ThirdPartyCloud

    public companion object {
        public fun localDevice(): ProviderPrivacyBoundary =
            ProviderPrivacyBoundary(ProviderPrivacyBoundaryId("local-process"), ProviderExecutionBoundary.LocalProcess)

        public fun platform(vendor: String): ProviderPrivacyBoundary =
            ProviderPrivacyBoundary(
                ProviderPrivacyBoundaryId("platform-on-device:$vendor"),
                ProviderExecutionBoundary.PlatformOnDevice,
                vendor = vendor,
            )

        public fun platformHybrid(vendor: String): ProviderPrivacyBoundary =
            ProviderPrivacyBoundary(
                ProviderPrivacyBoundaryId("platform-hybrid:$vendor"),
                ProviderExecutionBoundary.PlatformHybrid,
                vendor = vendor,
            )

        public fun appBackend(vendor: String? = null): ProviderPrivacyBoundary =
            ProviderPrivacyBoundary(
                ProviderPrivacyBoundaryId("app-backend${vendor?.let { ":$it" } ?: ""}"),
                ProviderExecutionBoundary.AppBackend,
                vendor = vendor,
            )

        public fun thirdPartyCloud(vendor: String): ProviderPrivacyBoundary =
            ProviderPrivacyBoundary(
                ProviderPrivacyBoundaryId("third-party-cloud:$vendor"),
                ProviderExecutionBoundary.ThirdPartyCloud,
                vendor = vendor,
            )
    }
}

/**
 * Provider-facing, single-attempt view of a request. The engine derives this
 * per attempt from an [InferenceRequest] (see [toProviderRequest]).
 *
 * Carries the request's effective [privacy] so adapters can translate
 * retention/training requirements into vendor-specific flags. Privacy
 * *enforcement* — whether this provider may be invoked at all — is done by the
 * execution controller before the adapter is ever called (see `privacy-model.md`).
 *
 * The effective timeout is NOT carried here: it lives on [InferenceContext] so
 * there is a single source of truth the engine controls per attempt (which can
 * legitimately differ from the request's policy under retry/deadline scenarios).
 */
public data class ProviderRequest<Output : Any>(
    public val key: InferenceKey,
    public val input: InferenceInput,
    public val output: OutputSpec<Output>,
    public val privacy: PrivacyPolicy,
    public val prompt: PromptSpec? = null,
)

/** Derives the provider-facing request for one attempt. */
public fun <Output : Any> InferenceRequest<Output>.toProviderRequest(): ProviderRequest<Output> =
    ProviderRequest(
        key = key,
        input = input,
        output = output,
        privacy = privacy,
        prompt = prompt,
    )

/** Capabilities a request needs, derived from its input and output spec. */
public fun InferenceRequest<*>.requiredCapabilities(): Set<Capability> = buildSet {
    add(Capability.TextGeneration)
    if (input is InferenceInput.Messages) add(Capability.Chat)
    when (output) {
        is OutputSpec.Json, is OutputSpec.Custom -> add(Capability.StructuredOutput)
        else -> Unit
    }
}
