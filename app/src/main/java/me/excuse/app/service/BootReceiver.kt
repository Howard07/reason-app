package me.excuse.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED) {
            // 先排看门狗再拉服务：即使这次 startForegroundService 被系统拒绝，
            // 15 分钟后的自检仍会把服务带回来，不会出现「开机后一直没监控」。
            MonitorWatchdog.schedule(context)
            AppMonitorService.start(context)
        }
    }
}
