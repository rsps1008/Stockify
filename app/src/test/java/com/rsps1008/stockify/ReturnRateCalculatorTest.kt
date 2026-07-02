package com.rsps1008.stockify

import com.rsps1008.stockify.data.CashFlow
import com.rsps1008.stockify.data.ReturnRateCalculator
import com.rsps1008.stockify.data.ReturnRateMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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
    fun returnRateMode_normalizeFallsBackToRemainingPosition() {
        assertEquals(ReturnRateMode.REMAINING_POSITION, ReturnRateMode.normalize(null))
        assertEquals(ReturnRateMode.XIRR, ReturnRateMode.normalize("XIRR"))
    }
}
