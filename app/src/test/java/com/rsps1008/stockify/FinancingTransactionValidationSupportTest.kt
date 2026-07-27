package com.rsps1008.stockify

import com.rsps1008.stockify.data.FinancingTransactionValidationSupport
import com.rsps1008.stockify.data.StockTransaction
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class FinancingTransactionValidationSupportTest {
    @Test
    fun validMarginAndShortTransactionsPassCoreValidation() {
        val transactions = listOf(
            marginLoan("margin-1"),
            StockTransaction(
                stockCode = "2330", date = 2L, recordTime = 2L, type = "融資還款",
                marginRepaymentLotId = "margin-1", marginRepayment = 10_000.0,
                expense = 10_000.0
            ),
            shortSale("short-1"),
            StockTransaction(
                stockCode = "2330", date = 4L, recordTime = 4L, type = "買券還券",
                buyPrice = 90.0, buyShares = 100.0, shortCoverLotId = "short-1",
                shortCoverShares = 100.0, expense = 9_010.0
            )
        )

        assertNull(FinancingTransactionValidationSupport.validate(transactions))
    }

    @Test
    fun negativeAndNonFiniteRatesAreRejected() {
        assertNotNull(
            FinancingTransactionValidationSupport.validate(
                listOf(marginLoan("margin-1").copy(marginAnnualRate = -1.0))
            )
        )
        assertNotNull(
            FinancingTransactionValidationSupport.validate(
                listOf(shortSale("short-1").copy(shortBorrowAnnualRate = Double.NaN))
            )
        )
    }

    @Test
    fun duplicateOpeningLotIdsAreRejected() {
        assertNotNull(
            FinancingTransactionValidationSupport.validate(
                listOf(marginLoan("same"), marginLoan("same").copy(date = 2L, recordTime = 2L))
            )
        )
        assertNotNull(
            FinancingTransactionValidationSupport.validate(
                listOf(shortSale("same"), shortSale("same").copy(date = 4L, recordTime = 4L))
            )
        )
    }

    @Test
    fun identicalLotIdsInSeparateStockOrAccountAreAllowed() {
        val firstAccount = marginLoan("shared")
        val secondAccount = marginLoan("shared").copy(
            accountId = 2,
            date = 2L,
            recordTime = 2L
        )
        val otherStock = marginLoan("shared").copy(
            stockCode = "0050",
            date = 3L,
            recordTime = 3L
        )

        assertNull(
            FinancingTransactionValidationSupport.validate(
                listOf(firstAccount, secondAccount, otherStock)
            )
        )
    }

    @Test
    fun invalidRepaymentAndCompensationAmountsAreRejected() {
        assertNotNull(
            FinancingTransactionValidationSupport.validate(
                listOf(
                    marginLoan("margin-1"),
                    StockTransaction(
                        stockCode = "2330", date = 2L, recordTime = 2L, type = "融資還款",
                        marginRepaymentLotId = "margin-1", marginRepayment = -1.0
                    )
                )
            )
        )
        assertNotNull(
            FinancingTransactionValidationSupport.validate(
                listOf(
                    shortSale("short-1"),
                    StockTransaction(
                        stockCode = "2330", date = 4L, recordTime = 4L, type = "融券補償",
                        shortCompensationLotId = "short-1", shortCompensation = -1.0
                    )
                )
            )
        )
    }

    @Test
    fun financingFieldsOnAnUnrelatedTransactionTypeAreRejected() {
        val transaction = StockTransaction(
            stockCode = "2330",
            date = 1L,
            recordTime = 1L,
            type = "買進",
            buyPrice = 100.0,
            buyShares = 1_000.0,
            expense = 100_000.0,
            marginAnnualRate = 6.5
        )

        assertNotNull(FinancingTransactionValidationSupport.validate(listOf(transaction)))
    }

    @Test
    fun normalSellWithMarginRepaymentIsValidatedAsFinancing() {
        val transaction = StockTransaction(
            stockCode = "2330",
            date = 2L,
            recordTime = 2L,
            type = "賣出",
            sellPrice = 100.0,
            sellShares = 500.0,
            income = 49_800.0,
            marginRepaymentLotId = "margin-1",
            marginRepayment = 40_000.0
        )

        assertNull(FinancingTransactionValidationSupport.validate(listOf(marginLoan("margin-1"), transaction)))
    }

    @Test
    fun oneTransactionCannotOpenAndCloseFinancingLotsAtTheSameTime() {
        assertNotNull(
            FinancingTransactionValidationSupport.validate(
                listOf(
                    marginLoan("margin-1").copy(
                        marginRepaymentLotId = "margin-1",
                        marginRepayment = 10_000.0
                    )
                )
            )
        )
        assertNotNull(
            FinancingTransactionValidationSupport.validate(
                listOf(
                    shortSale("short-1").copy(
                        shortCoverLotId = "short-1",
                        shortCoverShares = 100.0
                    )
                )
            )
        )
    }

    @Test
    fun repaymentAndCompensationExpensesMustMatchTheirCashOutflows() {
        val invalidRepayment = StockTransaction(
            stockCode = "2330",
            date = 2L,
            recordTime = 2L,
            type = "融資還款",
            marginRepaymentLotId = "margin-1",
            marginRepayment = 10_000.0,
            marginActualInterest = 100.0,
            expense = 10_000.0
        )
        val invalidCompensation = StockTransaction(
            stockCode = "2330",
            date = 4L,
            recordTime = 4L,
            type = "融券補償",
            shortCompensationLotId = "short-1",
            shortCompensation = 500.0,
            expense = 0.0
        )

        assertNotNull(
            FinancingTransactionValidationSupport.validate(listOf(marginLoan("margin-1"), invalidRepayment))
        )
        assertNotNull(
            FinancingTransactionValidationSupport.validate(listOf(shortSale("short-1"), invalidCompensation))
        )
    }

    @Test
    fun negativeFeesAndMissingShortSaleIncomeAreRejected() {
        assertNotNull(
            FinancingTransactionValidationSupport.validate(
                listOf(marginLoan("margin-1").copy(fee = -1.0))
            )
        )
        assertNotNull(
            FinancingTransactionValidationSupport.validate(
                listOf(shortSale("short-1").copy(income = 0.0))
            )
        )
    }

    @Test
    fun shortSalePrincipalMustMatchTheEnteredPriceAndShares() {
        assertNotNull(
            FinancingTransactionValidationSupport.validate(
                listOf(shortSale("short-1").copy(shortBorrowPrincipal = 99_000.0))
            )
        )
    }

    @Test
    fun financingCannotTreatAnAmericanTickerAsTaiwanStock() {
        val americanTicker = shortSale("short-1").copy(stockCode = "AAPL")

        assertNotNull(
            FinancingTransactionValidationSupport.validateFinancingMarket(americanTicker, "TW")
        )
        assertNull(
            FinancingTransactionValidationSupport.validateFinancingMarket(shortSale("short-1"), "TW")
        )
    }

    @Test
    fun shortCoverBuyAndCoverSharesMustMatch() {
        val mismatchedCover = StockTransaction(
            stockCode = "2330",
            date = 4L,
            recordTime = 4L,
            type = "買券還券",
            buyPrice = 90.0,
            buyShares = 100.0,
            shortCoverLotId = "short-1",
            shortCoverShares = 120.0,
            expense = 9_010.0
        )

        assertNotNull(
            FinancingTransactionValidationSupport.validate(listOf(shortSale("short-1"), mismatchedCover))
        )
    }

    @Test
    fun financingTransactionsRejectFieldsBelongingToOtherTransactionKinds() {
        val repayment = StockTransaction(
            stockCode = "2330", date = 2L, recordTime = 2L, type = "融資還款",
            marginRepaymentLotId = "margin-1", marginRepayment = 10_000.0,
            expense = 10_000.0
        )
        val cover = StockTransaction(
            stockCode = "2330", date = 4L, recordTime = 4L, type = "買券還券",
            buyPrice = 90.0, buyShares = 100.0, shortCoverLotId = "short-1",
            shortCoverShares = 100.0, expense = 9_010.0
        )
        val compensation = StockTransaction(
            stockCode = "2330", date = 5L, recordTime = 5L, type = "融券補償",
            shortCompensationLotId = "short-1", shortCompensation = 500.0,
            expense = 500.0
        )
        val sellRepayment = StockTransaction(
            stockCode = "2330", date = 3L, recordTime = 3L, type = "賣出",
            sellPrice = 100.0, sellShares = 500.0, income = 49_800.0,
            marginRepaymentLotId = "margin-1", marginRepayment = 10_000.0
        )

        val invalidTransactionSets = listOf(
            listOf(marginLoan("margin-1").copy(sellPrice = 1.0)),
            listOf(marginLoan("margin-1"), repayment.copy(fee = 1.0)),
            listOf(shortSale("short-1").copy(buyShares = 1.0)),
            listOf(shortSale("short-1"), cover.copy(tax = 1.0)),
            listOf(shortSale("short-1"), compensation.copy(dividendIncome = 1.0)),
            listOf(marginLoan("margin-1"), sellRepayment.copy(expense = 1.0))
        )

        invalidTransactionSets.forEach { transactions ->
            assertNotNull(
                FinancingTransactionValidationSupport.validate(transactions)
            )
        }
    }

    @Test
    fun invalidCompanyActionsThatWouldCorruptShortBalancesAreRejected() {
        val invalidReduction = StockTransaction(
            stockCode = "2330",
            date = 2L,
            recordTime = 2L,
            type = "減資",
            capitalReductionRatio = 120.0,
            sharesBeforeReduction = 1_000.0,
            sharesAfterReduction = -200.0
        )
        val legacySplitWithRatioOnly = StockTransaction(
            stockCode = "2330",
            date = 2L,
            recordTime = 2L,
            type = "分割",
            stockSplitRatio = 10.0
        )

        assertNotNull(FinancingTransactionValidationSupport.validate(listOf(invalidReduction)))
        assertNull(FinancingTransactionValidationSupport.validate(listOf(legacySplitWithRatioOnly)))
    }

    private fun marginLoan(lotId: String) = StockTransaction(
        stockCode = "2330", date = 1L, recordTime = 1L, type = "融資買進",
        buyPrice = 100.0, buyShares = 1_000.0, expense = 100_100.0,
        marginPrincipal = 60_000.0, marginAnnualRate = 3.0, marginLotId = lotId
    )

    private fun shortSale(lotId: String) = StockTransaction(
        stockCode = "2330", date = 3L, recordTime = 3L, type = "融券賣出",
        sellPrice = 100.0, sellShares = 1_000.0, income = 99_500.0,
        shortBorrowPrincipal = 100_000.0, shortBorrowAnnualRate = 2.0, shortLotId = lotId
    )
}
