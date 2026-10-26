package me.excuse.app.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 存活看门狗。
 *
 * 监控能力全部长在 [AppMonitorService] 这一个前台服务上，而前台服务是「应用托管」的：
 * 厂商 ROM 在灭屏休眠、耗电治理、一键清理时会直接杀掉进程，且不负责把它拉回来。
 * START_STICKY 在国产 ROM 上基本等于失效；[BootReceiver] 只在开机时跑一次；
 * [SessionDeadlineAlarm] 只有存在活跃 session 时才排闹钟。所以「空闲状态下被杀」
 * 是一条没有任何复活源的路，表现就是永久停摆直到下次重启手机。
 *
 * 这里补一个「系统托管」的唤醒源：一次性闹钟交给 AlarmManager，由系统进程在到点时
 * 发广播，完全不依赖 app 进程的任何调度。这是唯一能穿透深度休眠与进程冻结的定时机制。
 *
 * 两个刻意的取舍：
 *
 * 1. 用 [AlarmManager.setAndAllowWhileIdle] 而不是 setExactAndAllowWhileIdle。
 *    看门狗不需要精确到秒；而 Doze 下「精确允许」类闹钟每个 app 共用一个 9 分钟量级的
 *    配额池，[SessionDeadlineAlarm] 已经占用那个池子，混用会互相推迟对方的到点时间。
 *    非精确的 allowWhileIdle 走独立配额。
 *
 * 2. 不维护心跳时间戳，也不做「服务是否存活」的判断，到点即无条件尝试拉起。
 *    服务还活着时 onStartCommand 天然幂等；服务死了正好复活。维护一套 HeartbeatStore
 *    只会多出一条「进程内状态与持久化状态不一致」的故障路径。
 */
object MonitorWatchdog {

    /**
     * 看门狗周期。15 分钟是权衡结果：再短没有意义（Doze 下 allowWhileIdle 的调度
     * 粒度本来就在十几分钟量级，排得更密只会被系统合并）；再长则「被杀后自动恢复」
     * 的等待体验太差。
     */
    private const val INTERVAL_MS = 15L * 60L * 1_000L

    const val ACTION_WATCHDOG = "me.excuse.app.service.action.WATCHDOG"

    private const val REQUEST_CODE = 7
    private const val TAG = "ReasonWatchdog"

    /** 排下一次自检。同一个 PendingIntent 重复排程会被覆盖，不会累积。 */
    fun schedule(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val triggerAt = System.currentTimeMillis() + INTERVAL_MS
        val operation = pendingIntent(context)
        try {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, operation)
        } catch (error: Exception) {
            // 个别 ROM 拒绝 allowWhileIdle 语义；退到普通 set，至少不失去唤醒源，
            // 只是会受 Doze 批量调度影响而延后。
            Log.w(TAG, "setAndAllowWhileIdle rejected, falling back to set()", error)
            try {
                @Suppress("DEPRECATION")
                alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, operation)
            } catch (inner: Exception) {
                Log.e(TAG, "watchdog scheduling failed", inner)
            }
        }
    }

    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        try {
            alarmManager.cancel(pendingIntent(context))
        } catch (_: Exception) {
        }
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, WatchdogReceiver::class.java).apply {
            action = ACTION_WATCHDOG
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
