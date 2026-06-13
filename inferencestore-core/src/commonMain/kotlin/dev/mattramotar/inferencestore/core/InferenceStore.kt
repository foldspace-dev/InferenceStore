package dev.mattramotar.inferencestore.core

/**
 * Module marker for `inferencestore-core`.
 *
 * The real streaming API (`stream`/`generate`), request model, provider
 * contract, policy engine, privacy enforcement, validators, and the canonical
 * event model land in the M1 issues (OSS-7 onward). This placeholder exists so
 * the module graph compiles for every target from the start (OSS-5).
 */
public object InferenceStore {
    public const val VERSION: String = "0.1.0-dev"
}
