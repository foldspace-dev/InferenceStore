package dev.mattramotar.inferencestore.core.policy

import kotlin.math.pow
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** Delay strategy between same-provider retries (`timeout-retry-policy.md`). */
public sealed interface BackoffPolicy {
    public data object None : BackoffPolicy

    public data class Fixed(public val delay: Duration) : BackoffPolicy {
        init {
            require(delay >= Duration.ZERO) { "delay must be non-negative, was $delay" }
        }
    }

    public data class Exponential(
        public val initial: Duration,
        public val multiplier: Double = 2.0,
        public val maxDelay: Duration,
        public val jitter: Jitter = Jitter.Full,
    ) : BackoffPolicy {
        init {
            require(initial > Duration.ZERO) { "initial must be positive, was $initial" }
            require(multiplier >= 1.0) { "multiplier must be >= 1.0, was $multiplier" }
            require(maxDelay >= initial) { "maxDelay must be >= initial, was maxDelay=$maxDelay initial=$initial" }
        }
    }
}

/** How much randomness to apply to an [BackoffPolicy.Exponential] delay. */
public enum class Jitter { None, Full }

/**
 * The delay before retry number [retryIndex] (0-based: the first retry is 0).
 * [random] is injectable so tests can make `Jitter.Full` deterministic.
 */
public fun BackoffPolicy.delayFor(retryIndex: Int, random: Random = Random.Default): Duration = when (this) {
    BackoffPolicy.None -> Duration.ZERO
    is BackoffPolicy.Fixed -> delay
    is BackoffPolicy.Exponential -> {
        val scaled = (initial * multiplier.pow(retryIndex)).coerceAtMost(maxDelay)
        when (jitter) {
            Jitter.None -> scaled
            Jitter.Full -> {
                val millis = scaled.inWholeMilliseconds
                if (millis <= 0L) scaled else random.nextLong(millis + 1).milliseconds
            }
        }
    }
}
