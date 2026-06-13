package dev.mattramotar.inferencestore.testkit

import dev.mattramotar.inferencestore.core.InferenceStore

/**
 * Module marker for `inferencestore-testkit`.
 *
 * The fake provider, scripted responses, virtual clock, and route assertions
 * land in OSS-12. This placeholder verifies the testkit depends on core and
 * compiles for every target.
 */
public object Testkit {
    public val coreVersion: String = InferenceStore.VERSION
}
