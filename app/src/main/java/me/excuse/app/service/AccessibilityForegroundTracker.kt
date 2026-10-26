package me.excuse.app.service

import java.util.concurrent.CopyOnWriteArraySet

/**
 * 无障碍窗口事件 → 前台包名变化。
 *
 * 只认「包级切换」：同包内的窗口搅动（Dialog、PopupWindow、应用内跳页）不产生输出；
 * 包名变化即为一次真实的前台切换。事件由窗口管理器在焦点切换瞬间同步发出，天然有序、
 * 无延迟 —— UsageStats 那套消歧窗 / 证据水位线 / 迟到事件丢弃，在这个传感器下都不存在。
 *
 * 包过滤（IME、SystemUI、本 app 悬浮窗、Home 映射）由 [ForegroundAccessibilityService]
 * 在事件进来之前完成，本类不感知 Android API，可纯 JVM 单测。
 */
internal class AccessibilityForegroundTracker {

    data class Change(
        val packageName: String?,
        val at: Long,
        val isEntryEdge: Boolean,
    )

    var stickyPackageName: String? = null
        private set
    var stickyClassName: String? = null
        private set

    fun reset() {
        stickyPackageName = null
        stickyClassName = null
    }

    /** 不经过事件流的直接校准：播种、主动 sendHome 后对齐 sticky。 */
    fun forceForeground(packageName: String?, className: String? = null) {
        stickyPackageName = packageName
        stickyClassName = className
    }

    /**
     * 一个已过滤的窗口前台事件。[packageName] 为 null 表示「回到桌面 / 无可识别前台」。
     * 同包事件返回 null —— 不产生任何下游动作。
     */
    fun onWindowForeground(packageName: String?, className: String?, at: Long): Change? {
        if (packageName == stickyPackageName) return null
        stickyPackageName = packageName
        stickyClassName = className
        return Change(packageName, at, isEntryEdge = packageName != null)
    }

    fun debugState(): String = "a11ySticky=($stickyPackageName, $stickyClassName)"
}

/**
 * 无障碍服务与监控服务之间的进程内桥。
 *
 * 两者运行在同一进程，回调都发生在主线程；监听器集合仍用 CopyOnWriteArraySet，
 * 容忍极端时序下的并发注册/注销。
 *
 * [isConnected] 是「无障碍服务此刻真的被系统绑定」的进程内真相。注意它不等于
 * 「用户在设置里开了开关」：国产 ROM 杀掉无障碍服务后设置项常常仍是开的，
 * 监控门控必须用绑定状态，否则会在「开关看着是开的、事件其实没了」的情况下盲跑。
 */
internal object AccessibilityForegroundHub {

    val tracker = AccessibilityForegroundTracker()

    @Volatile
    var isConnected: Boolean = false
        private set

    private val changeListeners =
        CopyOnWriteArraySet<(AccessibilityForegroundTracker.Change) -> Unit>()
    private val connectionListeners = CopyOnWriteArraySet<(Boolean) -> Unit>()

    fun markConnected() {
        if (isConnected) return
        isConnected = true
        connectionListeners.forEach { it(true) }
    }

    fun markDisconnected() {
        if (!isConnected) return
        isConnected = false
        // 绑定断开期间事件流是断的，sticky 不再可信；重连后由播种重新建立。
        tracker.reset()
        connectionListeners.forEach { it(false) }
    }

    /** 由无障碍服务在「包级前台切换」时调用；非切换（同包搅动）不会通知监听者。 */
    fun publishWindow(packageName: String?, className: String?, at: Long) {
        val change = tracker.onWindowForeground(packageName, className, at) ?: return
        changeListeners.forEach { it(change) }
    }

    fun addChangeListener(listener: (AccessibilityForegroundTracker.Change) -> Unit) {
        changeListeners.add(listener)
    }

    fun removeChangeListener(listener: (AccessibilityForegroundTracker.Change) -> Unit) {
        changeListeners.remove(listener)
    }

    fun addConnectionListener(listener: (Boolean) -> Unit) {
        connectionListeners.add(listener)
    }

    fun removeConnectionListener(listener: (Boolean) -> Unit) {
        connectionListeners.remove(listener)
    }
}
