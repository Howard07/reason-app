package me.excuse.app.data.prefs

import java.time.DayOfWeek

/**
 * 拦截调度配置：周几规则 + 临时暂停。
 *
 * 默认 7 天全开（= 历史行为，升级后不变），暂停截止为 null（未暂停）。
 */
data class InterceptSchedule(
    val activeWeekdays: Set<DayOfWeek> = DayOfWeek.entries.toSet(),
    val pauseUntilMillis: Long? = null,
    val pauseSource: PauseSource? = null,
) {
    fun isPausedAt(nowMillis: Long): Boolean {
        val until = pauseUntilMillis ?: return false
        return until > nowMillis
    }
}

/** 记录本次暂停是怎么触发的，供通知文案与设置页显示。 */
enum class PauseSource(val key: String) {
    TWO_HOURS("2h"),
    END_OF_DAY("today");

    companion object {
        fun fromKey(key: String?): PauseSource? = entries.firstOrNull { it.key == key }
    }
}
