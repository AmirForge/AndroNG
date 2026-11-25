package com.v2ray.ang.ui.main

import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class UsageWindowsTest {
    @Test
    fun sevenMostRecentPeriodsAreTogetherAndAllOlderRecordsRemainReachable() {
        val hours = usageWindows(List(24) { 10L })
        assertEquals(17..23, hours.last())
        assertEquals(0..2, hours.first())
        assertEquals((0 until 24).toList(), hours.flatMap { it.toList() })
        assertEquals(23..29, usageWindows(List(30) { 10L }).last())
        assertEquals(listOf(0..2), usageWindows(List(3) { 10L }))
        assertEquals(emptyList<IntRange>(), usageWindows(emptyList()))
    }

    @Test
    fun onlyPagesWithRecordsRemainAndZeroIsARecord() {
        val values = MutableList<Long?>(24) { null }
        assertEquals(emptyList<IntRange>(), usageWindows(values))
        values[5] = 0L
        values[21] = 42L
        assertEquals(listOf(5..9, 17..23), usageWindows(values))
        values[21] = null
        assertEquals(listOf(5..9), usageWindows(values))
    }

    @Test
    fun dailyWindowEndsOnTheReportDayOrTheEndOfTheCycle() {
        val start = LocalDate.of(2026, 11, 10)
        assertEquals(0, usageDaysThrough(start, start.minusDays(1), 30))
        assertEquals(1, usageDaysThrough(start, start, 30))
        assertEquals(7..13, usageWindows(List(usageDaysThrough(start, start.plusDays(13), 30)) { 10L }).last())
        assertEquals(30, usageDaysThrough(start, start.plusDays(31), 30))
        assertEquals(30, usageDaysThrough(null, start, 30))
    }

    @Test
    fun chartUsesTwiceThePeakInTheSelectedTimeMode() {
        assertEquals(5_000_000_000L, usageChartCeiling(2_300_000_000L))
        assertEquals(400_000_000L, usageChartCeiling(194_000_000L))
        assertEquals(300_000_000L, usageChartCeiling(141_000_000L))
        assertEquals(1L, usageChartCeiling(0L))
        assertEquals(Long.MAX_VALUE, usageChartCeiling(Long.MAX_VALUE))
    }
}
