package me.excuse.app.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * 到点兜底：用 [AlarmManager.setExactAndAllowWhileIdle] 注册一个精确闹钟，
 * 在 session 计划时长耗尽时唤醒本进程，绕开部分厂商 ROM（ColorOS/MIUI/OriginOS）
 * 对前台服务协程 [kotlinx.coroutines.delay] 的省电冻结。
 *
 * 为什么需要它：SessionTimer 依赖协程 delay 到点回调，但在激进省电策略下，
 * 前台服务进程的 CPU 调度会被冻结，delay 永不恢复，超时窗永远不弹。
 * AlarmManager 的闹钟由系统进程触发，不依赖 app 进程的调度，是唯一抗冻结的定时手段。
 *
 * 正常路径仍由 SessionTimer 的 delay 更快地到点；闹钟作为保险兜底（通常晚几秒）。
 * onSessionTimeElapsed 内部幂等，两条路径谁先到都不会重复弹窗。
 */
class SessionDeadlineAlarm(private val context: Context) {

    private val alarmManager by lazy {
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    }

    /**
     * 注册一个到点闹钟。
     *
     * @param packageName 目标 app 包名，作为闹钟的唯一标识（一个 pkg 最多一个 session）。
     * @param deadlineMillis 计划时长的绝对墙钟终点（startedAt + plannedMinutes * 60_000）。
     * @return true 表示闹钟成功注册；false 表示不可用（无精确闹钟权限或平台限制），
     *         此时退化为仅靠 SessionTimer 的 delay 计时。
     */
    fun schedule(packageName: String, deadlineMillis: Long): Boolean {
        if (!canScheduleExact()) {
            Log.w(TAG, "exact alarm not permitted; fallback to delay-only for pkg=$packageName")
            return false
        }
        // 已经过了 deadline 的 session 无需闹钟（兜底循环会立刻处理）。
        if (deadlineMillis <= System.currentTimeMillis()) {
            Log.w(TAG, "deadline already past, skip alarm for pkg=$packageName")
            return false
        }
        return try {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                deadlineMillis,
                pendingIntent(packageName),
            )
            Log.w(TAG, "alarm scheduled pkg=$packageName deadline=$deadlineMillis now=${System.currentTimeMillis()}")
            true
        } catch (error: SecurityException) {
            Log.w(TAG, "setExactAndAllowWhileIdle rejected for pkg=$packageName", error)
            false
        }
    }

    fun cancel(packageName: String) {
        try {
            alarmManager.cancel(pendingIntent(packageName))
        } catch (_: Exception) {
        }
    }

    /**
     * Android 12 (S, API 31) 起精确闹钟受 SCHEDULE_EXACT_ALARM 保护（默认拒绝，需用户授权）；
     * Android 13 (T, API 33) 起，定时器/闹钟类 app 可用 USE_EXACT_ALARM（免授权，受商店审核）。
     * 我们在 Manifest 里按 SDK 版本同时声明两者；canScheduleExactAlarms() 会如实反映授权状态。
     */
    private fun canScheduleExact(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    private fun pendingIntent(packageName: String): PendingIntent {
        val intent = Intent(context, DeadlineAlarmReceiver::class.java).apply {
            action = ACTION_SESSION_DEADLINE
            putExtra(EXTRA_PACKAGE_NAME, packageName)
        }
        val flags =
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(
            context,
            requestCode(packageName),
            intent,
            flags,
        )
    }

    private fun requestCode(packageName: String): Int =
        (packageName.hashCode() and 0x7FFFFFFF)

    companion object {
        const val ACTION_SESSION_DEADLINE =
            "me.excuse.app.service.action.SESSION_DEADLINE"
        const val EXTRA_PACKAGE_NAME = "me.excuse.app.service.extra.PACKAGE_NAME"
        private const val TAG = "ReasonTimer"
    }
}
