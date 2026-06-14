package dev.mattramotar.inferencestore.core.policy

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Backoff delay computation (OSS-18). */
class BackoffTest {

    @Test
    fun none_isAlwaysZero() {
        assertEquals(Duration.ZERO, BackoffPolicy.None.delayFor(0))
        assertEquals(Duration.ZERO, BackoffPolicy.None.delayFor(5))
    }

    @Test
    fun fixed_isConstant() {
        val backoff = BackoffPolicy.Fixed(2.seconds)
        assertEquals(2.seconds, backoff.delayFor(0))
        assertEquals(2.seconds, backoff.delayFor(3))
    }

    @Test
    fun exponential_jitterNone_growsAndCaps() {
        val backoff = BackoffPolicy.Exponential(initial = 1.seconds, multiplier = 2.0, maxDelay = 10.seconds, jitter = Jitter.None)
        assertEquals(1.seconds, backoff.delayFor(0))
        assertEquals(2.seconds, backoff.delayFor(1))
        assertEquals(4.seconds, backoff.delayFor(2))
        assertEquals(8.seconds, backoff.delayFor(3))
        assertEquals(10.seconds, backoff.delayFor(4)) // capped at maxDelay
        assertEquals(10.seconds, backoff.delayFor(10))
    }

    @Test
    fun exponential_jitterFull_staysWithinBound() {
        val backoff = BackoffPolicy.Exponential(initial = 1.seconds, multiplier = 2.0, maxDelay = 10.seconds, jitter = Jitter.Full)
        val random = Random(42)
        repeat(50) {
            val delay = backoff.delayFor(2, random) // base 4s
            assertTrue(delay >= Duration.ZERO && delay <= 4.seconds, "delay out of bound: $delay")
        }
    }
}
