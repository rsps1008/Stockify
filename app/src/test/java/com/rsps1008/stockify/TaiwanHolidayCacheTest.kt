package com.rsps1008.stockify

import com.rsps1008.stockify.data.TaiwanHolidayCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class TaiwanHolidayCacheTest {
    private val date = LocalDate.of(2026, 10, 9)

    @Test
    fun repeatedAndConcurrentLookupsOnlyLoadOnceAndRefreshAfterOneDay() = runBlocking {
        var loads = 0
        var now = 0L
        val cache = TaiwanHolidayCache({ loads++; delay(10); mapOf(date to true) }, { now })
        assertTrue((1..12).map { async { cache.isHoliday(date) } }.awaitAll().all { it })
        assertEquals(1, loads)
        now = 86_400_000
        assertTrue(cache.isHoliday(date))
        assertEquals(2, loads)
    }

    @Test
    fun failedRefreshRetainsHolidayAndBacksOffForFiveMinutes() = runBlocking {
        var loads = 0
        var now = 0L
        val cache = TaiwanHolidayCache({
            loads++
            if (loads > 1) error("offline")
            mapOf(date to true)
        }, { now })
        assertTrue(cache.isHoliday(date))
        now = 86_400_000
        repeat(10) { assertTrue(cache.isHoliday(date)) }
        assertEquals(2, loads)
        now += 300_000
        assertTrue(cache.isHoliday(date))
        assertEquals(3, loads)
    }

    @Test
    fun weekendsDoNotRequestCalendarAndYearRolloverLoadsNewYear() = runBlocking {
        val years = mutableListOf<Int>()
        val newYear = LocalDate.of(2027, 1, 1)
        val cache = TaiwanHolidayCache({ year -> years += year; mapOf(date to false, newYear to true) })
        assertTrue(cache.isHoliday(LocalDate.of(2026, 10, 10)))
        assertTrue(years.isEmpty())
        assertFalse(cache.isHoliday(date))
        assertTrue(cache.isHoliday(newYear))
        assertEquals(listOf(2026, 2027), years)
    }

    @Test
    fun cancelledLoadPropagatesAndCanRetry() = runBlocking {
        var loads = 0
        val cache = TaiwanHolidayCache({
            if (++loads == 1) throw CancellationException("cancelled")
            mapOf(date to true)
        })
        try { cache.isHoliday(date); fail("Cancellation must propagate") } catch (_: CancellationException) { }
        assertTrue(cache.isHoliday(date))
    }

    @Test
    fun emptyOrMissingDayResponseIsNotCachedAsSuccessfulForOneDay() = runBlocking {
        var now = 0L
        var calls = 0
        val cache = TaiwanHolidayCache({ if (++calls == 1) emptyMap() else mapOf(date to true) }, { now })
        assertFalse(cache.isHoliday(date))
        assertFalse(cache.isHoliday(date))
        assertEquals(1, calls)
        now += 300_000
        assertTrue(cache.isHoliday(date))
    }
}
