package me.excuse.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 接收 [SessionDeadlineAlarm] 注册的到点闹钟。闹钟触发时唤醒服务进程，
 * 让 [AppMonitorService] 推进状态机到超时。即使进程此前被厂商 ROM 冻结，
 * 系统闹钟仍会拉起本 receiver，再通过前台服务把「时间到了」窗口弹出来。
 */
class DeadlineAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SessionDeadlineAlarm.ACTION_SESSION_DEADLINE) return
        val packageName = intent.getStringExtra(SessionDeadlineAlarm.EXTRA_PACKAGE_NAME) ?: return
        AppMonitorService.startForDeadline(context, packageName)
    }
}
