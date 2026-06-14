package dev.mattramotar.inferencestore.samples.crossplatform

import dev.mattramotar.inferencestore.core.provider.InferenceProvider
import dev.mattramotar.inferencestore.core.provider.ProviderKind

/**
 * iOS: a platform demo provider standing in for the Apple Foundation Models adapter
 * (ADR-0006 / OSS-41). Production swaps this `actual` for that provider.
 */
public actual fun platformProvider(): InferenceProvider =
    DemoTextProvider("ios-foundation-models", ProviderKind.Platform, "[iOS] Apple Foundation Models summary (demo).")
