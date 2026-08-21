package com.rsps1008.stockify

import com.rsps1008.stockify.data.CashFlow
import com.rsps1008.stockify.data.ReturnRateCalculator
import com.rsps1008.stockify.data.ReturnRateMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.pow

class ReturnRateCalculatorTest {

    @Test
    fun xirr_returnsTenPercentForOneYearGain() {
        val start = 1_700_000_000_000L
        val flows = listOf(
            CashFlow(start, -1_000.0),
            CashFlow(start + 365L * 86_400_000L, 1_100.0)
        )

        val result = ReturnRateCalculator.calculateXirrPercentage(flows)

        assertEquals(10.0, result!!, 0.05)
    }

    @Test
    fun xirr_returnsFivePercentForTwoYearGain() {
        val start = 1_700_000_000_000L
        val flows = listOf(
            CashFlow(start, -1_000.0),
            CashFlow(start + 730L * 86_400_000L, 1_102.5)
        )

        val result = ReturnRateCalculator.calculateXirrPercentage(flows)

        assertEquals(5.0, result!!, 0.05)
    }

    @Test
    fun xirr_returnsNullWithoutBothPositiveAndNegativeCashFlows() {
        val start = 1_700_000_000_000L
        val flows = listOf(
            CashFlow(start, -1_000.0)
        )

        assertNull(ReturnRateCalculator.calculateXirrPercentage(flows))
    }

    @Test
    fun xirrUsesCalendarDaysAcrossDaylightSavingTime() {
        val zone = ZoneId.of("America/New_York")
        val start = LocalDate.of(2024, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = LocalDate.of(2025, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        val expectedRate = 1.1.pow(365.0 / 366.0) - 1.0

        val result = ReturnRateCalculator.calculateXirrRate(
            listOf(CashFlow(start, -1_000.0), CashFlow(end, 1_100.0)),
            zoneId = zone
        )

        assertEquals(expectedRate, result!!, 1e-8)
    }

    @Test
    fun returnRateMode_normalizeFallsBackToRemainingPosition() {
        assertEquals(ReturnRateMode.REMAINING_POSITION, ReturnRateMode.normalize(null))
        assertEquals(ReturnRateMode.XIRR, ReturnRateMode.normalize("XIRR"))
    }
}
