package com.rsps1008.stockify

import com.rsps1008.stockify.data.isAutomaticCloudBackupDue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class AutomaticCloudBackupDueTest {
    private val zone = ZoneId.of("Asia/Taipei")

    @Test
    fun disabledReturnsFalse() {
        val result = isAutomaticCloudBackupDue(
            enabled = false,
            intervalDays = 1,
            lastAttempt = 0L,
            lastSuccess = 0L,
            hasRecordedFailure = false,
            nowMillis = System.currentTimeMillis()
        )
        assertFalse(result)
    }

    @Test
    fun intervalZeroAlwaysDue() {
        val todayMorning = ZonedDateTime.of(2026, 10, 10, 8, 0, 0, 0, zone).toInstant().toEpochMilli()
        val todayAfternoon = ZonedDateTime.of(2026, 10, 10, 16, 0, 0, 0, zone).toInstant().toEpochMilli()

        val result = isAutomaticCloudBackupDue(
            enabled = true,
            intervalDays = 0,
            lastAttempt = todayMorning,
            lastSuccess = todayMorning,
            hasRecordedFailure = false,
            nowMillis = todayAfternoon,
            zoneId = zone
        )
        assertTrue(result)
    }

    @Test
    fun neverBackedUpIsDue() {
        val now = ZonedDateTime.of(2026, 10, 10, 16, 0, 0, 0, zone).toInstant().toEpochMilli()
        val result = isAutomaticCloudBackupDue(
            enabled = true,
            intervalDays = 1,
            lastAttempt = 0L,
            lastSuccess = 0L,
            hasRecordedFailure = false,
            nowMillis = now,
            zoneId = zone
        )
        assertTrue(result)
    }

    @Test
    fun failedAttemptWithinCooldownReturnsFalse() {
        val now = 1000000L
        val recentAttempt = now - 60_000L // 1 minute ago, less than 5 minutes cooldown
        val result = isAutomaticCloudBackupDue(
            enabled = true,
            intervalDays = 1,
            lastAttempt = recentAttempt,
            lastSuccess = 0L,
            hasRecordedFailure = true,
            nowMillis = now,
            retryCooldownMillis = 300_000L
        )
        assertFalse(result)
    }

    @Test
    fun failedAttemptAfterCooldownReturnsTrue() {
        val now = 1000000L
        val pastAttempt = now - 400_000L // 6.6 minutes ago, past 5 minutes cooldown
        val result = isAutomaticCloudBackupDue(
            enabled = true,
            intervalDays = 1,
            lastAttempt = pastAttempt,
            lastSuccess = 0L,
            hasRecordedFailure = true,
            nowMillis = now,
            retryCooldownMillis = 300_000L
        )
        assertTrue(result)
    }

    @Test
    fun intervalOneDay_AcrossMidnight_IsDue() {
        // Yesterday 23:00 vs Today 08:00 (only 9 hours apart, but new calendar day)
        val yesterdayNight = ZonedDateTime.of(2026, 10, 9, 23, 0, 0, 0, zone).toInstant().toEpochMilli()
        val todayMorning = ZonedDateTime.of(2026, 10, 10, 8, 0, 0, 0, zone).toInstant().toEpochMilli()

        val result = isAutomaticCloudBackupDue(
            enabled = true,
            intervalDays = 1,
            lastAttempt = yesterdayNight,
            lastSuccess = yesterdayNight,
            hasRecordedFailure = false,
            nowMillis = todayMorning,
            zoneId = zone
        )
        assertTrue("Yesterday 23:00 to Today 08:00 must be due because it's a new calendar date", result)
    }

    @Test
    fun intervalOneDay_SameDayAfterSuccess_IsNotDue() {
        // Today 08:00 backed up, today 16:00 closing app again
        val todayMorning = ZonedDateTime.of(2026, 10, 10, 8, 0, 0, 0, zone).toInstant().toEpochMilli()
        val todayAfternoon = ZonedDateTime.of(2026, 10, 10, 16, 0, 0, 0, zone).toInstant().toEpochMilli()

        val result = isAutomaticCloudBackupDue(
            enabled = true,
            intervalDays = 1,
            lastAttempt = todayMorning,
            lastSuccess = todayMorning,
            hasRecordedFailure = false,
            nowMillis = todayAfternoon,
            zoneId = zone
        )
        assertFalse("Later on the same day after a success, it must not back up again", result)
    }

    @Test
    fun intervalThreeDays_DueOnFourthDay() {
        val day1 = ZonedDateTime.of(2026, 10, 1, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        val day3 = ZonedDateTime.of(2026, 10, 3, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        val day4 = ZonedDateTime.of(2026, 10, 4, 12, 0, 0, 0, zone).toInstant().toEpochMilli()

        assertFalse(
            isAutomaticCloudBackupDue(
                enabled = true,
                intervalDays = 3,
                lastAttempt = day1,
                lastSuccess = day1,
                hasRecordedFailure = false,
                nowMillis = day3,
                zoneId = zone
            )
        )
        assertTrue(
            isAutomaticCloudBackupDue(
                enabled = true,
                intervalDays = 3,
                lastAttempt = day1,
                lastSuccess = day1,
                hasRecordedFailure = false,
                nowMillis = day4,
                zoneId = zone
            )
        )
    }
}
