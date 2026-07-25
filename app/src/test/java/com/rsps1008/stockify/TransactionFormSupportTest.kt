package com.rsps1008.stockify

import com.rsps1008.stockify.ui.screens.datePickerSelectionToTransactionDateMillis
import com.rsps1008.stockify.ui.screens.isValidMarginRepaymentAmounts
import com.rsps1008.stockify.ui.screens.normalizeTransactionDateMillis
import com.rsps1008.stockify.ui.screens.transactionDateToDatePickerMillis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class TransactionFormSupportTest {
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
}
