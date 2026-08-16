package com.rsps1008.stockify.data.dividend

import com.rsps1008.stockify.ui.viewmodel.getDividendFetchDateString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class DividendInfoCacheTest {

    @Test
    fun getDividendFetchDateString_formatsDateCorrectly() {
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(2026, Calendar.AUGUST, 16, 12, 0, 0)
        }
        val dateString = getDividendFetchDateString(calendar.timeInMillis)
        // Ensure format is yyyy/MM/dd
        assertEquals(10, dateString.length)
        assertEquals('/', dateString[4])
        assertEquals('/', dateString[7])
    }

    @Test
    fun dividendInfoCacheEntry_serialization_supportsLastFetchedDate() {
        val entry = DividendInfoCacheEntry(
            cashDividend = 2.5,
            cashDividendDate = "2026/07/01",
            stockDividend = 0.5,
            stockDividendDate = "2026/07/01",
            lastLocalAccountId = 1,
            lastFetchedDate = "2026/08/16"
        )
        val json = Json.encodeToString(entry)
        val decoded = Json.decodeFromString<DividendInfoCacheEntry>(json)

        assertEquals("2026/08/16", decoded.lastFetchedDate)
        assertEquals(2.5, decoded.cashDividend ?: 0.0, 0.001)
    }

    @Test
    fun dividendInfoCacheEntry_deserialization_handlesMissingLastFetchedDateGracefully() {
        val legacyJson = """
            {
                "cashDividend": 1.8,
                "cashDividendDate": "2026/06/15",
                "lastLocalAccountId": 1
            }
        """.trimIndent()
        val decoded = Json.decodeFromString<DividendInfoCacheEntry>(legacyJson)

        assertNull(decoded.lastFetchedDate)
        assertEquals(1.8, decoded.cashDividend ?: 0.0, 0.001)
    }

    @Test
    fun dividendInfoCacheEntry_dateComparison_distinguishesDifferentDays() {
        val day1 = getDividendFetchDateString(1786800000000L)
        val day2 = getDividendFetchDateString(1786800000000L + 86400000L)

        assertNotEquals(day1, day2)
    }
}
