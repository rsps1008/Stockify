package com.rsps1008.stockify.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Preserves the date-only meaning of transaction timestamps.
 *
 * Transactions are stored at the start of the selected day in the device time zone.
 * Historical valuation instants use market time zones, so comparing the two epochs
 * directly can move a transaction to an adjacent market date.
 */
object TransactionDateSupport {
    fun replayCutoffMillis(
        isoDate: String,
        storageZoneId: ZoneId = ZoneId.systemDefault()
    ): Long? {
        val date = parseIsoDate(isoDate) ?: return null
        return date.plusDays(1)
            .atStartOfDay(storageZoneId)
            .toInstant()
            .toEpochMilli() - 1L
    }

    fun moveToZoneDateStartMillis(
        transactionDateMillis: Long,
        targetZoneId: ZoneId,
        storageZoneId: ZoneId = ZoneId.systemDefault()
    ): Long {
        val date = Instant.ofEpochMilli(transactionDateMillis)
            .atZone(storageZoneId)
            .toLocalDate()
        return date.atStartOfDay(targetZoneId).toInstant().toEpochMilli()
    }

    private fun parseIsoDate(value: String): LocalDate? = runCatching {
        LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE)
    }.getOrNull()
}
