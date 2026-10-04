package com.rsps1008.stockify

import com.rsps1008.stockify.data.PerformanceCalendarCalculationSupport
import com.rsps1008.stockify.ui.viewmodel.PersonalHistoryPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformanceCalendarCalculationSupportTest {
    @Test fun normalDailyGain() {
        assertEquals(1_000.0, daily(points(0.0, 1_000.0)).profitLoss, 0.001)
        assertEquals(1.0, daily(points(0.0, 1_000.0)).returnPercentage, 0.001)
    }

    @Test fun normalDailyLoss() {
        val item = daily(points(0.0, -500.0))
        assertEquals(-500.0, item.profitLoss, 0.001)
        assertEquals(-0.5, item.returnPercentage, 0.001)
    }

    @Test fun buyDoesNotBecomeProfit() {
        val item = daily(listOf(point("2026-08-03", 0.0, 0.0), point("2026-08-04", 100_000.0, 0.0)))
        assertEquals(0.0, item.profitLoss, 0.001)
    }

    @Test fun partialSellDoesNotBecomeLoss() {
        val item = daily(listOf(point("2026-08-03", 100_000.0, 2_000.0), point("2026-08-04", 50_000.0, 2_000.0)))
        assertEquals(0.0, item.profitLoss, 0.001)
    }

    @Test fun dividendIncluded() {
        assertEquals(500.0, daily(points(0.0, 500.0)).profitLoss, 0.001)
    }

    @Test fun dividendExcluded() {
        assertEquals(0.0, daily(points(0.0, 0.0)).profitLoss, 0.001)
    }

    @Test fun stockSplitKeepsValue() {
        assertEquals(0.0, daily(listOf(point("2026-08-03", 100_000.0, 0.0), point("2026-08-04", 100_000.0, 0.0))).profitLoss, 0.001)
    }

    @Test fun capitalReduction() {
        assertEquals(10_000.0, daily(listOf(point("2026-08-03", 100_000.0, 0.0), point("2026-08-04", 90_000.0, 10_000.0))).profitLoss, 0.001)
    }

    @Test fun zeroPreviousPortfolioValue() {
        assertEquals(0.0, daily(listOf(point("2026-08-03", 0.0, 0.0), point("2026-08-04", 100.0, 100.0))).returnPercentage, 0.001)
    }

    @Test fun monthlyReturnUsesCompounding() {
        assertEquals(2.01, PerformanceCalendarCalculationSupport.compoundReturns(listOf(1.0, 1.0)), 0.001)
    }

    @Test fun firstDayUsesPreviousTradingDay() {
        val month = PerformanceCalendarCalculationSupport.calculateMonthlyPerformance(
            listOf(point("2026-07-31", 100_000.0, 1_000.0), point("2026-08-03", 101_000.0, 2_000.0)), "2026-08"
        )!!
        assertEquals(1_000.0, month.dailyItems.single().profitLoss, 0.001)
        assertEquals(1.0, month.dailyItems.single().returnPercentage, 0.001)
    }

    @Test fun weekendHasNoPerformance() {
        val items = PerformanceCalendarCalculationSupport.calculateDailyPerformance(points(0.0, 1_000.0))
        assertTrue(items.none { it.date == "2026-08-02" })
    }

    @Test fun crossMonthCalculation() {
        val month = PerformanceCalendarCalculationSupport.calculateMonthlyPerformance(
            listOf(point("2026-08-31", 100_000.0, 0.0), point("2026-09-01", 101_000.0, 1_000.0)), "2026-09"
        )!!
        assertEquals(1_000.0, month.profitLoss, 0.001)
    }

    @Test fun crossYearCalculation() {
        val month = PerformanceCalendarCalculationSupport.calculateMonthlyPerformance(
            listOf(point("2026-12-31", 100_000.0, 0.0), point("2027-01-04", 101_000.0, 1_000.0)), "2027-01"
        )!!
        assertEquals(1_000.0, month.profitLoss, 0.001)
    }

    private fun daily(points: List<PersonalHistoryPoint>) =
        PerformanceCalendarCalculationSupport.calculateDailyPerformance(points).single()

    private fun points(previousPl: Double, todayPl: Double) = listOf(
        point("2026-08-03", 100_000.0, previousPl),
        point("2026-08-04", 100_000.0, todayPl)
    )

    private fun point(date: String, marketValue: Double, totalPl: Double) = PersonalHistoryPoint(
        date = date,
        price = 0.0,
        shares = 0.0,
        marketValue = marketValue,
        totalPL = totalPl,
        totalPLPercentage = 0.0
    )
}
