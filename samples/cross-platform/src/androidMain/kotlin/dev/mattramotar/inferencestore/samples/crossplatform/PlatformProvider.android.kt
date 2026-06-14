package dev.mattramotar.inferencestore.samples.crossplatform

import dev.mattramotar.inferencestore.core.provider.InferenceProvider
import dev.mattramotar.inferencestore.core.provider.ProviderKind

/**
 * Android: a local demo provider standing in for the LiteRT-LM adapter (OSS-29).
 * Production swaps this `actual` for `LiteRtLmProvider`.
 */
public actual fun platformProvider(): InferenceProvider =
    DemoTextProvider("android-litertlm", ProviderKind.Local, "[Android] LiteRT-LM summary (demo).")
