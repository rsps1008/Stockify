package com.rsps1008.stockify.data

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

data class ShortLotSummary(
    val lotId: String,
    val openedAt: Long,
    val annualRate: Double,
    val originalShares: Double,
    val remainingShares: Double,
    val originalPrincipal: Double,
    val accruedBorrowFee: Double
)

data class ShortSellingSummary(
    val outstandingShares: Double = 0.0,
    val accruedBorrowFee: Double = 0.0,
    val compensationExpense: Double = 0.0,
    val lots: List<ShortLotSummary> = emptyList(),
    val openedPrincipal: Double = 0.0
)

/** Replays short-sale lots. Rates are user-entered estimates, not broker settlement values. */
object ShortSellingCalculationSupport {
    private data class LotState(
        val id: String, val openedAt: Long, val annualRate: Double,
        val originalShares: Double, val originalPrincipal: Double,
        var remainingShares: Double, var accruedBorrowFee: Double, var lastAccrualDate: Long
    )

    fun calculate(transactions: List<StockTransaction>, valuationDate: Long, dayCount: Int = 365): ShortSellingSummary {
        val denominator = if (dayCount == 360) 360 else 365
        val lots = linkedMapOf<String, LotState>()
        var compensationExpense = 0.0
        fun accrue(lot: LotState, date: Long) {
            if (date <= lot.lastAccrualDate || lot.remainingShares <= 0.0) return
            val days = daysBetween(lot.lastAccrualDate, date)
            val principal = lot.originalPrincipal * lot.remainingShares / lot.originalShares
            lot.accruedBorrowFee += principal * lot.annualRate / 100.0 * days / denominator
            lot.lastAccrualDate = date
        }
        transactions.sortedWith(compareBy<StockTransaction> { it.date }.thenBy { it.recordTime }).forEach { tx ->
            lots.values.forEach { accrue(it, tx.date) }
            when (tx.type) {
                "融券賣出" -> {
                    val shares = tx.sellShares.coerceAtLeast(0.0)
                    val principal = tx.shortBorrowPrincipal.takeIf { it > 0.0 } ?: tx.sellPrice * shares
                    if (shares > 0.0) {
                        val id = tx.shortLotId.ifBlank { "legacy-short-${tx.id}" }
                        lots[id] = LotState(id, tx.date, tx.shortBorrowAnnualRate, shares, principal, shares, 0.0, tx.date)
                    }
                }
                "買券還券" -> if (tx.shortCoverLotId.isNotBlank() && tx.shortCoverShares > 0.0) {
                    lots[tx.shortCoverLotId]?.let { it.remainingShares = (it.remainingShares - tx.shortCoverShares).coerceAtLeast(0.0); it.lastAccrualDate = tx.date }
                }
                "融券補償" -> compensationExpense += tx.shortCompensation.coerceAtLeast(0.0)
            }
        }
        lots.values.forEach { accrue(it, valuationDate) }
        return ShortSellingSummary(
            outstandingShares = lots.values.sumOf { it.remainingShares },
            accruedBorrowFee = lots.values.sumOf { it.accruedBorrowFee },
            compensationExpense = compensationExpense,
            openedPrincipal = lots.values.sumOf { it.originalPrincipal },
            lots = lots.values.filter { it.remainingShares > 0.0 }.map {
                ShortLotSummary(it.id, it.openedAt, it.annualRate, it.originalShares, it.remainingShares, it.originalPrincipal, it.accruedBorrowFee)
            }
        )
    }

    private fun daysBetween(start: Long, end: Long): Long = ChronoUnit.DAYS.between(
        Instant.ofEpochMilli(start).atZone(ZoneId.systemDefault()).toLocalDate(),
        Instant.ofEpochMilli(end).atZone(ZoneId.systemDefault()).toLocalDate()
    ).coerceAtLeast(0L)
}
