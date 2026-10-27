package me.excuse.app.service

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
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

    /**
     * 当前启用的输入法包名。服务每次被系统重新绑定都会重建实例，因此这里拿到的
     * 始终是本轮最新的输入法列表（用户换输入法后无需额外刷新逻辑）。
     */
    private val inputMethodPackages: Set<String> by lazy { resolveInputMethodPackages() }

    private fun resolveInputMethodPackages(): Set<String> {
        val packages = LinkedHashSet<String>()
        runCatching {
            Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
                ?.let { ComponentName.unflattenFromString(it)?.packageName }
                ?.let(packages::add)
        }
        runCatching {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.enabledInputMethodList
                ?.forEach { packages.add(it.packageName) }
        }
        Log.w(TAG, "input method packages=$packages")
        return packages
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

        val classified = classifyAccessibilityWindow(
            packageName = pkg,
            className = cls,
            windowType = windowTypeOf(event),
            ownPackage = packageName,
            ownMainActivityClassName = MainActivity::class.java.name,
            homePackage = homePackage,
            inputMethodPackages = inputMethodPackages,
            isLaunchable = ::isLaunchable,
        )
        val foreground: String? = when (classified) {
            AccessibilityWindowClass.Ignore -> return
            AccessibilityWindowClass.Home -> null
            is AccessibilityWindowClass.App -> classified.packageName
        }
        AccessibilityForegroundHub.publishWindow(foreground, cls, System.currentTimeMillis())
    }

    /**
     * 事件所属窗口的类型。拿不到（部分 ROM 的 getWindows 返回空，或 windowId 无效）
     * 时返回 null，分类逻辑会退回纯包名判据。
     */
    private fun windowTypeOf(event: AccessibilityEvent): Int? {
        val windows = runCatching { windows }.getOrNull() ?: return null
        if (windows.isEmpty()) return null
        return windows.firstOrNull { it.id == event.windowId }?.type
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
