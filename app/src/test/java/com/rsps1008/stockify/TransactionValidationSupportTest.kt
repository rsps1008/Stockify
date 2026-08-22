package com.rsps1008.stockify

import com.rsps1008.stockify.data.StockTransaction
import com.rsps1008.stockify.data.TransactionValidationSupport
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TransactionValidationSupportTest {
    @Test
    fun negativeOrdinaryExpenseIsRejected() {
        val transaction = ordinaryBuy().copy(expense = -100.0)

        assertNotNull(TransactionValidationSupport.validateForWrite(transaction))
    }

    @Test
    fun nonFiniteOrdinaryIncomeIsRejected() {
        val transaction = ordinarySell().copy(income = Double.NaN)

        assertNotNull(TransactionValidationSupport.validateForWrite(transaction))
    }

    @Test
    fun ordinaryBuyWithSellFieldsIsRejected() {
        val transaction = ordinaryBuy().copy(sellPrice = 100.0, sellShares = 1.0)

        assertNotNull(TransactionValidationSupport.validateForWrite(transaction))
    }

    @Test
    fun ordinaryBuyWithShortFieldsIsRejected() {
        val transaction = ordinaryBuy().copy(shortBorrowPrincipal = 100.0)

        assertNotNull(TransactionValidationSupport.validateForWrite(transaction))
    }

    @Test
    fun validOrdinaryBuyAndSellPass() {
        assertNull(TransactionValidationSupport.validateForWrite(ordinaryBuy()))
        assertNull(TransactionValidationSupport.validateForWrite(ordinarySell()))
    }

    @Test
    fun ordinaryManualAmountOverridesRemainAllowed() {
        assertNull(TransactionValidationSupport.validateForWrite(ordinaryBuy().copy(expense = 1_001.0)))
        assertNull(TransactionValidationSupport.validateForWrite(ordinarySell().copy(income = 119.0)))
    }

    @Test
    fun validCorporateActionRowsPass() {
        assertNull(
            TransactionValidationSupport.validateForWrite(
                StockTransaction(
                    stockCode = "2330",
                    date = 3L,
                    recordTime = 1L,
                    type = "配息",
                    cashDividend = 2.0,
                    exDividendShares = 10.0,
                    fee = 1.0,
                    dividendIncome = 19.0
                )
            )
        )
        assertNull(
            TransactionValidationSupport.validateForWrite(
                StockTransaction(
                    stockCode = "2330",
                    date = 4L,
                    recordTime = 1L,
                    type = "配股",
                    stockDividend = 0.1,
                    dividendShares = 1.0,
                    exRightsShares = 10.0
                )
            )
        )
        assertNull(
            TransactionValidationSupport.validateForWrite(
                StockTransaction(
                    stockCode = "2330",
                    date = 5L,
                    recordTime = 1L,
                    type = "減資",
                    capitalReductionRatio = 10.0,
                    sharesBeforeReduction = 100.0,
                    sharesAfterReduction = 90.0
                )
            )
        )
        assertNull(
            TransactionValidationSupport.validateForWrite(
                StockTransaction(
                    stockCode = "2330",
                    date = 6L,
                    recordTime = 1L,
                    type = "分割",
                    stockSplitRatio = 2.0,
                    sharesBeforeSplit = 100.0,
                    sharesAfterSplit = 200.0
                )
            )
        )
    }

    @Test
    fun corporateActionWithAnotherActionFieldIsRejected() {
        val transaction = StockTransaction(
            stockCode = "2330",
            date = 3L,
            recordTime = 1L,
            type = "配息",
            cashDividend = 2.0,
            dividendIncome = 20.0,
            stockDividend = 0.1
        )

        assertNotNull(TransactionValidationSupport.validateForWrite(transaction))
    }

    private fun ordinaryBuy() = StockTransaction(
        stockCode = "2330",
        date = 1L,
        recordTime = 1L,
        type = "買進",
        buyPrice = 100.0,
        buyShares = 10.0,
        fee = 0.0,
        expense = 1_000.0
    )

    private fun ordinarySell() = StockTransaction(
        stockCode = "2330",
        date = 2L,
        recordTime = 1L,
        type = "賣出",
        sellPrice = 120.0,
        sellShares = 1.0,
        fee = 0.0,
        tax = 0.0,
        income = 120.0
    )
}
