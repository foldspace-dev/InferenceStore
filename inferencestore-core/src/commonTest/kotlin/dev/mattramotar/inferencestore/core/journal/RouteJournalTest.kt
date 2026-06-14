package dev.mattramotar.inferencestore.core.journal

import dev.mattramotar.inferencestore.core.event.AttemptOutcome
import dev.mattramotar.inferencestore.core.provider.ErrorCategory
import dev.mattramotar.inferencestore.core.provider.ProviderId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Route journal: attempt history, recent failures, and cooldowns (OSS-26). */
@OptIn(ExperimentalCoroutinesApi::class)
class RouteJournalTest {

    private val a = ProviderId("a")
    private val policy = CooldownPolicy(failureThreshold = 2, window = 1.minutes, cooldown = 30.seconds)

    @Test
    fun recordsAndQueriesRecentFailures() = runTest {
        val journal = MemoryRouteJournal(testTimeSource, policy)
        journal.record(a, AttemptOutcome.Succeeded)
        journal.record(a, AttemptOutcome.Failed, ErrorCategory.RateLimited)
        val failures = journal.recentFailures(a)
        assertEquals(1, failures.size)
        assertEquals(ErrorCategory.RateLimited, failures.first().category)
    }

    @Test
    fun cooldownActivatesAtThreshold() = runTest {
        val journal = MemoryRouteJournal(testTimeSource, policy)
        journal.record(a, AttemptOutcome.Failed)
        assertNull(journal.cooldown(a)) // 1 < threshold 2
        journal.record(a, AttemptOutcome.Failed)
        val cooldown = journal.cooldown(a)
        assertNotNull(cooldown)
        assertEquals(2, cooldown.recentFailures)
        assertTrue(cooldown.remaining > Duration.ZERO)
    }

    @Test
    fun cooldownExpiresAfterDuration() = runTest {
        val journal = MemoryRouteJournal(testTimeSource, policy)
        repeat(2) { journal.record(a, AttemptOutcome.Failed) }
        assertNotNull(journal.cooldown(a))
        delay(31.seconds) // past the 30s cooldown
        assertNull(journal.cooldown(a))
    }

    @Test
    fun cooldownOutlastsWindow_whenCooldownExceedsWindow() = runTest {
        // window 30s, cooldown 2min: the failures age out of the window, but the cooldown
        // deadline (stamped when the threshold tripped) is honored its full duration.
        val journal = MemoryRouteJournal(
            testTimeSource,
            CooldownPolicy(failureThreshold = 3, window = 30.seconds, cooldown = 2.minutes),
        )
        repeat(3) { journal.record(a, AttemptOutcome.Failed) }
        assertNotNull(journal.cooldown(a))
        delay(40.seconds) // past the 30s window, within the 2min cooldown
        assertTrue(journal.recentFailures(a).isEmpty()) // failures pruned by the window
        val cooldown = journal.cooldown(a)
        assertNotNull(cooldown, "cooldown must outlast the window")
        assertTrue(cooldown.remaining > Duration.ZERO)
    }

    @Test
    fun failuresOutsideWindowDoNotCount() = runTest {
        val journal = MemoryRouteJournal(testTimeSource, policy)
        journal.record(a, AttemptOutcome.Failed)
        delay(61.seconds) // past the 1-minute window -> pruned
        journal.record(a, AttemptOutcome.Failed)
        assertNull(journal.cooldown(a)) // only 1 failure remains in window
        assertEquals(1, journal.recentFailures(a).size)
    }

    @Test
    fun cooledDownProviders_isASnapshotOfActiveCooldowns() = runTest {
        val journal = MemoryRouteJournal(testTimeSource, policy)
        repeat(2) { journal.record(a, AttemptOutcome.Failed) }
        journal.record(ProviderId("b"), AttemptOutcome.Succeeded)
        assertEquals(setOf(a), journal.cooledDownProviders())
    }

    @Test
    fun clear_removesHistory() = runTest {
        val journal = MemoryRouteJournal(testTimeSource, policy)
        repeat(2) { journal.record(a, AttemptOutcome.Failed) }
        journal.clear(a)
        assertNull(journal.cooldown(a))
        assertTrue(journal.recentFailures(a).isEmpty())
    }
}
