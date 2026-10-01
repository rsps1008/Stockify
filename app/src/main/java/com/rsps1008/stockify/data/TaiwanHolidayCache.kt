package com.rsps1008.stockify.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.DayOfWeek
import java.time.LocalDate

/** Keep the last usable calendar on failure; retry unavailable calendars at most every five minutes. */
internal class TaiwanHolidayCache(
    private val loadYear: suspend (Int) -> Map<LocalDate, Boolean>,
    private val nowMillis: () -> Long = System::currentTimeMillis
) {
    private val mutex = Mutex()
    private var cachedYear: Int? = null
    private var holidays: Map<LocalDate, Boolean> = emptyMap()
    private var refreshAfter = 0L

    suspend fun isHoliday(date: LocalDate): Boolean = mutex.withLock {
        if (date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY) {
            return@withLock true
        }
        val now = nowMillis()
        if (cachedYear != date.year || now >= refreshAfter) {
            if (cachedYear != date.year) {
                holidays = emptyMap()
                refreshAfter = 0L
            }
            cachedYear = date.year
            try {
                val loaded = loadYear(date.year)
                require(loaded.containsKey(date)) { "Calendar does not contain requested date" }
                holidays = loaded
                refreshAfter = now + 86_400_000L
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                refreshAfter = now + 300_000L
            }
        }
        holidays[date] ?: false
    }
}
