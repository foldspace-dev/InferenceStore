package dev.mattramotar.inferencestore.provider.litertlm

/**
 * Placeholder for the LiteRT-LM Android/JVM local adapter (OSS-29).
 *
 * Real engine initialization (off the main thread), token streaming, and
 * availability/capability/error mapping land with that issue.
 */
public object LiteRtLmProvider {
    public const val ID: String = "litertlm"
}
