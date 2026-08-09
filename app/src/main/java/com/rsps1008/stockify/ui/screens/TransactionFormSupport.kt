package com.rsps1008.stockify.ui.screens

import com.rsps1008.stockify.data.HoldingCalculationSupport
import com.rsps1008.stockify.data.StockTransaction
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

internal fun isValidMarginRepaymentAmounts(
    repaymentText: String,
    actualInterestText: String,
    remainingPrincipal: Double
): Boolean {
    val repayment = repaymentText.toOptionalNonNegativeDouble() ?: return false
    val actualInterest = actualInterestText.toOptionalNonNegativeDouble() ?: return false
    return (repayment > 0.0 || actualInterest > 0.0) &&
        repayment <= remainingPrincipal
}

internal fun resolveSellMarginRepayment(
    repaymentText: String,
    sellIncome: Double
): Double? {
    if (!sellIncome.isFinite() || sellIncome < 0.0) return null
    if (repaymentText.isBlank()) return sellIncome
    return repaymentText.toOptionalNonNegativeDouble()
}

internal fun shouldAutoCalculateTransactionCosts(
    transactionId: Int?,
    hasUserEditedTradeInputs: Boolean
): Boolean = transactionId == null || hasUserEditedTradeInputs

internal fun shouldApplyTransactionTypeChange(
    currentType: String,
    requestedType: String
): Boolean = currentType != requestedType

internal fun shouldApplyDividendAutoFill(
    requestedStockCode: String,
    requestedAccountId: Int,
    requestedDate: Long,
    requestedType: String,
    currentStockCode: String,
    currentAccountId: Int,
    currentDate: Long,
    currentType: String
): Boolean {
    return requestedStockCode == currentStockCode &&
        requestedAccountId == currentAccountId &&
        requestedDate == currentDate &&
        requestedType == currentType
}

internal fun autoCalculatedMarginSelfFundedText(
    expense: Double,
    marginPrincipalText: String
): String {
    val principal = marginPrincipalText.toDoubleOrNull()
        ?.takeIf { it.isFinite() && it >= 0.0 }
        ?: return ""
    val selfFunded = expense - principal
    if (!expense.isFinite() || expense < 0.0 || !selfFunded.isFinite() || selfFunded < 0.0) {
        return ""
    }
    return BigDecimal.valueOf(selfFunded).stripTrailingZeros().toPlainString()
}

internal fun annualRateInputText(rate: Double): String {
    if (!rate.isFinite() || rate < 0.0) return ""
    return BigDecimal.valueOf(rate).stripTrailingZeros().toPlainString()
}

internal fun financingLotScopeChanged(
    currentStockCode: String,
    currentAccountId: Int,
    newStockCode: String,
    newAccountId: Int
): Boolean {
    return currentStockCode != newStockCode || currentAccountId != newAccountId
}

internal fun transactionCashFlowAmount(transaction: StockTransaction): Double? {
    return when (transaction.type) {
        "買進" -> -transaction.expense
        "融資買進" -> -(if (transaction.marginSelfFundedOverridden) {
            transaction.marginSelfFunded
        } else {
            transaction.expense - transaction.marginPrincipal
        })
        "賣出" -> transaction.income - transaction.marginRepayment - transaction.marginActualInterest
        "融資還款" -> -(transaction.marginRepayment + transaction.marginActualInterest)
        "融券賣出" -> transaction.income
        "買券還券" -> -transaction.expense
        "融券補償" -> -transaction.shortCompensation
        "配息" -> HoldingCalculationSupport.resolveDividendIncome(transaction)
        "減資" -> transaction.cashReturned
        "配股" -> 0.0
        else -> null
    }
}

internal fun normalizeTransactionDateMillis(
    dateMillis: Long,
    zoneId: ZoneId = ZoneId.systemDefault()
): Long {
    return Instant.ofEpochMilli(dateMillis)
        .atZone(zoneId)
        .toLocalDate()
        .atStartOfDay(zoneId)
        .toInstant()
        .toEpochMilli()
}

internal fun transactionDateToDatePickerMillis(
    dateMillis: Long,
    zoneId: ZoneId = ZoneId.systemDefault()
): Long {
    return Instant.ofEpochMilli(dateMillis)
        .atZone(zoneId)
        .toLocalDate()
        .atStartOfDay(ZoneOffset.UTC)
        .toInstant()
        .toEpochMilli()
}

internal fun datePickerSelectionToTransactionDateMillis(
    selectedDateMillis: Long,
    zoneId: ZoneId = ZoneId.systemDefault()
): Long {
    return Instant.ofEpochMilli(selectedDateMillis)
        .atZone(ZoneOffset.UTC)
        .toLocalDate()
        .atStartOfDay(zoneId)
        .toInstant()
        .toEpochMilli()
}

private fun String.toOptionalNonNegativeDouble(): Double? {
    val value = if (isBlank()) 0.0 else toDoubleOrNull() ?: return null
    return value.takeIf { it.isFinite() && it >= 0.0 }
}
