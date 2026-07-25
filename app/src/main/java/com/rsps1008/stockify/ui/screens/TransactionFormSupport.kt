package com.rsps1008.stockify.ui.screens

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
    return value.takeIf { it >= 0.0 }
}
