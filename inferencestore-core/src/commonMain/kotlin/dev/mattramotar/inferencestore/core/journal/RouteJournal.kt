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
 *
 * Crossing the failure threshold within [CooldownPolicy.window] stamps a `cooledUntil`
 * deadline that is honored independently of window pruning, so a configured cooldown
 * longer than the window still lasts its full duration.
 */
public class MemoryRouteJournal(
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val policy: CooldownPolicy = CooldownPolicy(),
) : RouteJournal {

    private class Entry(val mark: TimeMark, val outcome: AttemptOutcome, val category: ErrorCategory?)
    private class CooldownState(val until: TimeMark, val failures: Int)

    private val mutex = Mutex()
    private val attempts: MutableMap<ProviderId, MutableList<Entry>> = mutableMapOf()
    private val cooled: MutableMap<ProviderId, CooldownState> = mutableMapOf()

    override suspend fun record(providerId: ProviderId, outcome: AttemptOutcome, category: ErrorCategory?) {
        mutex.withLock {
            val list = attempts.getOrPut(providerId) { mutableListOf() }
            list.add(Entry(timeSource.markNow(), outcome, category))
            pruneExpired(providerId, list)
            if (outcome == AttemptOutcome.Failed) {
                val failures = list.count { it.outcome == AttemptOutcome.Failed }
                if (failures >= policy.failureThreshold) {
                    // Cooldown runs for `cooldown` from this (the most recent) failure.
                    cooled[providerId] = CooldownState(timeSource.markNow() + policy.cooldown, failures)
                }
            }
        }
    }

    override suspend fun recentFailures(providerId: ProviderId): List<RouteFailure> = mutex.withLock {
        val list = attempts[providerId] ?: return@withLock emptyList()
        pruneExpired(providerId, list)
        list.filter { it.outcome == AttemptOutcome.Failed }
            .map { RouteFailure(providerId, it.category ?: ErrorCategory.Unknown, it.mark.elapsedNow()) }
            .sortedBy { it.age } // most recent (smallest age) first
    }

    override suspend fun cooldown(providerId: ProviderId): Cooldown? = mutex.withLock {
        cooldownLocked(providerId)
    }

    override suspend fun cooledDownProviders(): Set<ProviderId> = mutex.withLock {
        // Snapshot the keys first: cooldownLocked removes expired entries as it goes.
        cooled.keys.toList().filterTo(mutableSetOf()) { cooldownLocked(it) != null }
    }

    override suspend fun clear(providerId: ProviderId) {
        mutex.withLock {
            attempts.remove(providerId)
            cooled.remove(providerId)
        }
    }

    override suspend fun clearAll() {
        mutex.withLock {
            attempts.clear()
            cooled.clear()
        }
    }

    // Caller holds the lock. Returns the active cooldown, evicting it once expired.
    private fun cooldownLocked(providerId: ProviderId): Cooldown? {
        val state = cooled[providerId] ?: return null
        val remaining = -state.until.elapsedNow() // until is in the future while cooling down
        if (remaining <= Duration.ZERO) {
            cooled.remove(providerId)
            return null
        }
        return Cooldown(remaining, state.failures)
    }

    private fun pruneExpired(providerId: ProviderId, list: MutableList<Entry>) {
        list.removeAll { it.mark.elapsedNow() > policy.window }
        // Don't retain an empty history for an idle provider (unless it's still cooling).
        if (list.isEmpty() && providerId !in cooled) attempts.remove(providerId)
    }
}
