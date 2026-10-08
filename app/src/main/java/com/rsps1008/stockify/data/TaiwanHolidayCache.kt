package com.rsps1008.stockify.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.DayOfWeek
import java.time.LocalDate

/** Keep each year's calendar for the process lifetime; retry only until that year is available. */
internal class TaiwanHolidayCache(
    private val loadYear: suspend (Int) -> Map<LocalDate, Boolean>,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val readSavedYear: suspend (Int) -> Map<LocalDate, Boolean>? = { null },
    private val saveYear: suspend (Int, Map<LocalDate, Boolean>) -> Unit = { _, _ -> }
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
            val saved = try {
                readSavedYear(date.year)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }?.takeIf { calendar ->
                calendar.isNotEmpty() && calendar.keys.all { it.year == date.year }
            }
            if (saved != null) {
                holidays = saved
                refreshAfter = Long.MAX_VALUE
                return@withLock holidays[date] ?: false
            }
            try {
                val loaded = loadYear(date.year)
                require(loaded.containsKey(date)) { "Calendar does not contain requested date" }
                holidays = loaded
                try {
                    saveYear(date.year, loaded)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Keep the validated calendar usable in memory even if persistence fails.
                }
                refreshAfter = Long.MAX_VALUE
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                refreshAfter = now + 300_000L
            }
        }
        holidays[date] ?: false
    }
}
