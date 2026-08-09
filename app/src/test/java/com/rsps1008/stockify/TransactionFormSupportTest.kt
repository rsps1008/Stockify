package com.rsps1008.stockify

import com.rsps1008.stockify.ui.screens.datePickerSelectionToTransactionDateMillis
import com.rsps1008.stockify.ui.screens.financingLotScopeChanged
import com.rsps1008.stockify.ui.screens.autoCalculatedMarginSelfFundedText
import com.rsps1008.stockify.ui.screens.annualRateInputText
import com.rsps1008.stockify.ui.screens.isValidMarginRepaymentAmounts
import com.rsps1008.stockify.ui.screens.resolveSellMarginRepayment
import com.rsps1008.stockify.ui.screens.shouldApplyDividendAutoFill
import com.rsps1008.stockify.ui.screens.shouldApplyTransactionTypeChange
import com.rsps1008.stockify.ui.screens.transactionCashFlowAmount
import com.rsps1008.stockify.data.StockTransaction
import com.rsps1008.stockify.ui.screens.normalizeTransactionDateMillis
import com.rsps1008.stockify.ui.screens.shouldAutoCalculateTransactionCosts
import com.rsps1008.stockify.ui.screens.transactionDateToDatePickerMillis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class TransactionFormSupportTest {
    @Test
    fun editingDoesNotRecalculateStoredAmountsUntilTheUserChangesTradeInputs() {
        assertFalse(shouldAutoCalculateTransactionCosts(transactionId = 1, hasUserEditedTradeInputs = false))
        assertTrue(shouldAutoCalculateTransactionCosts(transactionId = 1, hasUserEditedTradeInputs = true))
        assertTrue(shouldAutoCalculateTransactionCosts(transactionId = null, hasUserEditedTradeInputs = false))
    }

    @Test
    fun reselectingTheCurrentTransactionTypeDoesNotCountAsAnEdit() {
        assertFalse(shouldApplyTransactionTypeChange("買進", "買進"))
        assertTrue(shouldApplyTransactionTypeChange("買進", "賣出"))
    }

    @Test
    fun dividendAutoFillOnlyAppliesToTheOriginalFormScope() {
        assertTrue(
            shouldApplyDividendAutoFill(
                requestedStockCode = "2330",
                requestedAccountId = 1,
                requestedDate = 1_000L,
                requestedType = "配息",
                currentStockCode = "2330",
                currentAccountId = 1,
                currentDate = 1_000L,
                currentType = "配息"
            )
        )
        assertFalse(
            shouldApplyDividendAutoFill(
                requestedStockCode = "2330",
                requestedAccountId = 1,
                requestedDate = 1_000L,
                requestedType = "配息",
                currentStockCode = "2330",
                currentAccountId = 2,
                currentDate = 1_000L,
                currentType = "配息"
            )
        )
        assertFalse(
            shouldApplyDividendAutoFill(
                requestedStockCode = "2330",
                requestedAccountId = 1,
                requestedDate = 1_000L,
                requestedType = "配息",
                currentStockCode = "0050",
                currentAccountId = 1,
                currentDate = 1_000L,
                currentType = "配息"
            )
        )
        assertFalse(
            shouldApplyDividendAutoFill(
                requestedStockCode = "2330",
                requestedAccountId = 1,
                requestedDate = 1_000L,
                requestedType = "配息",
                currentStockCode = "2330",
                currentAccountId = 1,
                currentDate = 2_000L,
                currentType = "配股"
            )
        )
    }

    @Test
    fun marginSelfFundedAutoFillUsesExpenseMinusPrincipal() {
        assertEquals("40000", autoCalculatedMarginSelfFundedText(100_000.0, "60000"))
        assertEquals("0", autoCalculatedMarginSelfFundedText(100_000.0, "100000"))
        assertEquals("", autoCalculatedMarginSelfFundedText(100_000.0, "100001"))
        assertEquals("", autoCalculatedMarginSelfFundedText(100_000.0, ""))
    }

    @Test
    fun annualRateInputKeepsAValidRateAndRejectsInvalidValues() {
        assertEquals("6.45", annualRateInputText(6.45))
        assertEquals("3.5", annualRateInputText(3.5))
        assertEquals("0", annualRateInputText(0.0))
        assertEquals("", annualRateInputText(Double.NaN))
    }

    @Test
    fun financingLotSelectionIsClearedWhenStockOrAccountChanges() {
        assertFalse(financingLotScopeChanged("2330", 1, "2330", 1))
        assertTrue(financingLotScopeChanged("2330", 1, "2330", 2))
        assertTrue(financingLotScopeChanged("2330", 1, "0050", 1))
    }

    @Test
    fun interestOnlyMarginRepaymentIsValid() {
        assertTrue(isValidMarginRepaymentAmounts("", "120", 0.0))
        assertTrue(isValidMarginRepaymentAmounts("0", "120", 50_000.0))
    }

    @Test
    fun marginRepaymentRejectsEmptyNegativeAndOverpaymentAmounts() {
        assertFalse(isValidMarginRepaymentAmounts("", "", 50_000.0))
        assertFalse(isValidMarginRepaymentAmounts("-1", "120", 50_000.0))
        assertFalse(isValidMarginRepaymentAmounts("50001", "", 50_000.0))
        assertFalse(isValidMarginRepaymentAmounts("0", "Infinity", 50_000.0))
    }

    @Test
    fun sellMarginRepaymentUsesIncomeOnlyWhenTheInputIsBlank() {
        assertEquals(80_000.0, resolveSellMarginRepayment("", 80_000.0)!!, 0.0)
        assertEquals(30_000.0, resolveSellMarginRepayment("30000", 80_000.0)!!, 0.0)
        assertNull(resolveSellMarginRepayment("not-a-number", 80_000.0))
        assertNull(resolveSellMarginRepayment("Infinity", 80_000.0))
    }

    @Test
    fun currentTimeAndDatePickerSelectionNormalizeToTheSameTradingDate() {
        val taipei = ZoneId.of("Asia/Taipei")
        val date = LocalDate.of(2026, 7, 25)
        val afternoon = date.atTime(15, 30).atZone(taipei).toInstant().toEpochMilli()
        val pickerUtcMidnight = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val expected = date.atStartOfDay(taipei).toInstant().toEpochMilli()

        assertEquals(expected, normalizeTransactionDateMillis(afternoon, taipei))
        assertEquals(expected, datePickerSelectionToTransactionDateMillis(pickerUtcMidnight, taipei))
        assertEquals(pickerUtcMidnight, transactionDateToDatePickerMillis(expected, taipei))
    }

    @Test
    fun `sell repaying more than its income is a negative cash flow`() {
        val transaction = StockTransaction(
            stockCode = "2330",
            date = 0L,
            recordTime = 0L,
            type = "賣出",
            income = 50_000.0,
            marginRepaymentLotId = "lot-1",
            marginRepayment = 55_000.0,
            marginActualInterest = 500.0
        )

        assertEquals(-5_500.0, transactionCashFlowAmount(transaction) ?: 0.0, 0.0)
    }
}
