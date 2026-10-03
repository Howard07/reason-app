package me.excuse.app.service

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import me.excuse.app.data.prefs.InterceptSchedule
import me.excuse.app.data.prefs.PauseSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InterceptScheduleEvaluatorTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    // 2026-10-05 是周一（用于跨午夜/跨周的可读断言）。
    private val monday = LocalDate.of(2026, 10, 5)
    private val sunday = LocalDate.of(2026, 10, 11)

    private fun millisOf(date: LocalDate, hour: Int, minute: Int): Long =
        date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun defaultAllDaysInterceptsEveryDay() {
        val schedule = InterceptSchedule()
        for (day in DayOfWeek.entries) {
            assertTrue(
                "default should intercept $day",
                InterceptScheduleEvaluator.shouldIntercept(
                    schedule, monday.plusDays((day.value - 1).toLong()), 0L, zone,
                ),
            )
        }
    }

    @Test
    fun removingSundaySkipsSundayButNotMonday() {
        val schedule = InterceptSchedule(activeWeekdays = DayOfWeek.entries.toSet() - DayOfWeek.SUNDAY)

        assertFalse(InterceptScheduleEvaluator.shouldIntercept(schedule, sunday, 0L, zone))
        assertTrue(InterceptScheduleEvaluator.shouldIntercept(schedule, monday, 0L, zone))
    }

    @Test
    fun pauseTwoHoursDisablesDuringWindowThenReenables() {
        val schedule = InterceptSchedule(activeWeekdays = DayOfWeek.entries.toSet())
        val from = millisOf(monday, 9, 0)
        val paused = schedule.copy(
            pauseUntilMillis = InterceptScheduleEvaluator.pauseForHours(from, 2),
            pauseSource = PauseSource.TWO_HOURS,
        )

        // 暂停期内：不拦
        assertFalse(InterceptScheduleEvaluator.shouldIntercept(paused, monday, from + 1_000L, zone))
        // 到期那一刻（含）后：恢复周几规则 → 周一拦
        assertTrue(InterceptScheduleEvaluator.shouldIntercept(paused, monday, from + 2 * 3_600_000L, zone))
    }

    @Test
    fun pauseUntilEndOfDayResumesNextMorning() {
        val schedule = InterceptSchedule(activeWeekdays = DayOfWeek.entries.toSet())
        val paused = schedule.copy(
            pauseUntilMillis = InterceptScheduleEvaluator.pauseUntilEndOfDay(monday, zone),
            pauseSource = PauseSource.END_OF_DAY,
        )

        // 当天深夜仍暂停
        assertFalse(InterceptScheduleEvaluator.shouldIntercept(paused, monday, millisOf(monday, 23, 0), zone))
        // 次日 0 点后：自动恢复（周二也默认拦截）
        val tuesday = monday.plusDays(1)
        assertTrue(InterceptScheduleEvaluator.shouldIntercept(paused, tuesday, millisOf(tuesday, 0, 1), zone))
    }

    @Test
    fun pauseOverridesWeekdayRuleEvenOnAnActiveDay() {
        // 周一在激活列表里，但暂停期内仍然不拦 —— 暂停优先。
        val schedule = InterceptSchedule(
            activeWeekdays = DayOfWeek.entries.toSet(),
            pauseUntilMillis = millisOf(monday, 12, 0),
            pauseSource = PauseSource.TWO_HOURS,
        )
        assertFalse(InterceptScheduleEvaluator.shouldIntercept(schedule, monday, millisOf(monday, 10, 0), zone))
    }

    @Test
    fun nullPauseUntilIsNeverPaused() {
        val schedule = InterceptSchedule()
        assertFalse(schedule.isPausedAt(Long.MAX_VALUE))
    }
}
