package com.rsps1008.stockify.data

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

data class MarginLotSummary(
    val lotId: String,
    val openedAt: Long,
    val annualRate: Double,
    val originalPrincipal: Double,
    val remainingPrincipal: Double,
    val accruedInterest: Double
)

data class MarginSummary(
    val outstandingPrincipal: Double = 0.0,
    val accruedInterest: Double = 0.0,
    val lots: List<MarginLotSummary> = emptyList(),
    val selfFundedCapital: Double = 0.0,
    val cashBalance: Double = 0.0,
    val actualInterestPaid: Double = 0.0
) {
    val totalInterestExpense: Double get() = accruedInterest + actualInterestPaid
}

/** Pure replay of financing lots. It never changes share counts. */
object MarginCalculationSupport {
    private data class LotKey(val market: String, val stockCode: String, val accountId: Int, val lotId: String)

    private data class LotState(
        val id: String,
        val openedAt: Long,
        val annualRate: Double,
        val originalPrincipal: Double,
        var remainingPrincipal: Double,
        var accruedInterest: Double = 0.0,
        var actualInterestPaid: Double = 0.0,
        var lastAccrualDate: Long
    )

    fun calculate(
        transactions: List<StockTransaction>,
        valuationDate: Long,
        dayCount: Int = 365
    ): MarginSummary {
        val denominator = if (dayCount == 360) 360 else 365
        val lots = linkedMapOf<LotKey, LotState>()
        var selfFundedCapital = 0.0
        var cashBalance = 0.0

        fun accrueUntil(lot: LotState, date: Long) {
            if (date <= lot.lastAccrualDate || lot.remainingPrincipal <= 0.0) return
            val days = daysBetween(lot.lastAccrualDate, date)
            lot.accruedInterest += lot.remainingPrincipal * lot.annualRate / 100.0 * days / denominator
            lot.lastAccrualDate = date
        }

        transactions.asSequence()
            .filter { it.date <= valuationDate }
            .sortedWith(compareBy<StockTransaction> { it.date }.thenBy { it.recordTime }.thenBy { it.id })
            .forEach { tx ->
            lots.values.forEach { accrueUntil(it, tx.date) }
            when (tx.type) {
                "融資買進" -> {
                    val id = tx.marginLotId.ifBlank { "legacy-${tx.id}" }
                    val principal = tx.marginPrincipal.coerceAtLeast(0.0)
                    if (principal > 0.0) {
                        lots[tx.toLotKey(id)] = LotState(
                            id,
                            tx.date,
                            tx.marginAnnualRate,
                            principal,
                            principal,
                            lastAccrualDate = tx.date
                        )
                    }
                    val selfFunded = if (tx.marginSelfFundedOverridden) {
                        tx.marginSelfFunded
                    } else {
                        tx.expense - principal
                    }
                    cashBalance -= selfFunded
                    selfFundedCapital += selfFunded.coerceAtLeast(0.0)
                }
                "買進" -> {
                    cashBalance -= tx.expense
                    selfFundedCapital += tx.expense
                }
                "賣出" -> cashBalance += tx.income - tx.marginRepayment.coerceAtLeast(0.0) - tx.marginActualInterest.coerceAtLeast(0.0)
                "配息" -> cashBalance += HoldingCalculationSupport.resolveDividendIncome(tx)
                "減資" -> cashBalance += tx.cashReturned
            }
            val isMarginRepayment = tx.marginRepaymentLotId.isNotBlank() && (tx.marginRepayment > 0.0 || tx.marginActualInterest > 0.0)
            if (isMarginRepayment) {
                lots[tx.toLotKey(tx.marginRepaymentLotId)]?.let { lot ->
                    accrueUntil(lot, tx.date)
                    val principalBeforeRepayment = lot.remainingPrincipal
                    if (tx.marginActualInterest > 0.0) {
                        lot.actualInterestPaid += tx.marginActualInterest
                        lot.accruedInterest = when {
                            // Interest-only payments settle all interest accrued to this date.
                            tx.marginRepayment <= 0.0 -> 0.0
                            principalBeforeRepayment <= 0.0 -> lot.accruedInterest
                            else -> {
                                // A partial repayment settles only the proportional interest
                                // attached to the repaid principal.
                                val repaidRatio = (tx.marginRepayment / principalBeforeRepayment)
                                    .coerceIn(0.0, 1.0)
                                lot.accruedInterest * (1.0 - repaidRatio)
                            }
                        }
                    }
                    if (tx.marginRepayment > 0.0) {
                        lot.remainingPrincipal = (lot.remainingPrincipal - tx.marginRepayment).coerceAtLeast(0.0)
                    }
                    lot.lastAccrualDate = tx.date
                }
                if (tx.type == "融資還款") cashBalance -= tx.marginRepayment + tx.marginActualInterest
            } else if (tx.marginActualInterest > 0.0 && tx.type != "賣出") {
                cashBalance -= tx.marginActualInterest
            }
        }

        lots.values.forEach { accrueUntil(it, valuationDate) }
        val activeLots = lots.values.filter { it.remainingPrincipal > 0.0 || it.accruedInterest > 0.0 }
        return MarginSummary(
            outstandingPrincipal = lots.values.sumOf { it.remainingPrincipal },
            accruedInterest = lots.values.sumOf { it.accruedInterest },
            lots = activeLots.map {
                MarginLotSummary(it.id, it.openedAt, it.annualRate, it.originalPrincipal, it.remainingPrincipal, it.accruedInterest)
            },
            selfFundedCapital = selfFundedCapital,
            cashBalance = cashBalance,
            actualInterestPaid = lots.values.sumOf { it.actualInterestPaid }
        )
    }

    /**
     * Validates every repayment in chronological order without clamping an
     * overpayment. This is intentionally separate from [calculate], whose
     * tolerant replay is kept for legacy/imported records.
     */
    fun hasValidRepaymentBalances(transactions: List<StockTransaction>): Boolean {
        val remainingByLot = mutableMapOf<LotKey, Double>()

        transactions
            .sortedWith(compareBy<StockTransaction> { it.date }.thenBy { it.recordTime }.thenBy { it.id })
            .forEach { transaction ->
                if (transaction.type == "融資買進") {
                    val lotId = transaction.marginLotId.ifBlank { "legacy-${transaction.id}" }
                    remainingByLot[transaction.toLotKey(lotId)] = transaction.marginPrincipal.coerceAtLeast(0.0)
                }

                val hasMarginPayment = transaction.marginRepayment > 0.0 || transaction.marginActualInterest > 0.0
                if (hasMarginPayment) {
                    val lotKey = transaction.toLotKey(transaction.marginRepaymentLotId)
                    val remaining = remainingByLot[lotKey] ?: return false
                    if (transaction.marginRepayment > remaining + BALANCE_EPSILON) return false
                    if (transaction.marginRepayment > 0.0) {
                        remainingByLot[lotKey] = (remaining - transaction.marginRepayment).coerceAtLeast(0.0)
                    }
                }
            }

        return true
    }

    private fun StockTransaction.toLotKey(lotId: String): LotKey =
        LotKey(market = StockMarket.normalize(market), stockCode = stockCode, accountId = accountId, lotId = lotId)

    private fun daysBetween(start: Long, end: Long): Long {
        val zone = ZoneId.systemDefault()
        val startDate = Instant.ofEpochMilli(start).atZone(zone).toLocalDate()
        val endDate = Instant.ofEpochMilli(end).atZone(zone).toLocalDate()
        return ChronoUnit.DAYS.between(startDate, endDate).coerceAtLeast(0L)
    }

    private const val BALANCE_EPSILON = 1e-6
}
