package com.rsps1008.stockify.data

import com.rsps1008.stockify.ui.viewmodel.PersonalHistoryPoint
import java.time.YearMonth

/** A daily portfolio result derived from the already replayed portfolio history. */
data class DailyPerformance(
    val date: String,
    val profitLoss: Double,
    val returnPercentage: Double,
    val portfolioValue: Double,
    val previousPortfolioValue: Double,
    val externalCashFlow: Double = 0.0
)

data class MonthlyPerformance(
    val yearMonth: String,
    val profitLoss: Double,
    val returnPercentage: Double,
    val dailyItems: List<DailyPerformance>
)

/**
 * Keeps the performance calendar on the exact same replayed values as the home
 * history chart.  It intentionally does not replay transactions or fetch prices.
 */
object PerformanceCalendarCalculationSupport {
    fun calculateDailyPerformance(points: List<PersonalHistoryPoint>): List<DailyPerformance> {
        val ordered = points
            .asSequence()
            .filter { it.date.length >= 10 }
            .sortedBy { it.date }
            .toList()

        return ordered.zipWithNext { previous, today ->
            val previousBase = previous.marketValue.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
            DailyPerformance(
                date = today.date,
                // totalPL already accounts for the transaction cash flows, dividends,
                // company actions, margin, and short positions in the history replay.
                profitLoss = (today.totalPL - previous.totalPL).finiteOrZero(),
                returnPercentage = if (previousBase > 0.0) {
                    (((today.totalPL - previous.totalPL) / previousBase) * 100.0).finiteOrZero()
                } else {
                    0.0
                },
                portfolioValue = today.marketValue.finiteOrZero(),
                previousPortfolioValue = previousBase
            )
        }
    }

    fun calculateMonthlyPerformance(
        points: List<PersonalHistoryPoint>,
        yearMonth: String
    ): MonthlyPerformance? {
        val normalizedMonth = runCatching { YearMonth.parse(yearMonth) }.getOrNull() ?: return null
        val ordered = points
            .asSequence()
            .filter { it.date.length >= 10 }
            .sortedBy { it.date }
            .toList()
        val firstIndex = ordered.indexOfFirst { it.date.startsWith("$normalizedMonth-") }
        if (firstIndex < 0) return null
        val lastIndex = ordered.indexOfLast { it.date.startsWith("$normalizedMonth-") }
        val previous = ordered.getOrNull(firstIndex - 1)
        val first = ordered[firstIndex]
        val last = ordered[lastIndex]
        val dailyItems = calculateDailyPerformance(ordered)
            .filter { it.date.startsWith("$normalizedMonth-") }

        return MonthlyPerformance(
            yearMonth = normalizedMonth.toString(),
            // The monthly amount is anchored to the point before the month rather
            // than summing rounded display values.
            profitLoss = ((last.totalPL - (previous?.totalPL ?: first.totalPL))).finiteOrZero(),
            returnPercentage = compoundReturns(dailyItems.map { it.returnPercentage }),
            dailyItems = dailyItems
        )
    }

    fun compoundReturns(returnPercentages: Iterable<Double>): Double {
        var factor = 1.0
        returnPercentages.forEach { percent ->
            if (percent.isFinite()) factor *= 1.0 + percent / 100.0
        }
        return ((factor - 1.0) * 100.0).finiteOrZero()
    }

    private fun Double.finiteOrZero(): Double = if (isFinite()) this else 0.0
}
