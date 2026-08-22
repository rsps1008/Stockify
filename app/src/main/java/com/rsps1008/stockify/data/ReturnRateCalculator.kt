package com.rsps1008.stockify.data

import kotlin.math.abs
import kotlin.math.pow
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

data class CashFlow(
    val dateMillis: Long,
    val amount: Double
)

object ReturnRateCalculator {
    private data class PreparedCashFlow(
        val years: Double,
        val amount: Double
    )

    private const val DAYS_PER_YEAR = 365.0
    private const val EPSILON = 1e-10
    private const val DEFAULT_GUESS = 0.1

    fun calculateXirrRate(
        cashFlows: List<CashFlow>,
        guess: Double = DEFAULT_GUESS,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): Double? {
        val flows = cashFlows
            .filter { abs(it.amount) > EPSILON }
            .sortedBy { it.dateMillis }

        if (flows.size < 2) return null
        if (flows.none { it.amount > 0.0 } || flows.none { it.amount < 0.0 }) return null

        val baseDate = flows.first().dateMillis
        val baseLocalDate = Instant.ofEpochMilli(baseDate).atZone(zoneId).toLocalDate()
        val preparedFlows = flows.map { flow ->
            PreparedCashFlow(
                years = ChronoUnit.DAYS.between(
                    baseLocalDate,
                    Instant.ofEpochMilli(flow.dateMillis).atZone(zoneId).toLocalDate()
                ).toDouble() / DAYS_PER_YEAR,
                amount = flow.amount
            )
        }
        val xirr = solveXirr(preparedFlows, guess) ?: return null
        return xirr
    }

    fun calculateXirrPercentage(
        cashFlows: List<CashFlow>,
        guess: Double = DEFAULT_GUESS,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): Double? {
        val xirr = calculateXirrRate(cashFlows, guess, zoneId) ?: return null
        return xirr * 100.0
    }

    private fun solveXirr(
        flows: List<PreparedCashFlow>,
        guess: Double
    ): Double? {
        val newton = solveWithNewton(flows, guess)
        if (newton != null) return newton
        return solveWithBisection(flows)
    }

    private fun solveWithNewton(
        flows: List<PreparedCashFlow>,
        guess: Double
    ): Double? {
        var rate = guess.takeIf { it.isFinite() && it > -0.999999999 } ?: DEFAULT_GUESS
        repeat(100) {
            val value = xnpv(rate, flows)
            if (!value.isFinite()) return null
            if (abs(value) < 1e-8) return rate

            val derivative = xnpvDerivative(rate, flows)
            if (!derivative.isFinite() || abs(derivative) < EPSILON) return null

            val nextRate = rate - (value / derivative)
            if (!nextRate.isFinite() || nextRate <= -0.999999999) return null

            if (abs(nextRate - rate) < 1e-10) return nextRate
            rate = nextRate
        }
        return null
    }

    private fun solveWithBisection(flows: List<PreparedCashFlow>): Double? {
        var lower = -0.999999999
        var upper = 0.1
        var lowerValue = xnpv(lower, flows)
        var upperValue = xnpv(upper, flows)

        var expandCount = 0
        while (lowerValue.isFinite() && upperValue.isFinite() && lowerValue * upperValue > 0.0 && expandCount < 80) {
            upper = if (upper < 1.0) upper + 1.0 else upper * 2.0
            upperValue = xnpv(upper, flows)
            expandCount++
        }

        if (!lowerValue.isFinite() || !upperValue.isFinite() || lowerValue * upperValue > 0.0) return null

        repeat(200) {
            val mid = (lower + upper) / 2.0
            val midValue = xnpv(mid, flows)
            if (!midValue.isFinite()) return null
            if (abs(midValue) < 1e-8 || abs(upper - lower) < 1e-10) return mid

            if (lowerValue * midValue <= 0.0) {
                upper = mid
                upperValue = midValue
            } else {
                lower = mid
                lowerValue = midValue
            }
        }

        return (lower + upper) / 2.0
    }

    private fun xnpv(rate: Double, flows: List<PreparedCashFlow>): Double {
        if (rate <= -1.0) return Double.NaN
        return flows.sumOf { flow ->
            flow.amount / (1.0 + rate).pow(flow.years)
        }
    }

    private fun xnpvDerivative(
        rate: Double,
        flows: List<PreparedCashFlow>
    ): Double {
        if (rate <= -1.0) return Double.NaN
        return flows.sumOf { flow ->
            if (flow.years == 0.0) 0.0 else {
                (-flow.years * flow.amount) / (1.0 + rate).pow(flow.years + 1.0)
            }
        }
    }
}
