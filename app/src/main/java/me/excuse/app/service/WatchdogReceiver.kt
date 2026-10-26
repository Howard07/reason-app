package me.excuse.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 看门狗闹钟的接收端。
 *
 * 到点后先续排下一次，再尝试拉起服务。这个顺序不能反：拉起过程可能抛异常
 * （Android 12+ 后台启动前台服务会被拒），此时续排已完成，下一个周期仍会再来一次；
 * 反过来写就永久断链，而且断得无声无息。
 */
class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != MonitorWatchdog.ACTION_WATCHDOG) return
        MonitorWatchdog.schedule(context)
        AppMonitorService.startForWatchdog(context)
    }
}
