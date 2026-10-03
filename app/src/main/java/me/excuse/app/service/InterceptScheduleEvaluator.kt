package me.excuse.app.service

import me.excuse.app.data.prefs.InterceptSchedule
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * 「今天拦不拦」的纯判定。
 *
 * 唯一输入是 (调度配置, 本地日期时间)，不碰系统时钟、不碰 DataStore，因此可在 JVM 上确定性单测。
 * 时区由 [zoneId] 显式传入（生产环境用 [ZoneId.systemDefault]）。
 *
 * 语义（与产品约定一致）：
 *  - 临时暂停优先于周几规则：暂停期内无论周几都不拦。
 *  - 暂停截止用绝对时间戳表达，「暂停到今天结束」在暂停那一刻换算成当天 [LocalTime.MAX]。
 *  - 判定只决定「是否发起新的拦截」，不改变已进入 InSession / TimeUp 的会话。
 */
object InterceptScheduleEvaluator {

    fun shouldIntercept(
        schedule: InterceptSchedule,
        now: LocalDate,
        nowMillis: Long,
        zoneId: ZoneId,
    ): Boolean {
        if (schedule.isPausedAt(nowMillis)) return false
        return schedule.activeWeekdays.contains(now.dayOfWeek)
    }

    /** 暂停 2 小时：把截止时间戳算成 now + 2h。 */
    fun pauseForHours(fromMillis: Long, hours: Long): Long = fromMillis + hours * 3_600_000L

    /** 暂停到今天结束：当天 23:59:59.999 的绝对时间戳。 */
    fun pauseUntilEndOfDay(date: LocalDate, zoneId: ZoneId): Long =
        date.atTime(LocalTime.MAX).atZone(zoneId).toInstant().toEpochMilli()
}
