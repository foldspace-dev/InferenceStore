package dev.mattramotar.inferencestore.samples.crossplatform

import dev.mattramotar.inferencestore.core.provider.InferenceProvider
import dev.mattramotar.inferencestore.core.provider.ProviderKind

/** JVM/desktop: a local demo provider (swap in any JVM-capable adapter). */
public actual fun platformProvider(): InferenceProvider =
    DemoTextProvider("jvm-local", ProviderKind.Local, "[JVM] on-device summary (demo).")
