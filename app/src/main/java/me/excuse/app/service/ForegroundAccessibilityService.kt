package me.excuse.app.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import me.excuse.app.MainActivity

/**
 * 前台监控的唯一传感器，兼「系统托管」支点。
 *
 * 传感器：`TYPE_WINDOW_STATE_CHANGED` 由窗口管理器在焦点窗口切换瞬间同步发出，
 * 天然有序、无延迟。这里只做包级归一化（过滤规则见 [onAccessibilityEvent]），
 * 产出交给 [AccessibilityForegroundHub]；业务判断全部在 [AppMonitorService] 的状态机里。
 *
 * 支点：进程被 ROM 杀掉之后，只要无障碍开关还开着，系统会重新绑定本服务；
 * 「有服务被系统绑定」命中 Android 12+ 后台启动前台服务的豁免条款，
 * 所以 [onServiceConnected] 里的启动不会被 ForegroundServiceStartNotAllowedException
 * 拒绝。这是全项目最可靠的复活点。
 */
class ForegroundAccessibilityService : AccessibilityService() {

    /**
     * 输入法、SystemUI 等没有 launcher 入口的包不产生「前台切换」语义。
     * PackageManager 查询结果按包名缓存（主线程访问，无需加锁）。
     */
    private val launchableCache = HashMap<String, Boolean>()

    private val homePackage: String? by lazy {
        packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0,
        )?.activityInfo?.packageName
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.w(TAG, "connected; ensuring monitor service is running")
        AccessibilityForegroundHub.markConnected()
        AppMonitorService.startFromAccessibility(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val cls = event.className?.toString()

        // 本 app 的悬浮窗（拦截窗/到点窗）也带本包名，认了等于误判「用户离开了受监控
        // app」；只有用户真的回到主界面（MainActivity）才算一次前台切换。
        if (pkg == packageName && cls != MainActivity::class.java.name) return

        val foreground: String? = when {
            // 桌面 = 离开当前 app。Recents/通知栏/锁屏属于 SystemUI，没有 launcher
            // 入口，会被下面这条直接忽略 —— 停留在 Recents 上不算切换。
            pkg == homePackage -> null
            !isLaunchable(pkg) -> return
            else -> pkg
        }
        AccessibilityForegroundHub.publishWindow(foreground, cls, System.currentTimeMillis())
    }

    private fun isLaunchable(pkg: String): Boolean =
        launchableCache.getOrPut(pkg) {
            val intent = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setPackage(pkg)
            packageManager.queryIntentActivities(intent, 0).isNotEmpty()
        }

    override fun onInterrupt() {
        Log.w(TAG, "interrupted")
        AccessibilityForegroundHub.markDisconnected()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.w(TAG, "unbound")
        AccessibilityForegroundHub.markDisconnected()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        AccessibilityForegroundHub.markDisconnected()
        super.onDestroy()
    }

    private companion object {
        private const val TAG = "ReasonA11y"
    }
}
