package com.rsps1008.stockify.data

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import org.junit.Assert.assertEquals
import org.junit.Test

class ValuationClockTest {

    @Test
    fun emitsImmediatelyAndContinuesAfterInterval() = runBlocking {
        var now = 100L
        val values = withTimeout(1_000L) {
            valuationClockFlow(clock = { now++ }, intervalMillis = 1L)
                .take(2)
                .toList()
        }

        assertEquals(listOf(100L, 101L), values)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonPositiveInterval() {
        valuationClockFlow(intervalMillis = 0L)
    }
}
