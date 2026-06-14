package dev.mattramotar.inferencestore.core.journal

import dev.mattramotar.inferencestore.core.event.AttemptOutcome
import dev.mattramotar.inferencestore.core.provider.ErrorCategory
import dev.mattramotar.inferencestore.core.provider.ProviderId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** A recent provider failure, with how long ago it happened. */
public data class RouteFailure(
    public val providerId: ProviderId,
    public val category: ErrorCategory,
    public val age: Duration,
)

/** An active cooldown for a provider: avoid it for [remaining], after [recentFailures] failures. */
public data class Cooldown(
    public val remaining: Duration,
    public val recentFailures: Int,
)

/**
 * When a provider has too many recent failures, it is "cooling down": [failureThreshold]
 * or more failures within [window] put it in cooldown for [cooldown] (measured from the
 * most recent failure). A policy may avoid cooled-down providers.
 */
public data class CooldownPolicy(
    public val failureThreshold: Int = 3,
    public val window: Duration = 1.minutes,
    public val cooldown: Duration = 30.seconds,
) {
    init {
        require(failureThreshold >= 1) { "failureThreshold must be >= 1, was $failureThreshold" }
        require(window > Duration.ZERO) { "window must be positive, was $window" }
        require(cooldown > Duration.ZERO) { "cooldown must be positive, was $cooldown" }
    }
}

/**
 * Records provider attempt history so policies can avoid repeatedly choosing a failing
 * provider (`storage-model.md`). Interface + in-memory implementation in MVP; a
 * persistent journal is post-MVP.
 *
 * Consumption is decoupled from routing (`InferencePolicy.selectRoute` is non-suspend):
 * read a cooldown snapshot before a request and pass it to `InferencePolicy.excluding(...)`.
 */
public interface RouteJournal {
    /** Record one provider attempt outcome (optionally its failure [category]). */
    public suspend fun record(providerId: ProviderId, outcome: AttemptOutcome, category: ErrorCategory? = null)

    /** Failures for [providerId] still within the cooldown window, most recent first. */
    public suspend fun recentFailures(providerId: ProviderId): List<RouteFailure>

    /** The active cooldown for [providerId], or null if it is not cooling down. */
    public suspend fun cooldown(providerId: ProviderId): Cooldown?

    /** Snapshot of every provider currently in cooldown — pass to `InferencePolicy.excluding`. */
    public suspend fun cooledDownProviders(): Set<ProviderId>

    public suspend fun clear(providerId: ProviderId)
    public suspend fun clearAll()
}

/**
 * In-memory [RouteJournal]. Tracks recent attempts against [timeSource] (inject a test
 * clock in tests) and derives cooldowns from [policy]. Coroutine-safe via a [Mutex].
 */
public class MemoryRouteJournal(
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val policy: CooldownPolicy = CooldownPolicy(),
) : RouteJournal {

    private class Entry(val mark: TimeMark, val outcome: AttemptOutcome, val category: ErrorCategory?)

    private val mutex = Mutex()
    private val attempts: MutableMap<ProviderId, MutableList<Entry>> = mutableMapOf()

    override suspend fun record(providerId: ProviderId, outcome: AttemptOutcome, category: ErrorCategory?) {
        mutex.withLock {
            val list = attempts.getOrPut(providerId) { mutableListOf() }
            list.add(Entry(timeSource.markNow(), outcome, category))
            pruneExpired(list)
        }
    }

    override suspend fun recentFailures(providerId: ProviderId): List<RouteFailure> = mutex.withLock {
        val list = attempts[providerId] ?: return@withLock emptyList()
        pruneExpired(list)
        list.filter { it.outcome == AttemptOutcome.Failed }
            .map { RouteFailure(providerId, it.category ?: ErrorCategory.Unknown, it.mark.elapsedNow()) }
            .sortedBy { it.age } // most recent (smallest age) first
    }

    override suspend fun cooldown(providerId: ProviderId): Cooldown? = mutex.withLock {
        cooldownLocked(providerId)
    }

    override suspend fun cooledDownProviders(): Set<ProviderId> = mutex.withLock {
        attempts.keys.filterTo(mutableSetOf()) { cooldownLocked(it) != null }
    }

    override suspend fun clear(providerId: ProviderId) {
        mutex.withLock { attempts.remove(providerId) }
    }

    override suspend fun clearAll() {
        mutex.withLock { attempts.clear() }
    }

    // Caller holds the lock. Returns the active cooldown, pruning expired entries.
    private fun cooldownLocked(providerId: ProviderId): Cooldown? {
        val list = attempts[providerId] ?: return null
        pruneExpired(list)
        val failures = list.filter { it.outcome == AttemptOutcome.Failed }
        if (failures.size < policy.failureThreshold) return null
        // Cooldown runs from the most recent failure (smallest elapsed).
        val sinceLast = failures.minOf { it.mark.elapsedNow() }
        val remaining = policy.cooldown - sinceLast
        return if (remaining > Duration.ZERO) Cooldown(remaining, failures.size) else null
    }

    private fun pruneExpired(list: MutableList<Entry>) {
        list.removeAll { it.mark.elapsedNow() > policy.window }
    }
}
