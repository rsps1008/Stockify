package com.rsps1008.stockify.data

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs

data class ProfitLossBreakdown(
    val realizedProfitLoss: Double,
    val unrealizedProfitLoss: Double,
    val realizedCost: Double,
    val unrealizedCost: Double,
    val soldShares: Double,
    val coveredShortShares: Double,
    val realizedBuyAverage: Double,
    val realizedSellAverage: Double,
    val unrealizedAverageCost: Double,
    val hasRealizedActivity: Boolean
) {
    val realizedPercentage: Double
        get() = if (realizedCost > POSITION_EPSILON) realizedProfitLoss / realizedCost * 100.0 else 0.0

    val unrealizedPercentage: Double
        get() = if (unrealizedCost > POSITION_EPSILON) unrealizedProfitLoss / unrealizedCost * 100.0 else 0.0

    private companion object {
        const val POSITION_EPSILON = 1e-6
    }
}

/**
 * Optional home-screen decomposition of the existing cumulative P/L.
 *
 * The repository remains the owner of the legacy cumulative result. This replay
 * calculates the realized slice, then assigns the exact remainder of that
 * legacy result to the unrealized slice so enabling the display preference can
 * never change the portfolio total.
 */
object ProfitLossBreakdownSupport {
    private data class PositionKey(val market: String, val stockCode: String, val accountId: Int)

    private data class LongState(
        var shares: Double = 0.0,
        var cost: Double = 0.0
    )

    private data class ShortLotKey(
        val market: String,
        val stockCode: String,
        val accountId: Int,
        val lotId: String
    )

    private data class ShortLotState(
        var originalShares: Double,
        var remainingShares: Double,
        val originalPrincipal: Double,
        val openingIncome: Double,
        val openingGross: Double,
        val annualRate: Double,
        var accruedFee: Double,
        var lastAccrualDate: Long
    )

    fun calculate(
        transactions: List<StockTransaction>,
        valuationDate: Long,
        legacyTotalProfitLoss: Double,
        marginSummary: MarginSummary,
        marginDayCount: Int,
        includeDividendIncome: Boolean = true
    ): ProfitLossBreakdown {
        val orderedTransactions = HoldingCalculationSupport.transactionsAtOrBefore(transactions, valuationDate)
        val longStates = mutableMapOf<PositionKey, LongState>()
        val shortLots = linkedMapOf<ShortLotKey, ShortLotState>()
        val denominator = if (marginDayCount == 360) 360 else 365

        var realizedProfitLoss = 0.0
        var realizedCost = 0.0
        var soldShares = 0.0
        var coveredShortShares = 0.0
        var realizedBuyAmount = 0.0
        var realizedSellAmount = 0.0
        var hasRealizedActivity = false

        fun accrueShortFee(lot: ShortLotState, date: Long) {
            if (date <= lot.lastAccrualDate || lot.remainingShares <= POSITION_EPSILON || lot.originalShares <= POSITION_EPSILON) {
                return
            }
            val days = daysBetween(lot.lastAccrualDate, date)
            val remainingPrincipal = lot.originalPrincipal * lot.remainingShares / lot.originalShares
            lot.accruedFee += remainingPrincipal * lot.annualRate / 100.0 * days / denominator
            lot.lastAccrualDate = date
        }

        orderedTransactions.forEach { transaction ->
            shortLots.values.forEach { accrueShortFee(it, transaction.date) }

            val positionKey = transaction.positionKey()
            val longState = longStates.getOrPut(positionKey) { LongState() }
            when (transaction.type) {
                "買進", "融資買進" -> {
                    longState.shares += transaction.buyShares.coerceAtLeast(0.0)
                    longState.cost += transaction.expense
                }

                "賣出" -> {
                    val requestedShares = transaction.sellShares.coerceAtLeast(0.0)
                    val disposedShares = requestedShares.coerceAtMost(longState.shares.coerceAtLeast(0.0))
                    val averageCost = if (longState.shares > POSITION_EPSILON) longState.cost / longState.shares else 0.0
                    val disposedCost = averageCost * disposedShares
                    val allocatedIncome = if (requestedShares > POSITION_EPSILON) {
                        transaction.income * disposedShares / requestedShares
                    } else {
                        0.0
                    }
                    val allocatedGross = transaction.sellPrice * disposedShares

                    realizedProfitLoss += allocatedIncome - disposedCost
                    realizedCost += disposedCost
                    realizedBuyAmount += disposedCost
                    realizedSellAmount += allocatedGross
                    soldShares += disposedShares
                    longState.shares = (longState.shares - disposedShares).coerceAtLeast(0.0)
                    longState.cost = (longState.cost - disposedCost).coerceAtLeast(0.0)
                    hasRealizedActivity = hasRealizedActivity || requestedShares > POSITION_EPSILON || abs(transaction.income) > POSITION_EPSILON
                }

                "配股" -> longState.shares += transaction.dividendShares

                "配息" -> {
                    if (includeDividendIncome) {
                        val dividendIncome = HoldingCalculationSupport.resolveDividendIncome(transaction)
                        realizedProfitLoss += dividendIncome
                        hasRealizedActivity = hasRealizedActivity || abs(dividendIncome) > POSITION_EPSILON
                    }
                }

                "分割", "減資" -> {
                    val shareChange = if (transaction.type == "分割") {
                        HoldingCalculationSupport.splitShareChange(transaction, longState.shares)
                    } else {
                        HoldingCalculationSupport.capitalReductionShareChange(transaction, longState.shares)
                    }
                    if (transaction.type == "減資" &&
                        transaction.cashReturned > POSITION_EPSILON &&
                        shareChange < -POSITION_EPSILON
                    ) {
                        val removedShares = (-shareChange).coerceAtMost(longState.shares)
                        val averageCost = if (longState.shares > POSITION_EPSILON) longState.cost / longState.shares else 0.0
                        val removedCost = averageCost * removedShares
                        realizedProfitLoss += transaction.cashReturned - removedCost
                        realizedCost += removedCost
                        longState.cost = (longState.cost - removedCost).coerceAtLeast(0.0)
                        hasRealizedActivity = true
                    } else if (transaction.type == "減資" && transaction.cashReturned > POSITION_EPSILON) {
                        realizedProfitLoss += transaction.cashReturned
                        hasRealizedActivity = true
                    }
                    longState.shares = (longState.shares + shareChange).coerceAtLeast(0.0)

                    val factor = when (transaction.type) {
                        "分割" -> HoldingCalculationSupport.splitShareFactor(transaction)
                        else -> HoldingCalculationSupport.capitalReductionShareFactor(transaction)
                    }
                    if (factor > 0.0 && factor != 1.0) {
                        shortLots.forEach { (key, lot) ->
                            if (key.matches(transaction)) {
                                lot.originalShares *= factor
                                lot.remainingShares *= factor
                            }
                        }
                    }
                }

                "融券賣出" -> {
                    val shares = transaction.sellShares.coerceAtLeast(0.0)
                    if (shares > POSITION_EPSILON) {
                        val lotId = transaction.shortLotId.ifBlank { "legacy-short-${transaction.id}" }
                        shortLots[transaction.shortLotKey(lotId)] = ShortLotState(
                            originalShares = shares,
                            remainingShares = shares,
                            originalPrincipal = transaction.shortBorrowPrincipal.takeIf { it > 0.0 }
                                ?: transaction.sellPrice * shares,
                            openingIncome = transaction.income,
                            openingGross = transaction.sellPrice * shares,
                            annualRate = transaction.shortBorrowAnnualRate,
                            accruedFee = 0.0,
                            lastAccrualDate = transaction.date
                        )
                    }
                }

                "買券還券" -> {
                    val requestedShares = transaction.shortCoverShares.coerceAtLeast(0.0)
                    val lot = shortLots[transaction.shortLotKey(transaction.shortCoverLotId)]
                    if (lot != null && requestedShares > POSITION_EPSILON && lot.remainingShares > POSITION_EPSILON) {
                        val remainingBefore = lot.remainingShares
                        val coveredShares = requestedShares.coerceAtMost(remainingBefore)
                        val originalRatio = coveredShares / lot.originalShares
                        val requestRatio = coveredShares / requestedShares
                        val feeAllocation = lot.accruedFee * coveredShares / remainingBefore
                        val openingIncome = lot.openingIncome * originalRatio
                        val openingGross = lot.openingGross * originalRatio
                        val coverExpense = transaction.expense * requestRatio
                        val coveredPrincipal = lot.originalPrincipal * originalRatio

                        realizedProfitLoss += openingIncome - coverExpense - feeAllocation
                        realizedCost += coveredPrincipal
                        realizedBuyAmount += coverExpense
                        realizedSellAmount += openingGross
                        coveredShortShares += coveredShares
                        lot.remainingShares = (remainingBefore - coveredShares).coerceAtLeast(0.0)
                        lot.accruedFee = (lot.accruedFee - feeAllocation).coerceAtLeast(0.0)
                        lot.lastAccrualDate = transaction.date
                        hasRealizedActivity = true
                    }
                }

                "融券補償" -> if (transaction.shortCompensation > 0.0) {
                    realizedProfitLoss -= transaction.shortCompensation
                    hasRealizedActivity = true
                }
            }
        }

        shortLots.values.forEach { accrueShortFee(it, valuationDate) }
        if (marginSummary.actualInterestPaid > POSITION_EPSILON) {
            realizedProfitLoss -= marginSummary.actualInterestPaid
            hasRealizedActivity = true
        }

        val remainingLongCost = longStates.values.sumOf { it.cost.coerceAtLeast(0.0) }
        val remainingLongShares = longStates.values.sumOf { it.shares.coerceAtLeast(0.0) }
        val remainingShortPrincipal = shortLots.values.sumOf { lot ->
            if (lot.originalShares > POSITION_EPSILON) {
                lot.originalPrincipal * lot.remainingShares / lot.originalShares
            } else {
                0.0
            }
        }
        val unrealizedCost = remainingLongCost + remainingShortPrincipal
        val totalRealizedShares = soldShares + coveredShortShares

        return ProfitLossBreakdown(
            realizedProfitLoss = realizedProfitLoss,
            unrealizedProfitLoss = legacyTotalProfitLoss - realizedProfitLoss,
            realizedCost = realizedCost,
            unrealizedCost = unrealizedCost,
            soldShares = soldShares,
            coveredShortShares = coveredShortShares,
            realizedBuyAverage = if (totalRealizedShares > POSITION_EPSILON) realizedBuyAmount / totalRealizedShares else 0.0,
            realizedSellAverage = if (totalRealizedShares > POSITION_EPSILON) realizedSellAmount / totalRealizedShares else 0.0,
            unrealizedAverageCost = if (remainingLongShares > POSITION_EPSILON) remainingLongCost / remainingLongShares else 0.0,
            hasRealizedActivity = hasRealizedActivity
        )
    }

    private fun StockTransaction.positionKey(): PositionKey = PositionKey(
        market = StockMarket.normalize(market),
        stockCode = stockCode,
        accountId = accountId
    )

    private fun StockTransaction.shortLotKey(lotId: String): ShortLotKey = ShortLotKey(
        market = StockMarket.normalize(market),
        stockCode = stockCode,
        accountId = accountId,
        lotId = lotId
    )

    private fun ShortLotKey.matches(transaction: StockTransaction): Boolean =
        market == StockMarket.normalize(transaction.market) &&
            stockCode == transaction.stockCode &&
            accountId == transaction.accountId

    private fun daysBetween(start: Long, end: Long): Long {
        val zone = ZoneId.systemDefault()
        val startDate = Instant.ofEpochMilli(start).atZone(zone).toLocalDate()
        val endDate = Instant.ofEpochMilli(end).atZone(zone).toLocalDate()
        return ChronoUnit.DAYS.between(startDate, endDate).coerceAtLeast(0L)
    }

    private const val POSITION_EPSILON = 1e-6
}
