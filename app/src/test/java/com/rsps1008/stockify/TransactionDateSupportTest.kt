package com.rsps1008.stockify

import com.rsps1008.stockify.data.TransactionDateSupport
import com.rsps1008.stockify.data.HistoricalLongPositionTimeline
import com.rsps1008.stockify.data.StockTransaction
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransactionDateSupportTest {
    private val taipei = ZoneId.of("Asia/Taipei")
    private val newYork = ZoneId.of("America/New_York")

    @Test
    fun taipeiStoredUsTransactionDoesNotFallInsidePreviousUsTradingDate() {
        val transactionDate = LocalDate.of(2026, 8, 25)
            .atStartOfDay(taipei)
            .toInstant()
            .toEpochMilli()
        val previousDateCutoff = TransactionDateSupport.replayCutoffMillis(
            "2026-08-24",
            taipei
        )!!
        val selectedDateCutoff = TransactionDateSupport.replayCutoffMillis(
            "2026-08-25",
            taipei
        )!!

        assertTrue(transactionDate > previousDateCutoff)
        assertTrue(transactionDate <= selectedDateCutoff)

        val timeline = HistoricalLongPositionTimeline(
            listOf(
                StockTransaction(
                    stockCode = "AAPL",
                    market = "US",
                    date = transactionDate,
                    recordTime = transactionDate,
                    type = "買進",
                    buyShares = 1.0,
                    expense = 100.0
                )
            )
        )
        assertEquals(0.0, timeline.advanceTo(previousDateCutoff).shares, 0.0)
        assertEquals(1.0, timeline.advanceTo(selectedDateCutoff).shares, 0.0)
    }

    @Test
    fun transactionDateMovesToTheSameCalendarDateInTargetMarketZone() {
        val storedDate = LocalDate.of(2026, 8, 25)
            .atStartOfDay(taipei)
            .toInstant()
            .toEpochMilli()

        val mapped = TransactionDateSupport.moveToZoneDateStartMillis(
            transactionDateMillis = storedDate,
            targetZoneId = newYork,
            storageZoneId = taipei
        )

        assertEquals(
            LocalDate.of(2026, 8, 25),
            Instant.ofEpochMilli(mapped).atZone(newYork).toLocalDate()
        )
    }

    @Test
    fun replayCutoffRejectsInvalidIsoDate() {
        assertNull(TransactionDateSupport.replayCutoffMillis("2026-02-30", taipei))
    }

    @Test
    fun replayCutoffUsesTheActualEndOfDayAcrossDstChanges() {
        val cutoff = TransactionDateSupport.replayCutoffMillis(
            "2026-03-08",
            newYork
        )!!

        assertEquals(
            LocalDate.of(2026, 3, 9),
            Instant.ofEpochMilli(cutoff + 1L).atZone(newYork).toLocalDate()
        )
        assertEquals(
            23L * 60L * 60L * 1_000L,
            cutoff + 1L - LocalDate.of(2026, 3, 8)
                .atStartOfDay(newYork)
                .toInstant()
                .toEpochMilli()
        )
    }
}
